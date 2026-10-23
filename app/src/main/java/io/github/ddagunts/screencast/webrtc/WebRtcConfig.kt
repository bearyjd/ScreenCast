// Fork modification by bearyjd: disable unreviewed WebRTC audio, including legacy preferences.
package io.github.ddagunts.screencast.webrtc

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit

// WebRTC-mode cast protocol constants. The signaling namespace is ours; it
// just has to match what the receiver HTML subscribes to via
// cast.framework.CastReceiverContext.addCustomMessageListener(). Keep the value
// in sync with receiver/receiver.js.
const val WEBRTC_NAMESPACE = "urn:x-cast:io.github.ddagunts.screencast.webrtc"

// Audio playback-capture parameters. 48 kHz stereo 16-bit is libwebrtc's
// native pipeline format, so samples flow through without resampling. Not
// user-facing — changing them requires a matching change in the ADM
// configuration; the knobs users care about (res, fps, bitrate, audio on/off)
// are in WebRtcSessionConfig.
const val WEBRTC_AUDIO_SAMPLE_RATE = 48_000
const val WEBRTC_AUDIO_CHANNELS = 2

// msid stream ids and track ids. libwebrtc only lip-syncs (shared sync group)
// audio and video that share a stream id. Sharing is DEFAULT-OFF until the
// attended cast-sim A/B shows the audio_vs_video jitter-buffer delta shrinks and
// audio stays smooth (see tools/cast-sim/README.md, "A/B: shared stream id").
// The receiver merges tracks into one MediaStream itself and ignores
// evt.streams, so this id is cosmetic to it.
const val WEBRTC_SHARED_STREAM_ID_DEFAULT = false
const val WEBRTC_SHARED_STREAM_ID = "screen"
const val WEBRTC_VIDEO_STREAM_ID_LEGACY = "screen-video"
const val WEBRTC_AUDIO_STREAM_ID_LEGACY = "screen-audio"
const val WEBRTC_VIDEO_TRACK_ID = "screen0"
const val WEBRTC_AUDIO_TRACK_ID = "audio0"

enum class WebRtcTrackKind { VIDEO, AUDIO }

fun webRtcStreamIds(kind: WebRtcTrackKind, shared: Boolean = WEBRTC_SHARED_STREAM_ID_DEFAULT): List<String> =
    if (shared) listOf(WEBRTC_SHARED_STREAM_ID)
    else listOf(if (kind == WebRtcTrackKind.VIDEO) WEBRTC_VIDEO_STREAM_ID_LEGACY else WEBRTC_AUDIO_STREAM_ID_LEGACY)

// User-selectable capture presets. Width/height/fps are applied to the
// ScreenCapturerAndroid.startCapture call. Each preset is a sensible combo
// that most Chromecasts decode fine; exotic resolutions (1440p/4K) are
// omitted because Gen1–3 dongles can't decode them and the debug value is
// mostly in A/B-testing sane profiles, not torture-testing the receiver.
enum class VideoPreset(val label: String, val width: Int, val height: Int, val fps: Int) {
    HD_30("720p30", 1280, 720, 30),
    FHD_30("1080p30", 1920, 1080, 30),
    FHD_60("1080p60", 1920, 1080, 60);

    companion object {
        val DEFAULT = FHD_60
        fun fromName(name: String?): VideoPreset =
            values().firstOrNull { it.name == name } ?: DEFAULT
    }
}

// Requested video codec. AUTO leaves libwebrtc's default offer order untouched;
// H264 is the default because the library default (software VP8) is far slower.
enum class VideoCodecPreference(val label: String, val mimeType: String?) {
    AUTO("Auto", null),
    H264("H.264", "video/H264"),
    VP8("VP8", "video/VP8"),
    VP9("VP9", "video/VP9"),
    AV1("AV1", "video/AV1");

    companion object {
        fun fromName(name: String?): VideoCodecPreference =
            entries.firstOrNull { it.name == name } ?: H264
    }
}

