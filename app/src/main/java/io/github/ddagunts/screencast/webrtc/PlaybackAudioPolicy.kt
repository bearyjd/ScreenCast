package io.github.ddagunts.screencast.webrtc

// Pure policy for WebRTC playback-capture audio. No Android or libwebrtc types.

// MediaRecorder.AudioSource.REMOTE_SUBMIX (hidden API constant). The ADM is
// built with this source so that any recorder libwebrtc creates by itself can
// never be a microphone recorder: REMOTE_SUBMIX needs CAPTURE_AUDIO_OUTPUT,
// which ordinary apps lack, so such a construction fails (init error -> guard).
internal const val AUDIO_SOURCE_REMOTE_SUBMIX = 8

// AudioRecord.STATE_INITIALIZED
internal const val AUDIO_RECORD_STATE_INITIALIZED = 1

// AudioPlaybackCapture exists from Android 10.
internal const val MIN_PLAYBACK_CAPTURE_SDK = 29

enum class AudioMode {
    /** User turned audio off. */
    OFF,
    /** Audio requested, but the OS cannot do playback capture (< Android 10). */
    UNSUPPORTED_OS,
    /** Audio requested, but RECORD_AUDIO (needed by AudioRecord even for playback capture) is not granted. */
    NEEDS_PERMISSION,
    /** Audio will be attempted; it can still fall back to video-only at runtime. */
    ACTIVE,
}

fun resolveAudioMode(requested: Boolean, recordAudioGranted: Boolean, sdkInt: Int): AudioMode = when {
    !requested -> AudioMode.OFF
    sdkInt < MIN_PLAYBACK_CAPTURE_SDK -> AudioMode.UNSUPPORTED_OS
    !recordAudioGranted -> AudioMode.NEEDS_PERMISSION
    else -> AudioMode.ACTIVE
}

const val AUDIO_PERMISSION_NOTE =
    "Audio will not be sent until you allow the Microphone permission. Playback capture needs it; the microphone is not used."
const val AUDIO_UNSUPPORTED_NOTE = "Audio needs Android 10 or newer; this cast will be video-only."

fun audioNote(mode: AudioMode): String? = when (mode) {
    AudioMode.NEEDS_PERMISSION -> AUDIO_PERMISSION_NOTE
    AudioMode.UNSUPPORTED_OS -> AUDIO_UNSUPPORTED_NOTE
    else -> null
}

enum class PermissionAction { NONE, REQUEST, OPEN_SETTINGS }

/**
 * [askedBefore]: we already showed the system prompt at least once and it was denied.
 * Without it, "no rationale" is ambiguous (first ask vs. "don't ask again").
 */
fun permissionAction(granted: Boolean, askedBefore: Boolean, shouldShowRationale: Boolean): PermissionAction = when {
    granted -> PermissionAction.NONE
    askedBefore && !shouldShowRationale -> PermissionAction.OPEN_SETTINGS
    else -> PermissionAction.REQUEST
}

/** AudioRecord buffer size: twice the platform minimum, never below one 10 ms frame. */
internal fun playbackBufferBytes(minBufferBytes: Int, sampleRate: Int, channels: Int): Int =
    maxOf(2 * maxOf(minBufferBytes, 0), sampleRate / 100 * channels * 2)

/** Polls [isQuiet] until true or [maxWaitMs] elapsed. Returns whether it went quiet. */
internal fun awaitQuiet(
    maxWaitMs: Long, pollMs: Long, isQuiet: () -> Boolean, nowMs: () -> Long, sleepMs: (Long) -> Unit,
): Boolean {
    val start = nowMs()
    while (true) {
        if (isQuiet()) return true
        if (nowMs() - start >= maxWaitMs) return false
        sleepMs(pollMs)
    }
}

/** Runs [action] at most once, thread-safely. */
internal class ReleaseOnce(private val action: () -> Unit) {
    private val done = java.util.concurrent.atomic.AtomicBoolean(false)
    fun run() { if (done.compareAndSet(false, true)) action() }
}
