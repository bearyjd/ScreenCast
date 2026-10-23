package io.github.ddagunts.screencast.webrtc

import android.content.Context
import android.media.AudioManager
import androidx.core.content.edit
import io.github.ddagunts.screencast.util.LogRepository

// Opt-in "mute phone while casting". Only STREAM_MUSIC volume is touched (never ringer/DND).
// Crash safety: a marker with the original volume is persisted BEFORE muting and cleared after
// restoring, so a dead service can be recovered on next launch. Restore never overwrites a volume
// the user changed during the cast.

interface MediaVolumeControl {
    fun getMediaVolume(): Int
    fun setMediaVolume(level: Int)
}

data class MuteMarker(val originalVolume: Int, val sessionId: String, val timestampMs: Long)

interface MuteMarkerStore {
    fun read(): MuteMarker?
    fun write(marker: MuteMarker)
    fun clear()
}

class MediaMuteGuard(
    private val volume: MediaVolumeControl,
    private val store: MuteMarkerStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { LogRepository.log(LogRepository.Level.W, "MediaMuteGuard", it) },
) {
    fun mute(sessionId: String) {
        restore() // a stale marker must not be mistaken for the user's real volume
        val original = attempt("read volume") { volume.getMediaVolume() }.getOrNull() ?: return
        if (original <= 0) return
        val marker = MuteMarker(original, sessionId, clock())
        if (attempt("write mute marker") { store.write(marker) }.isFailure) return
        if (attempt("mute") { volume.setMediaVolume(0) }.isFailure) {
            attempt("clear mute marker") { store.clear() }
        }
    }

    fun restore() {
        val read = attempt("read mute marker") { store.read() }
        val marker = read.getOrNull() ?: return
        if (marker.originalVolume < 0) {
            log("discarding invalid mute marker")
            attempt("clear mute marker") { store.clear() }
            return
        }
        val current = attempt("read volume") { volume.getMediaVolume() }.getOrNull() ?: return
        if (current == 0 && attempt("restore volume") { volume.setMediaVolume(marker.originalVolume) }.isFailure) return
        attempt("clear mute marker") { store.clear() }
    }

    fun recoverIfIdle(castActive: Boolean) {
        if (!castActive) restore()
    }

    // Failures are logged, never rethrown: muting must not be able to break a cast.
    private inline fun <T> attempt(what: String, block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: RuntimeException) {
            log("mute guard: $what failed: ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(e)
        }

    companion object {
        fun forContext(context: Context): MediaMuteGuard {
            val app = context.applicationContext
            return MediaMuteGuard(AudioManagerVolume(app.getSystemService(AudioManager::class.java)), PrefsMuteMarkerStore(app))
        }
    }
}

class AudioManagerVolume(private val audio: AudioManager?) : MediaVolumeControl {
    override fun getMediaVolume(): Int =
        checkNotNull(audio) { "no AudioManager" }.getStreamVolume(AudioManager.STREAM_MUSIC)

    override fun setMediaVolume(level: Int) =
        checkNotNull(audio) { "no AudioManager" }.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
}

class PrefsMuteMarkerStore(context: Context) : MuteMarkerStore {
    private val prefs = context.applicationContext.getSharedPreferences("webrtc_mute_marker", Context.MODE_PRIVATE)

    override fun read(): MuteMarker? {
        if (!prefs.contains(KEY_VOLUME)) return null
        return MuteMarker(
            originalVolume = prefs.getInt(KEY_VOLUME, -1),
            sessionId = prefs.getString(KEY_SESSION, "") ?: "",
            timestampMs = prefs.getLong(KEY_TS, 0L),
        )
    }

    // commit(): the marker must be on disk before the volume changes.
    override fun write(marker: MuteMarker) {
        val ok = prefs.edit()
            .putInt(KEY_VOLUME, marker.originalVolume)
            .putString(KEY_SESSION, marker.sessionId)
            .putLong(KEY_TS, marker.timestampMs)
            .commit()
        check(ok) { "marker commit failed" }
    }

    override fun clear() = prefs.edit { clear() }

    private companion object {
        const val KEY_VOLUME = "original_volume"
        const val KEY_SESSION = "session_id"
        const val KEY_TS = "timestamp_ms"
    }
}
