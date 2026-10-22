package io.github.ddagunts.screencast.webrtc

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import org.webrtc.audio.JavaAudioDeviceModule

// Makes libwebrtc's JavaAudioDeviceModule read device playback instead of the
// microphone, with no microphone AudioRecord constructed or started by this
// app's code.
//
// FAIL-CLOSED DESIGN. The ADM is built with audioSource = REMOTE_SUBMIX
// (WebRtcPeer), so a recorder that libwebrtc builds on its own can never be a
// microphone recorder (REMOTE_SUBMIX needs CAPTURE_AUDIO_OUTPUT; construction
// fails => init error => PlaybackAudioGuard). The swap itself never builds a
// mic record, in ONE synchronized(audioRecordStateLock) section:
//   setUseAudioRecord(false)  -> initRecordingIfNeeded() (allocates buffers,
//   skips initAudioRecord) -> audioRecord = our playback record ->
//   setUseAudioRecord(true).
// It first requires audioThread == null (native start not requested yet) and
// audioRecord == null; otherwise video-only. The guard is defense in depth:
// any ADM error or stop disables ADM recording (setUseAudioRecord(false)); the
// capture thread then releases the record on its next loop (~10 ms) and keeps
// feeding silence. Nothing ever re-swaps.
//
// Pinned to io.github.webrtc-sdk:android:125.6422.07 (bytecode reviewed:
// initRecordingImpl, startRecordingImpl, stopRecordingIfNeededImpl,
// AudioRecordThread.run). WebRtcLibraryContractTest fails if a reflected
// member changes. ANY version bump requires re-running that bytecode review.
//
// Never call setPreferredDevice / the preferred-input-device API on our installed record:
// it could re-route capture. (A source-level test enforces this.)
//
// Note: stopRecordingIfNeeded() is a no-op while the native side has called
// startRecording, so it is NOT used to quiesce; setUseAudioRecord(false) is.

/** Names reflected on; keep in sync with app/proguard-rules.pro and the contract test. */
internal object AdmReflection {
    const val ADM_CLASS = "org.webrtc.audio.JavaAudioDeviceModule"
    const val RECORD_CLASS = "org.webrtc.audio.WebRtcAudioRecord"
    const val F_AUDIO_INPUT = "audioInput"
    const val F_AUDIO_RECORD = "audioRecord"
    const val F_AUDIO_THREAD = "audioThread"
    const val F_STATE_LOCK = "audioRecordStateLock"
    const val M_INIT_IF_NEEDED = "initRecordingIfNeeded"
    const val M_SET_USE_AUDIO_RECORD = "setUseAudioRecord"
}

// Top-level access to the Any.logX extensions.
internal object WebRtcAudioLog {
    fun w(msg: String) = logW(msg)
    fun i(msg: String) = logI(msg)
}

// ---- Testable seam -------------------------------------------------------

internal interface PlaybackRecord {
    val state: Int
    val isRecording: Boolean
    /** Idempotent: releases at most once. */
    fun release()
}

internal interface AdmPort {
    /** Runs [block] holding the ADM's audioRecordStateLock. */
    fun <T> atomically(block: () -> T): T
    /** audioThread == null: native has not started recording. */
    fun isCaptureThreadIdle(): Boolean
    fun hasRecord(): Boolean
    /** setUseAudioRecord, written under the lock. */
    fun setRecordEnabled(enabled: Boolean)
    /** initRecordingIfNeeded(); with recording disabled it creates no AudioRecord. */
    fun initRecording(): Boolean
    fun buildPlayback(): PlaybackRecord
    /** Sets (or with null clears) the ADM's audioRecord field. */
    fun install(record: PlaybackRecord?)
    /** Identity: the ADM's active record is exactly [record]. */
    fun isInstalled(record: PlaybackRecord): Boolean
    /** Diagnostic only; never used for decisions. */
    fun diagnosticSource(record: PlaybackRecord): Int?
    /** The record is no longer recording (or no longer installed). */
    fun isQuiet(record: PlaybackRecord): Boolean
    /** Flag write off the calling thread (callers may be libwebrtc threads). Must not throw. */
    fun disableRecording()
    /** Same, but completed before returning. Must not throw. */
    fun disableRecordingNow()
}