// Per-cast snapshot of the user-tunable knobs. Read once at startCast time and
// held for the session's lifetime — changing store values mid-cast has no
// effect on the running peer (the UI disables controls while casting).
data class WebRtcSessionConfig(
    val videoPreset: VideoPreset,
    val maxBitrateBps: Int,
    // What the user asked for (pref). Playback-capture audio only; the
    // microphone is never used.
    val audioRequested: Boolean,
    val videoCodecPreference: VideoCodecPreference = VideoCodecPreference.H264,
    // AudioRecord needs RECORD_AUDIO even for playback capture.
    val recordAudioGranted: Boolean = false,
    val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    // Audio will be attempted. It can still fall back to video-only at runtime.
    val audioActive: Boolean get() = resolveAudioMode(audioRequested, recordAudioGranted, sdkInt) == AudioMode.ACTIVE
}

// App ID defaults to the project's published receiver. Users can override with
// their own registered App ID (cast.google.com) if they host their own receiver.
// An empty value blocks startCast. Persisted in SharedPreferences.
const val DEFAULT_WEBRTC_APP_ID = "9098830C"

// Bitrate presets in Mbps — what the UI offers. Kept together so the settings
// screen and the store's default-resolution logic stay consistent.
val WEBRTC_BITRATE_MBPS_OPTIONS = listOf(1, 2, 4, 6, 8, 12)
const val DEFAULT_WEBRTC_BITRATE_MBPS = 12

class WebRtcConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var appId: String
        get() = prefs.getString(KEY_APP_ID, DEFAULT_WEBRTC_APP_ID)!!
        set(value) { prefs.edit().putString(KEY_APP_ID, value.trim()).apply() }

    var videoPreset: VideoPreset
        get() = VideoPreset.fromName(prefs.getString(KEY_VIDEO_PRESET, null))
        set(value) { prefs.edit().putString(KEY_VIDEO_PRESET, value.name).apply() }

    var maxBitrateMbps: Int
        get() = prefs.getInt(KEY_MAX_BITRATE_MBPS, DEFAULT_WEBRTC_BITRATE_MBPS)
            .takeIf { it in WEBRTC_BITRATE_MBPS_OPTIONS }
            ?: DEFAULT_WEBRTC_BITRATE_MBPS
        set(value) {
            val coerced = value.takeIf { it in WEBRTC_BITRATE_MBPS_OPTIONS }
                ?: DEFAULT_WEBRTC_BITRATE_MBPS
            prefs.edit().putInt(KEY_MAX_BITRATE_MBPS, coerced).apply()
        }

    var videoCodecPreference: VideoCodecPreference
        get() = VideoCodecPreference.fromName(prefs.getString(KEY_VIDEO_CODEC_PREFERENCE, null))
        set(value) { prefs.edit().putString(KEY_VIDEO_CODEC_PREFERENCE, value.name).apply() }

    var audioEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_PLAYBACK_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_AUDIO_PLAYBACK_ENABLED, value).apply() }

    // True once the system RECORD_AUDIO prompt was answered with a denial; lets the UI tell
    // "never asked" from "don't ask again".
    var micPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_MIC_PERMISSION_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_MIC_PERMISSION_ASKED, value) }

    // Snapshot for handoff into a cast session. Each call reads current prefs
    // so the next cast picks up any changes made after the previous one ended.
    fun snapshot(): WebRtcSessionConfig = WebRtcSessionConfig(
        videoPreset = videoPreset,
        maxBitrateBps = maxBitrateMbps * 1_000_000,
        audioRequested = audioEnabled,
        videoCodecPreference = videoCodecPreference,
        recordAudioGranted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED,
    )

    companion object {
        private const val PREFS_NAME = "webrtc_config"
        private const val KEY_APP_ID = "app_id"
        private const val KEY_VIDEO_PRESET = "video_preset"
        private const val KEY_MAX_BITRATE_MBPS = "max_bitrate_mbps"
        private const val KEY_VIDEO_CODEC_PREFERENCE = "video_codec_preference"
        // Legacy "audio_enabled" was always written as false by an older build; ignored on purpose.
        private const val KEY_AUDIO_PLAYBACK_ENABLED = "audio_playback_enabled"
        private const val KEY_MIC_PERMISSION_ASKED = "mic_permission_asked"
    }
}
