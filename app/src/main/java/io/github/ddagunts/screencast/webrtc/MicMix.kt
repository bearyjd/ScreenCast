package io.github.ddagunts.screencast.webrtc

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import org.webrtc.audio.JavaAudioDeviceModule

// Opt-in microphone mix. The invariant "the ADM never records the microphone" is UNCHANGED: the ADM
// stays pinned to REMOTE_SUBMIX behind PlaybackAudioGuard. The mic is read by a SEPARATE AudioRecord
// (AndroidMicSource) on its own thread, and mixed into the playback PCM inside the ADM's
// AudioBufferCallback. See docs/mic-mix-TDD.md.

/** Abstraction over the mic AudioRecord so the degrade paths run on the JVM. */
internal interface MicSource {
    val initialized: Boolean
    /** True when recording actually started. */
    fun start(): Boolean
    /** Blocking read of mono samples; returns count, or < 0 on error. */
    fun read(dst: ShortArray, size: Int): Int
    fun stop()
    fun release()
}

internal fun interface MicSourceFactory { fun create(sampleRate: Int): MicSource? }

internal enum class MicMixStatus { IDLE, STARTING, ACTIVE, DENIED, FAILED, STOPPED }

/** Notification view of the controller: ACTIVE only while the mic is recording and not silent. */
internal fun micMixDisplay(status: MicMixStatus, silent: Boolean): MicMixDisplay = when (status) {
    MicMixStatus.IDLE, MicMixStatus.STARTING -> MicMixDisplay.STARTING
    MicMixStatus.ACTIVE -> if (silent) MicMixDisplay.SILENT else MicMixDisplay.ACTIVE
    MicMixStatus.DENIED, MicMixStatus.FAILED, MicMixStatus.STOPPED -> MicMixDisplay.STOPPED
}

internal const val MIC_MIX_SAMPLE_RATE = 48_000
// Ring = 100 ms hard cap. Drift policy: the mic and the ADM run on independent clocks. Mic faster than
// playback -> ring fills; the consumer trims to a ~30 ms target (dropping the OLDEST samples, a rare
// click) on its first read and whenever the fill exceeds the ~60 ms high-water mark, so steady-state
// latency stays near the target instead of the cap. Mic slower -> underrun, silent mic for that buffer.
private const val RING_SAMPLES = MIC_MIX_SAMPLE_RATE / 10
internal const val MIC_RING_HIGH_WATER_SAMPLES = MIC_MIX_SAMPLE_RATE * 60 / 1000
internal const val MIC_RING_TARGET_SAMPLES = MIC_MIX_SAMPLE_RATE * 30 / 1000
private const val READ_CHUNK = MIC_MIX_SAMPLE_RATE / 100 // 10 ms
private const val MAX_EMPTY_READS = 100
/** Consecutive all-zero 10 ms reads (~2 s) before the mic is reported silent. */
internal const val MIC_SILENT_READS = 200
private const val JOIN_MS = 300L
/** An ACTIVE mic that nobody has pulled samples from for this long is recording for no reason. */
internal const val MIC_NO_CONSUMER_MS = 2_000L

/** Peer ICE states after which the cast is not (or no longer reliably) delivering audio: the mic stops. */
internal fun shouldStopMicOnIce(state: org.webrtc.PeerConnection.IceConnectionState): Boolean = when (state) {
    org.webrtc.PeerConnection.IceConnectionState.DISCONNECTED,
    org.webrtc.PeerConnection.IceConnectionState.FAILED,
    org.webrtc.PeerConnection.IceConnectionState.CLOSED -> true
    else -> false
}

/**
 * Owns the mic record and reader thread. Any failure degrades to playback-only; it never throws.
 * Blocking calls into the record (create/start/stop/release/join) are made outside [lock].
 */