/** Returns the playback record now owned by the ADM, or null (recording disabled) for video-only. */
internal fun swapPlayback(
    port: AdmPort, projectionAvailable: Boolean, log: (String) -> Unit,
    onInstalled: (PlaybackRecord) -> Unit = {},
): PlaybackRecord? {
    if (!projectionAvailable) {
        log("webrtc audio: no MediaProjection — video only")
        port.disableRecording()
        return null
    }
    var built: PlaybackRecord? = null
    var installed = false
    try {
        port.atomically {
            check(port.isCaptureThreadIdle()) { "native recording already started" }
            check(!port.hasRecord()) { "ADM already has a record" }
            port.setRecordEnabled(false)
            check(port.initRecording()) { "initRecordingIfNeeded failed" }
            val rec = port.buildPlayback()
            built = rec
            check(rec.state == AUDIO_RECORD_STATE_INITIALIZED) { "playback AudioRecord state=${rec.state}" }
            port.install(rec)
            installed = true
            onInstalled(rec) // identity reference must exist before the library thread can start
            port.setRecordEnabled(true)
            check(port.isInstalled(rec)) { "ADM does not hold our record" }
        }
        val rec = built!!
        log("webrtc audio: swap ok (diagnostic source=${runCatching { port.diagnosticSource(rec) }.getOrNull()})")
        return rec
    } catch (t: Throwable) {
        log("webrtc audio: swap failed — video only: $t")
        if (installed) runCatching { port.install(null) }
        port.disableRecording()
        runCatching { built?.release() }
        return null
    }
}

/**
 * Trips (once) when the ADM could otherwise re-create a recorder.
 * Reacts by disabling ADM recording; audio ends, video continues.
 */
internal class PlaybackAudioGuard(
    private val disable: () -> Unit,
    private val isOurRecordActive: () -> Boolean,
    private val log: (String) -> Unit,
    private val disableBlocking: () -> Unit = disable,
) {
    private val done = AtomicBoolean(false)
    val isTripped: Boolean get() = done.get()

    fun onRecordStart() { if (!isOurRecordActive()) trip("ADM started a record that is not ours") }
    fun onRecordStop() = trip("ADM stopped recording; a restart would make the library build its own record")
    fun onInitError(msg: String?) = trip("ADM init error: $msg")
    fun onStartError(msg: String?) = trip("ADM start error: $msg")
    fun onError(msg: String?) = trip("ADM error: $msg")

    /** Orderly teardown: disable ADM recording synchronously. Silent. */
    fun shutdown() { if (done.compareAndSet(false, true)) runCatching { disableBlocking() } }

    private fun trip(reason: String) {
        if (!done.compareAndSet(false, true)) return
        log("webrtc audio: $reason — guard disabled ADM recording; continuing video-only")
        runCatching { disable() }
    }
}

// ---- Reflection glue -----------------------------------------------------

internal class RealPlaybackRecord(val rec: AudioRecord) : PlaybackRecord {
    private val once = ReleaseOnce { rec.release() }
    override val state: Int get() = rec.state
    override val isRecording: Boolean get() = rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
    override fun release() = once.run()
}

internal class RealAdmPort(private val adm: JavaAudioDeviceModule) : AdmPort {
    @Volatile var projection: MediaProjection? = null
    private val input: Any = adm.audioInput
    private val cls = input.javaClass
    private val recField = cls.getDeclaredField(AdmReflection.F_AUDIO_RECORD).apply { isAccessible = true }
    private val threadField = cls.getDeclaredField(AdmReflection.F_AUDIO_THREAD).apply { isAccessible = true }
    private val lock: Any = cls.getDeclaredField(AdmReflection.F_STATE_LOCK).apply { isAccessible = true }.get(input)
        ?: error("audioRecordStateLock null")
    private val initIfNeeded: Method = cls.getDeclaredMethod(AdmReflection.M_INIT_IF_NEEDED).apply { isAccessible = true }

