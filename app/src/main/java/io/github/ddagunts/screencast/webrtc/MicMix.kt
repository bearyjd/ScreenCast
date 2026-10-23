package io.github.ddagunts.screencast.webrtc

import java.nio.ByteBuffer
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

internal enum class MicMixStatus { IDLE, ACTIVE, DENIED, FAILED, STOPPED }

internal const val MIC_MIX_SAMPLE_RATE = 48_000
// Ring = 100 ms. Drift policy: the mic and the ADM run on independent clocks. Mic faster than
// playback -> ring fills, oldest samples are dropped (a rare click, latency bounded at 100 ms).
// Mic slower -> underrun, mic contributes silence for that buffer. No resampling.
private const val RING_SAMPLES = MIC_MIX_SAMPLE_RATE / 10
private const val READ_CHUNK = MIC_MIX_SAMPLE_RATE / 100 // 10 ms
private const val MAX_EMPTY_READS = 100
private const val JOIN_MS = 300L

/** Owns the mic record and reader thread. Any failure degrades to playback-only; it never throws. */
internal class MicMixController(
    private val micPermissionGranted: () -> Boolean,
    private val factory: MicSourceFactory,
    private val log: (String) -> Unit,
    private val spawn: (Runnable) -> Thread = { Thread(it, "mic-mix").apply { isDaemon = true } },
) {
    private val lock = Any()
    private val ring = MicRingBuffer(RING_SAMPLES)
    private val chunk = ShortArray(READ_CHUNK)
    private var emptyReads = 0
    @Volatile private var state = MicMixStatus.IDLE
    private var source: MicSource? = null
    private var thread: Thread? = null

    val status: MicMixStatus get() = state
    val isActive: Boolean get() = state == MicMixStatus.ACTIVE

    /** Starts the mic. Re-checks the runtime permission (a per-app mic toggle can flip at any time). */
    fun start(): MicMixStatus {
        synchronized(lock) {
            if (state != MicMixStatus.IDLE) return state
            if (!micPermissionGranted()) {
                state = MicMixStatus.DENIED
                log("mic mix: Microphone permission denied; casting playback audio only")
                return state
            }
            val src = try { factory.create(MIC_MIX_SAMPLE_RATE) } catch (t: Throwable) {
                state = MicMixStatus.FAILED
                log("mic mix: microphone unavailable ($t); casting playback audio only")
                return state
            }
            if (src == null || !src.initialized || !runCatching { src.start() }.getOrDefault(false)) {
                runCatching { src?.release() }
                state = MicMixStatus.FAILED
                log("mic mix: microphone could not start; casting playback audio only")
                return state
            }
            source = src
            state = MicMixStatus.ACTIVE
            log("mic mix: microphone mix active")
            thread = spawn { while (pumpOnce()) { /* loop until stopped or failed */ } }.also { it.start() }
            return state
        }
    }

    /** One blocking read into the ring. False when the loop must end. */
    fun pumpOnce(): Boolean {
        val src = synchronized(lock) { if (state == MicMixStatus.ACTIVE) source else null } ?: return false
        val n = try { src.read(chunk, chunk.size) } catch (t: Throwable) { -1 }
        if (state != MicMixStatus.ACTIVE) return false
        when {
            n < 0 -> return degrade("microphone read failed ($n)")
            n == 0 -> if (++emptyReads >= MAX_EMPTY_READS) return degrade("microphone returned no data")
            else -> { emptyReads = 0; ring.write(chunk, n) }
        }
        return true
    }

    private fun degrade(reason: String): Boolean {
        synchronized(lock) {
            if (state != MicMixStatus.ACTIVE) return false
            state = MicMixStatus.FAILED
            log("mic mix: $reason; continuing with playback audio only")
            releaseSourceLocked()
        }
        return false
    }

    /** Samples for the audio thread; fewer than [count] (possibly 0) on underrun. Never blocks on I/O. */
    fun fillMic(dst: ShortArray, count: Int): Int = ring.read(dst, count)

    /** Idempotent. Safe before start; a controller stopped before start can never start. */
    fun stop() {
        val t: Thread?
        synchronized(lock) {
            if (state == MicMixStatus.STOPPED) return
            val wasActive = state == MicMixStatus.ACTIVE
            state = MicMixStatus.STOPPED
            t = thread; thread = null
            runCatching { source?.stop() } // unblocks the reader
            if (wasActive) log("mic mix: microphone stopped")
        }
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(JOIN_MS) }
        synchronized(lock) { releaseSourceLocked() }
        ring.clear()
    }

    private fun releaseSourceLocked() {
        val s = source ?: return
        source = null
        runCatching { s.stop() }
        runCatching { s.release() }
    }
}

/**
 * The ONLY way a mic controller comes to exist: null unless the user turned the mix on and
 * playback audio is active. Default config therefore never reaches the factory.
 */
internal fun createMicMixController(
    config: WebRtcSessionConfig, micPermissionGranted: () -> Boolean, factory: MicSourceFactory, log: (String) -> Unit,
): MicMixController? =
    if (config.micMixActive) MicMixController(micPermissionGranted, factory, log) else null

/**
 * Mixes the mic into the playback PCM libwebrtc is about to encode, only while the controller is
 * ACTIVE, [enabled] (guard not tripped) and the buffer is the expected 48 kHz stereo PCM16.
 * Otherwise the buffer is untouched. Never throws into the audio thread.
 */
internal class MicMixBufferCallback(
    private val delegate: JavaAudioDeviceModule.AudioBufferCallback,
    private val mic: MicMixController,
    private val enabled: () -> Boolean,
    private val log: (String) -> Unit,
) : JavaAudioDeviceModule.AudioBufferCallback {
    private var scratch = ShortArray(MIC_MIX_SAMPLE_RATE / 50)
    @Volatile private var broken = false

    override fun onBuffer(
        buffer: ByteBuffer, audioFormat: Int, channelCount: Int,
        sampleRate: Int, bytesRead: Int, captureTimestampNs: Long,
    ): Long {
        val r = delegate.onBuffer(buffer, audioFormat, channelCount, sampleRate, bytesRead, captureTimestampNs)
        if (broken || !enabled() || !mic.isActive) return r
        if (audioFormat != ENCODING_PCM_16BIT || channelCount != 2 || sampleRate != MIC_MIX_SAMPLE_RATE) return r
        try {
            val frames = bytesRead / 4
            if (scratch.size < frames) scratch = ShortArray(frames)
            val got = mic.fillMic(scratch, frames)
            PcmMixer.mixMonoIntoStereo(buffer, buffer.position(), bytesRead, scratch, got)
        } catch (t: Throwable) {
            broken = true
            log("mic mix: mixing disabled after error: $t")
        }
        return r
    }

    private companion object { const val ENCODING_PCM_16BIT = 2 } // AudioFormat.ENCODING_PCM_16BIT
}