internal class MicMixController(
    private val micPermissionGranted: () -> Boolean,
    private val factory: MicSourceFactory,
    private val log: (String) -> Unit,
    private val spawn: (Runnable) -> Thread = { Thread(it, "mic-mix").apply { isDaemon = true } },
    private val runAsync: (Runnable) -> Unit = { Thread(it, "mic-mix-stop").apply { isDaemon = true }.start() },
    /** Told every time the user-visible mic state changes (notification truthfulness). Must not block. */
    private val onDisplay: (MicMixDisplay) -> Unit = {},
    /** Monotonic milliseconds; injectable for tests. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Any()
    private val ring = MicRingBuffer(RING_SAMPLES)
    private val chunk = ShortArray(READ_CHUNK)
    private var emptyReads = 0
    private var zeroReads = 0
    private var primed = false // audio thread only
    @Volatile private var lastFillMs = Long.MIN_VALUE
    @Volatile private var activeSinceMs = 0L
    private var noConsumerLogged = false // reader thread only
    private val displayLock = Any()
    @Volatile private var state = MicMixStatus.IDLE
        set(v) { field = v; publishDisplay() }
    @Volatile private var silent = false
        set(v) { field = v; publishDisplay() }
    private var source: MicSource? = null
    private var thread: Thread? = null
    private val stopRequested = AtomicBoolean(false)

    private fun publishDisplay() = synchronized(displayLock) { runCatching { onDisplay(micMixDisplay(state, silent)) } }

    val status: MicMixStatus get() = state
    val isActive: Boolean get() = state == MicMixStatus.ACTIVE
    /** The mic is recording but delivering only zeros (system sensor toggle / no while-in-use access). */
    val isSilent: Boolean get() = silent && state == MicMixStatus.ACTIVE
    val buffered: Int get() = ring.available
    /** Human-readable state; never says "active" while the mic delivers silence. */
    val statusText: String get() = when (state) {
        MicMixStatus.ACTIVE -> if (silent) "mic silent (blocked by system?)" else "mic mix active"
        MicMixStatus.DENIED -> "mic permission denied"
        MicMixStatus.FAILED -> "mic unavailable"
        else -> "mic mix off"
    }

    /** Starts the mic. Re-checks the runtime permission (a per-app mic toggle can flip at any time). */
    fun start(): MicMixStatus {
        synchronized(lock) {
            if (state != MicMixStatus.IDLE) return state
            state = MicMixStatus.STARTING
        }
        if (!micPermissionGranted()) return failStart(MicMixStatus.DENIED, "Microphone permission denied; casting playback audio only")
        val created = runCatching { factory.create(MIC_MIX_SAMPLE_RATE) }
        val src = created.getOrNull()
        created.exceptionOrNull()?.let { return failStart(MicMixStatus.FAILED, "microphone unavailable ($it); casting playback audio only") }
        if (state != MicMixStatus.STARTING) { // stop() won the race while the record was being created
            runCatching { src?.release() }
            return state
        }
        val started = src != null && src.initialized && runCatching { src.start() }.getOrDefault(false)
        if (!started) {
            runCatching { src?.release() }
            return failStart(MicMixStatus.FAILED, "microphone could not start; casting playback audio only")
        }
        // The reader thread owns the record's release: it releases after its loop exits, so the record
        // is never released while a read is in flight. The thread is created (not started) first so it
        // is adopted together with the source and stop() always sees both.
        val reader = runCatching { spawn(Runnable { try { while (pumpOnce()) { /* until stopped or failed */ } } finally { releaseSource(src) } }) }
        reader.exceptionOrNull()?.let {
            runCatching { src!!.release() }
            return failStart(MicMixStatus.FAILED, "could not create the microphone reader thread ($it); casting playback audio only")
        }
        val adopted = synchronized(lock) {
            if (state == MicMixStatus.STARTING) { activeSinceMs = clock(); source = src; thread = reader.getOrNull(); state = MicMixStatus.ACTIVE; true } else false
        }
        if (!adopted) { // stop() won the race while the record was being created
            runCatching { src!!.stop() }; runCatching { src!!.release() }
            return state
        }
        log("mic mix: microphone mix active")
        runCatching { reader.getOrThrow().start() }.onFailure {
            degrade("could not start the microphone reader thread ($it)")
        }
        return state
    }

    private fun failStart(status: MicMixStatus, message: String): MicMixStatus {
        val set = synchronized(lock) { if (state == MicMixStatus.STARTING) { state = status; true } else false }
        if (set) log("mic mix: $message")
        return state
    }

    /** One blocking read into the ring. False when the loop must end. */
    fun pumpOnce(): Boolean {
        val src = synchronized(lock) { if (state == MicMixStatus.ACTIVE) source else null } ?: return false
        val n = try { src.read(chunk, chunk.size) } catch (e: Exception) {
            if (state == MicMixStatus.ACTIVE) log("mic mix: microphone read threw ${e.javaClass.simpleName}: ${e.message}")
            -1
        }
        if (state != MicMixStatus.ACTIVE) return false
        if (clock() - maxOf(lastFillMs, activeSinceMs) > MIC_NO_CONSUMER_MS) {
            // Recording with nobody mixing the samples (ADM not delivering): do not keep the mic open.
            if (!noConsumerLogged) { noConsumerLogged = true; log("mic mix: no consumer for the microphone samples for ${MIC_NO_CONSUMER_MS} ms; microphone stopped") }
            stopAsync()
            return false
        }
        when {
            n < 0 -> return degrade("microphone read failed ($n)")
            n == 0 -> if (++emptyReads >= MAX_EMPTY_READS) return degrade("microphone returned no data")
            else -> { emptyReads = 0; trackSilence(n); ring.write(chunk, n) }
        }
        return true
    }

    private fun trackSilence(n: Int) {
        var allZero = true
        for (i in 0 until n) if (chunk[i].toInt() != 0) { allZero = false; break }
        if (!allZero) { zeroReads = 0; silent = false; return }
        if (++zeroReads >= MIC_SILENT_READS && !silent) {
            silent = true
            log("mic mix: mic silent (blocked by system?); playback audio continues, microphone is not contributing")
        }
    }

    private fun degrade(reason: String): Boolean {
        val s = synchronized(lock) {
            if (state != MicMixStatus.ACTIVE) return false
            state = MicMixStatus.FAILED
            log("mic mix: $reason; continuing with playback audio only")
            source.also { source = null }
        }
        releaseSource(s)
        return false
    }

    /**
     * Samples for the audio thread; fewer than [count] (possibly 0) on underrun. Never blocks on I/O.
     * Trims the ring to the latency target on the first read and above the high-water mark.
     */
    fun fillMic(dst: ShortArray, count: Int): Int {
        lastFillMs = clock()
        if (!primed || ring.available > MIC_RING_HIGH_WATER_SAMPLES) { primed = true; ring.trimTo(MIC_RING_TARGET_SAMPLES) }
        return ring.read(dst, count)
    }

    /** Idempotent. Safe before start; a controller stopped before start can never start. */
    fun stop() {
        val t: Thread?
        val s: MicSource?
        val wasActive: Boolean
        synchronized(lock) {
            if (state == MicMixStatus.STOPPED) return
            wasActive = state == MicMixStatus.ACTIVE
            state = MicMixStatus.STOPPED
            t = thread; thread = null
            s = source; source = null
        }
        runCatching { s?.stop() } // unblocks the reader
        if (wasActive) log("mic mix: microphone stopped")
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(JOIN_MS) }
        // A live reader releases the record itself once its read returns; never release under it.
        if (t == null || !t.isAlive) releaseSource(s)
        ring.clear()
    }

    /** [stop] off the calling thread (the ADM audio thread must never wait on AudioRecord). Idempotent. */
    fun stopAsync() {
        if (!stopRequested.compareAndSet(false, true)) return
        if (runCatching { runAsync(Runnable { stop() }) }.isFailure) stop()
    }

    private val released = AtomicBoolean(false)

    /** Exactly once per controller (one source per controller), from whichever side finishes with it. */
    private fun releaseSource(s: MicSource?) {
        if (s == null || !released.compareAndSet(false, true)) return
        runCatching { s.stop() }
        runCatching { s.release() }
    }
}

