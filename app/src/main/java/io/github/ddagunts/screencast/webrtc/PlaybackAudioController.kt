package io.github.ddagunts.screencast.webrtc

import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The audio half of WebRtcPeer, behind a seam so its invariants run on the JVM:
 * callbacks feed the guard, the swap decides whether an audio track may exist,
 * close() quiesces the capture record before the projection stops, and the
 * record is released exactly once.
 */
internal class PlaybackAudioController(
    private val log: (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) {
    @Volatile private var port: AdmPort? = null
    @Volatile private var fallbackDisable: () -> Unit = {}
    @Volatile var record: PlaybackRecord? = null
        private set

    /** Set once by WebRtcPeer (see wireMicToGuard): runs after the guard trips. */
    @Volatile var onGuardTrip: () -> Unit = {}

    val guard = PlaybackAudioGuard(
        disable = { port?.disableRecording() ?: fallbackDisable() },
        isOurRecordActive = { record?.let { r -> port?.isInstalled(r) } == true },
        log = log,
        disableBlocking = { port?.disableRecordingNow() ?: fallbackDisable() },
        onTrip = { onGuardTrip() },
    )

    val stateCallback = object : JavaAudioDeviceModule.AudioRecordStateCallback {
        override fun onWebRtcAudioRecordStart() = guard.onRecordStart()
        override fun onWebRtcAudioRecordStop() = guard.onRecordStop()
    }

    val errorCallback = object : JavaAudioDeviceModule.AudioRecordErrorCallback {
        override fun onWebRtcAudioRecordInitError(msg: String?) = guard.onInitError(msg)
        override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode?, msg: String?) =
            guard.onStartError("$code $msg")
        override fun onWebRtcAudioRecordError(msg: String?) = guard.onError(msg)
    }

    fun meterEnabled(): Boolean = !guard.isTripped

    /** [port] is null when reflection setup failed; [fallbackDisable] then disables the ADM without it. */
    fun bind(port: AdmPort?, fallbackDisable: () -> Unit) {
        this.port = port
        this.fallbackDisable = fallbackDisable
    }

    @Volatile private var audioWanted = true

    /**
     * Call once after [bind]. When audio is off the ADM still exists (always with the
     * REMOTE_SUBMIX source) but its recording is disabled up front and no swap or audio track happens.
     */
    fun prepare(audioActive: Boolean) {
        audioWanted = audioActive
        if (!audioActive) runCatching { port?.disableRecording() ?: fallbackDisable() }
    }

    /** True when the swap succeeded and an audio track may be added. Never throws. */
    fun attach(projectionAvailable: Boolean): Boolean {
        val p = port
        if (p == null) {
            log("webrtc audio: ADM reflection unavailable — video only")
            runCatching { fallbackDisable() }
            return false
        }
        if (!audioWanted) return false
        val result = swapPlayback(p, projectionAvailable, log, onInstalled = { record = it })
        record = result // null on failure
        return result != null
    }

    /**
     * Disable ADM recording (the capture thread then releases the record) and wait,
     * bounded, until the record stops recording, so the projection is not torn down
     * under a live capture.
     */
    fun close(maxWaitMs: Long) {
        guard.shutdown()
        val r = record ?: return
        val p = port ?: return
        if (!awaitQuiet(maxWaitMs, POLL_MS, { runCatching { p.isQuiet(r) }.getOrDefault(true) }, nowMs, sleepMs)) {
            log("webrtc audio: capture record still active after ${maxWaitMs} ms; continuing teardown")
        }
    }

    /** Idempotent; call after the factory is disposed. */
    fun releaseRecord() {
        val r = record ?: return
        record = null
        runCatching { r.release() }
    }

    private companion object { const val POLL_MS = 10L }
}
