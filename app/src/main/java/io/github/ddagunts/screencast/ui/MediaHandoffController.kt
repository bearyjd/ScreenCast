// Fork modification by bearyjd: explicit direct-media handoff controller boundary.
package io.github.ddagunts.screencast.ui

import io.github.ddagunts.screencast.cast.*
import io.github.ddagunts.screencast.media.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface HandoffSession {
    val state: StateFlow<CastState>
    val playerState: StateFlow<String>
    suspend fun start(media: SharedMedia)
    suspend fun pause()
    suspend fun play()
    suspend fun stop()
    fun close()
}
data class HandoffState(val media: SharedMedia? = null, val selected: CastDevice? = null,
    val active: CastDevice? = null, val connecting: Boolean = false, val playing: Boolean = false,
    val error: String? = null, val canCast: Boolean = false)
class MediaHandoffController(private val scope: CoroutineScope, private val factory: (CastDevice) -> HandoffSession,
    private val busy: (String) -> Boolean = { false }) {
    val state = MutableStateFlow(HandoffState())
    private var session: HandoffSession? = null
    private var startJob: Job? = null
    private var statusJob: Job? = null
    private var playerJob: Job? = null
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
        disconnect()
        state.value = state.value.copy(active = device, connecting = true, canCast = false, error = null)
        val owned = factory(device)
        session = owned
        statusJob = scope.launch {
            owned.state.collect { status ->
                if (session !== owned) return@collect
                when (status) {
                    is CastState.Error -> {
                        state.value = state.value.copy(error = status.message)
                        disconnect()
                    }
                    is CastState.Casting -> state.value = state.value.copy(connecting = false)
                    else -> Unit
                }
            }
        }
        playerJob = scope.launch { owned.playerState.collect { player ->
            if (session === owned) state.value = state.value.copy(playing = player == "PLAYING")
        } }
        startJob = scope.launch {
            try { owned.start(media) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (session === owned) {
                    state.value = state.value.copy(error = "Could not connect to receiver.")
                    disconnect()
                }
            }
        }
    }
    fun disconnect() {
        statusJob?.cancel(); statusJob = null
        playerJob?.cancel(); playerJob = null
        startJob?.cancel(); startJob = null
        val owned = session; session = null; owned?.close()
        state.value = state.value.copy(active = null, connecting = false, playing = false)
    }
    fun stop() {
        val owned = session ?: return
        scope.launch {
            try { owned.stop() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (session === owned) state.value = state.value.copy(error = "Stop was not confirmed by receiver.") }
            finally { if (session === owned) disconnect() }
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
                if (session === owned) { state.value = state.value.copy(error = "Receiver control failed."); disconnect() }
            }
        }
    }
}
