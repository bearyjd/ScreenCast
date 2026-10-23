package io.github.ddagunts.screencast.webrtc

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import io.github.ddagunts.screencast.util.LogRepository

// Opt-in "mute phone while casting". Only STREAM_MUSIC is touched (never ringer/DND), and only via
// the stream's mute flag: Android keeps a separate volume per output device, so we never store or
// restore a volume number. We unmute only if the stream is still muted, so a user who unmuted (or
// never muted via us) is left alone. A marker records that WE muted, so a dead process is recovered.

interface MediaVolumeControl {
    fun isVolumeFixed(): Boolean
    fun isMuted(): Boolean
    fun mute()
    fun unmute()
}

data class MuteMarker(val sessionId: String, val timestampMs: Long)

interface MuteMarkerStore {
    fun read(): MuteMarker?
    fun write(marker: MuteMarker)
    fun clear()
}

// All public methods run under one lock so mute/restore/recover can never interleave.
class MediaMuteGuard(
    private val volume: MediaVolumeControl,
    private val store: MuteMarkerStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { LogRepository.log(LogRepository.Level.W, "MediaMuteGuard", it) },
) {
    private val lock = Any()

    // stillWanted is evaluated under the lock so a stop that raced ahead cannot be followed by a mute.
    fun mute(sessionId: String, stillWanted: () -> Boolean = { true }): Unit = synchronized(lock) {
        if (!stillWanted()) return
        val fixed = attempt("read fixed-volume") { volume.isVolumeFixed() }.getOrNull() ?: return
        if (fixed) { log("fixed-volume device: mute while casting skipped"); return }
        val muted = attempt("read mute state") { volume.isMuted() }.getOrNull() ?: return
        if (muted) return // the user's own mute stays; we neither mark nor undo it
        if (attempt("write mute marker") { store.write(MuteMarker(sessionId, clock())) }.isFailure) return
        val applied = attempt("mute") { volume.mute() }.isSuccess &&
            attempt("verify mute") { volume.isMuted() }.getOrNull() == true
        if (!applied) attempt("clear mute marker") { store.clear() }
    }

    fun restore(): Unit = synchronized(lock) { restoreLocked() }

    // castActive is re-checked under the lock, immediately before any unmute.
    fun recover(castActive: () -> Boolean): Unit = synchronized(lock) {
        if (!castActive()) restoreLocked()
    }

    private fun restoreLocked() {
        attempt("read mute marker") { store.read() }.getOrNull() ?: return
        val muted = attempt("read mute state") { volume.isMuted() }.getOrNull() ?: return
        if (muted && attempt("unmute") { volume.unmute() }.isFailure) return
        attempt("clear mute marker") { store.clear() }
    }

    // Failures are logged, never rethrown: muting must not be able to break a cast.
    private inline fun <T> attempt(what: String, block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: RuntimeException) {
            log("mute guard: $what failed: ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(e)
        }
}

// One guard per process, shared by the service and the ViewModel so their lock is the same lock.
object MediaMuteGuards {
    @Volatile private var instance: MediaMuteGuard? = null

    fun get(context: Context): MediaMuteGuard = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            MediaMuteGuard(AudioManagerVolume(app.getSystemService(AudioManager::class.java)), PrefsMuteMarkerStore(app))
        }.also { instance = it }
    }
}

class AudioManagerVolume(private val audio: AudioManager?) : MediaVolumeControl {
    private fun am() = checkNotNull(audio) { "no AudioManager" }
    override fun isVolumeFixed() = am().isVolumeFixed
    override fun isMuted() = am().isStreamMute(AudioManager.STREAM_MUSIC)
    override fun mute() = am().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
    // FLAG_SHOW_UI so the user sees that the phone is audible again.
    override fun unmute() =
        am().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
}

class PrefsMuteMarkerStore(private val prefs: SharedPreferences) : MuteMarkerStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("webrtc_mute_marker", Context.MODE_PRIVATE),
    )

    override fun read(): MuteMarker? {
        if (!prefs.contains(KEY_SESSION)) return null
        return MuteMarker(
            sessionId = prefs.getString(KEY_SESSION, "") ?: "",
            timestampMs = prefs.getLong(KEY_TS, 0L),
        )
    }

    // commit(): the marker must be on disk before the stream is muted, and gone before we forget it.
    override fun write(marker: MuteMarker) {
        val ok = prefs.edit().putString(KEY_SESSION, marker.sessionId).putLong(KEY_TS, marker.timestampMs).commit()
        check(ok) { "marker commit failed" }
    }

    override fun clear() {
        check(prefs.edit().clear().commit()) { "marker clear failed" }
    }

    private companion object {
        const val KEY_SESSION = "session_id"
        const val KEY_TS = "timestamp_ms"
    }
}