/** A guard trip (ADM failure / unexpected record) ends the mic too. Off the audio thread via stopAsync. */
internal fun wireMicToGuard(controller: PlaybackAudioController, mic: MicMixController) {
    controller.onGuardTrip = { mic.stopAsync() }
}

/**
 * The ONLY way a mic controller comes to exist: null unless the user turned the mix on and
 * playback audio is active. Default config therefore never reaches the factory.
 */
internal fun createMicMixController(
    config: WebRtcSessionConfig, micPermissionGranted: () -> Boolean, factory: MicSourceFactory, log: (String) -> Unit,
    onDisplay: (MicMixDisplay) -> Unit = {},
): MicMixController? =
    if (config.micMixActive) MicMixController(micPermissionGranted, factory, log, onDisplay = onDisplay) else null

/**
 * Mixes the mic into the playback PCM libwebrtc is about to encode, only while the controller is
 * ACTIVE, [enabled] (guard not tripped) and the buffer is the expected 48 kHz stereo PCM16.
 * Otherwise the buffer is untouched. Any condition that makes mixing permanently invalid
 * (guard tripped, odd format, mixer error, feedback) stops the mic. Never throws into the audio thread.
 */
internal class MicMixBufferCallback(
    private val delegate: JavaAudioDeviceModule.AudioBufferCallback,
    private val mic: MicMixController,
    private val enabled: () -> Boolean,
    private val log: (String) -> Unit,
    private val gate: MicGate = MicGate(),
    private val feedback: FeedbackGuard = FeedbackGuard(),
) : JavaAudioDeviceModule.AudioBufferCallback {
    private var scratch = ShortArray(MIC_MIX_SAMPLE_RATE / 50)
    @Volatile private var broken = false
    private var formatLogged = false

    override fun onBuffer(
        buffer: ByteBuffer, audioFormat: Int, channelCount: Int,
        sampleRate: Int, bytesRead: Int, captureTimestampNs: Long,
    ): Long {
        val r = delegate.onBuffer(buffer, audioFormat, channelCount, sampleRate, bytesRead, captureTimestampNs)
        if (broken || !mic.isActive) return r
        if (!enabled()) { mic.stopAsync(); return r }
        if (audioFormat != ENCODING_PCM_16BIT || channelCount != 2 || sampleRate != MIC_MIX_SAMPLE_RATE) {
            if (!formatLogged) {
                formatLogged = true
                log("mic mix: unexpected audio format (enc=$audioFormat ch=$channelCount rate=$sampleRate); microphone mix stopped")
            }
            mic.stopAsync()
            return r
        }
        try {
            val frames = bytesRead / 4
            if (scratch.size < frames) scratch = ShortArray(frames)
            val got = mic.fillMic(scratch, frames)
            // Base offset 0: libwebrtc fills the direct buffer from index 0 regardless of position().
            PcmMixer.mixMonoIntoStereo(buffer, 0, bytesRead, scratch, got, gate)
            if (feedback.onBlock(gate.takeBlockPeak(), frames)) {
                broken = true
                log("mic mix: sustained loud microphone level (feedback from the TV speaker?); microphone mix muted for this cast")
                mic.stopAsync()
            }
        } catch (t: Throwable) {
            broken = true
            log("mic mix: mixing disabled after error: $t")
            mic.stopAsync()
        }
        return r
    }

    private companion object { const val ENCODING_PCM_16BIT = 2 } // AudioFormat.ENCODING_PCM_16BIT
}
