// Fork modification by bearyjd: explicit direct-media handoff controller boundary.
package io.github.ddagunts.screencast.ui

import io.github.ddagunts.screencast.cast.*
import io.github.ddagunts.screencast.media.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val NEVER = MutableStateFlow(false)
private val NO_APPS = MutableStateFlow<Set<String>?>(null)

interface HandoffSession {
    val state: StateFlow<CastState>
    val playerState: StateFlow<String>
    /** True once the receiver refused our media (as opposed to the control link merely dropping). */
    val mediaRejected: StateFlow<Boolean> get() = NEVER
    /** appIds running on the receiver per its latest status; null if unknown. */
    val receiverApps: StateFlow<Set<String>?> get() = NO_APPS
    suspend fun start(media: SharedMedia)
    suspend fun pause()
    suspend fun play()
    suspend fun stop()
    fun close()
    /** Ends anything this session set up beyond the receiver link (e.g. the local file server). */
    fun release() {}
}
/** Failure with a message that is safe and useful to show verbatim. */
class HandoffException(message: String) : Exception(message)
data class HandoffState(val media: SharedMedia? = null, val selected: CastDevice? = null,
    val active: CastDevice? = null, val connecting: Boolean = false, val playing: Boolean = false,
    val error: String? = null, val canCast: Boolean = false, val activeLocal: Boolean = false)
class MediaHandoffController(private val scope: CoroutineScope, private val factory: (CastDevice) -> HandoffSession,
    private val busy: (String) -> Boolean = { false }) {
    val state = MutableStateFlow(HandoffState())
    private var session: HandoffSession? = null
    private var startJob: Job? = null
    private var statusJob: Job? = null
    private var playerJob: Job? = null
    private var appsJob: Job? = null
    fun preview(result: SharedMediaResult) {
        state.value = state.value.copy(media = result.media, selected = null, error = result.error, canCast = false)
    }
    fun select(device: CastDevice) {
        state.value = state.value.copy(selected = device, canCast = state.value.media != null)
    }
    fun cast() {
        val snapshot = state.value
        val media = snapshot.media ?: return
        val device = snapshot.selected ?: return
        if (!snapshot.canCast) return
        if (busy(device.host)) {
            state.value = snapshot.copy(error = "This receiver already has a screen cast. Stop it before handing off media.")
            return
        }
        end()
        state.value = state.value.copy(active = device, connecting = true, canCast = false, error = null, activeLocal = media.localName != null)
        val owned = factory(device)
        session = owned
        statusJob = scope.launch {
            owned.state.collect { status ->
                if (session !== owned) return@collect
                when (status) {
                    is CastState.Error -> {
                        state.value = state.value.copy(error = status.message)
                        // A dropped control link keeps serving (playback may continue); only a refusal ends it.
                        if (owned.mediaRejected.value) end() else disconnect()
                    }
                    is CastState.Casting -> state.value = state.value.copy(connecting = false)
                    else -> Unit
                }
            }
        }
        appsJob = scope.launch {
            var seen = false
            owned.receiverApps.collect { apps ->
                if (session !== owned || apps == null) return@collect
                // Backdrop etc. may be showing before our launch lands; only leaving AFTER we were seen ends serving.
                if (DEFAULT_MEDIA_RECEIVER in apps) seen = true else if (seen) { seen = false; owned.release() }
            }
        }
        playerJob = scope.launch { owned.playerState.collect { player ->
            if (session === owned) state.value = state.value.copy(playing = player == "PLAYING")
        } }
        startJob = scope.launch {
            try { owned.start(media) }
            catch (e: CancellationException) { throw e }
            catch (e: HandoffException) {
                if (session === owned) { state.value = state.value.copy(error = e.message); end() }
            }
            catch (_: Exception) {
                if (session === owned) {
                    state.value = state.value.copy(error = "Could not connect to receiver.")
                    end()
                }
            }
        }
    }
    fun disconnect() {
        statusJob?.cancel(); statusJob = null
        playerJob?.cancel(); playerJob = null
        appsJob?.cancel(); appsJob = null
        startJob?.cancel(); startJob = null
        val owned = session; session = null; owned?.close()
        state.value = state.value.copy(active = null, connecting = false, playing = false, activeLocal = false)
    }
    /** Disconnect controls AND stop anything served from this phone; idempotent. */
    fun end() {
        val owned = session
        disconnect()
        owned?.release()
    }
    fun stop() {
        val owned = session ?: return
        scope.launch {
            try { owned.stop() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (session === owned) state.value = state.value.copy(error = "Stop was not confirmed by receiver.") }
            finally { if (session === owned) end() }
        }
    }
    fun pause() = command { pause() }
    fun play() = command { play() }
    private fun command(action: suspend HandoffSession.() -> Unit) {
        val owned = session ?: return
        scope.launch {
            try { owned.action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (session === owned) { state.value = state.value.copy(error = "Receiver control failed."); end() }
            }
        }
    }
}
