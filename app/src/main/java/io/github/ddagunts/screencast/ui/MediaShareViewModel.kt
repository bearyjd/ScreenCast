// Fork modification by bearyjd: retain direct-media control across rotation without a projection service.
package io.github.ddagunts.screencast.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ddagunts.screencast.CastForegroundService
import io.github.ddagunts.screencast.WebRtcForegroundService
import io.github.ddagunts.screencast.cast.*
import io.github.ddagunts.screencast.media.SharedMedia
import io.github.ddagunts.screencast.util.ManualHostsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

class MediaShareViewModel(app: Application) : AndroidViewModel(app) {
    private val discovery = CastDiscovery(app)
    private val manual = ManualHostsStore(app, "cast")
    private var initialized = false
    private var scanning = false
    val discovered = discovery.flow.map { devices ->
        val hosts = devices.map { it.host }.toSet()
        (devices + manual.list().filterNot { it in hosts }.map { CastDevice(it, it, CHROMECAST_DEFAULT_PORT) }).sortedBy { it.name }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val controller = MediaHandoffController(viewModelScope, { device ->
        val cast = CastSession(device)
        object : HandoffSession {
            override val state = cast.state
            override val playerState = cast.playerState
            override suspend fun start(media: SharedMedia) = withContext(Dispatchers.IO) {
                cast.startCast(DEFAULT_MEDIA_RECEIVER, media.url, media.contentType, streamType = "BUFFERED")
            }
            override suspend fun pause() = withContext(Dispatchers.IO) { cast.pause() }
            override suspend fun play() = withContext(Dispatchers.IO) { cast.play() }
            override suspend fun stop() = withContext(Dispatchers.IO) { cast.stopPlayback() }
            override fun close() = cast.close()
        }
    }, { host -> CastForegroundService.devicesFlow.value.containsKey(host) || WebRtcForegroundService.sessionFlow.value?.device?.host == host })
    fun receiveInitial(intent: Intent) { if (!initialized) { initialized = true; receive(intent) } }
    fun receive(intent: Intent) = controller.preview(MediaShareIntent.parse(intent))
    fun startDiscovery() { if (!scanning) { scanning = true; runCatching { discovery.start() } } }
    fun stopDiscovery() { if (scanning) { scanning = false; discovery.stop() } }
    override fun onCleared() { stopDiscovery(); controller.disconnect(); super.onCleared() }
}