    override fun <T> atomically(block: () -> T): T = synchronized(lock) { block() }
    override fun isCaptureThreadIdle() = threadField.get(input) == null
    override fun hasRecord() = recField.get(input) != null
    override fun setRecordEnabled(enabled: Boolean) { synchronized(lock) { adm.setAudioRecordEnabled(enabled) } }
    override fun initRecording(): Boolean = initIfNeeded.invoke(input) == true

    override fun buildPlayback(): PlaybackRecord {
        if (Build.VERSION.SDK_INT < MIN_PLAYBACK_CAPTURE_SDK) error("API<29: no playback capture")
        return buildPlaybackQ(requireNotNull(projection) { "no MediaProjection" })
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildPlaybackQ(projection: MediaProjection): PlaybackRecord {
        val rate = WEBRTC_AUDIO_SAMPLE_RATE
        val ch = WEBRTC_AUDIO_CHANNELS
        val mask = if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(mask)
            .build()
        val minBytes = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission") // gated on RECORD_AUDIO by WebRtcSessionConfig.audioActive
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(playbackBufferBytes(minBytes, rate, ch))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        return RealPlaybackRecord(rec)
    }

    override fun install(record: PlaybackRecord?) {
        synchronized(lock) { recField.set(input, (record as RealPlaybackRecord?)?.rec) }
    }

    override fun isInstalled(record: PlaybackRecord): Boolean = synchronized(lock) {
        recField.get(input) === (record as RealPlaybackRecord).rec
    }

    override fun diagnosticSource(record: PlaybackRecord): Int? = (record as RealPlaybackRecord).rec.audioSource

    override fun isQuiet(record: PlaybackRecord): Boolean = !record.isRecording || !isInstalled(record)

    override fun disableRecording() {
        Thread({ disableRecordingNow() }, "webrtc-audio-disable").apply { isDaemon = true }.start()
    }

    override fun disableRecordingNow() {
        runCatching { setRecordEnabled(false) }.onFailure { WebRtcAudioLog.w("webrtc audio: setUseAudioRecord(false): $it") }
    }
}

// ---- Level meter ---------------------------------------------------------

// Pure: peak absolute sample (0..32768) of 16-bit little-endian PCM in
// [pos, pos+len) of buf. Reads by absolute index; never touches position.
internal fun pcm16Peak(buf: ByteBuffer, pos: Int, len: Int): Int {
    var peak = 0
    val end = minOf(pos + len, buf.limit()) - 1
    var i = pos
    while (i < end) {
        val s = (buf.get(i).toInt() and 0xFF) or (buf.get(i + 1).toInt() shl 8)
        val a = kotlin.math.abs(s.toShort().toInt())
        if (a > peak) peak = a
        i += 2
    }
    return peak
}

// Observes (never modifies) the PCM libwebrtc's ADM is about to encode and logs
// the peak level at most once every 5 s. Peak only — no audio content is logged.
// Does nothing while [enabled] is false.
internal class WebRtcAudioMeter(
    private val recordingState: () -> Int,
    private val nowNs: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = WebRtcAudioLog::i,
    private val enabled: () -> Boolean = { true },
) : JavaAudioDeviceModule.AudioBufferCallback {
    private var windowStartNs = 0L
    private var windowPeak = 0
    private var calls = 0

    override fun onBuffer(
        buffer: ByteBuffer, audioFormat: Int, channelCount: Int,
        sampleRate: Int, bytesRead: Int, captureTimestampNs: Long,
    ): Long {
        if (!enabled()) return 0L
        val now = nowNs()
        if (windowStartNs == 0L) windowStartNs = now
        calls++
        windowPeak = maxOf(windowPeak, pcm16Peak(buffer, buffer.position(), bytesRead))
        if (now - windowStartNs >= LOG_INTERVAL_NS) {
            log("webrtc audio: peak=$windowPeak/32768 buffers=$calls recordState=${recordingState()}")
            windowStartNs = now; windowPeak = 0; calls = 0
        }
        return 0L
    }

    private companion object { const val LOG_INTERVAL_NS = 5_000_000_000L }
}
