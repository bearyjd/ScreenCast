// Fork modification by bearyjd: retain direct-media control and classify capture receiver ownership without projection.
package io.github.ddagunts.screencast.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ddagunts.screencast.CastForegroundService
import io.github.ddagunts.screencast.WebRtcForegroundService
import io.github.ddagunts.screencast.cast.*
import io.github.ddagunts.screencast.LocalMediaForegroundService
import io.github.ddagunts.screencast.media.LocalMediaHandle
import io.github.ddagunts.screencast.media.LocalMediaHost
import io.github.ddagunts.screencast.media.LocalMediaPolicy
import io.github.ddagunts.screencast.media.ServingClaim
import io.github.ddagunts.screencast.media.SharedMedia
import io.github.ddagunts.screencast.media.SharedMediaResult
import io.github.ddagunts.screencast.util.ManualHostsStore
import io.github.ddagunts.screencast.util.NetworkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaShareViewModel(app: Application) : AndroidViewModel(app) {
    private val discovery = CastDiscovery.shared(app)
    private val manual = ManualHostsStore(app, "cast")
    private var initialized = false
    private var lease: CastDiscovery.Lease? = null
    val discoveryStatus: StateFlow<DiscoveryStatus> = discovery.status
    fun rescanDiscovery() = discovery.rescan()
    val discovered = discovery.devices.map { devices ->
        val hosts = devices.map { it.host }.toSet()
        (devices + manual.list().filterNot { it in hosts }.map { CastDevice(it, it, CHROMECAST_DEFAULT_PORT) }).sortedBy { it.name }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val controller = MediaHandoffController(viewModelScope, { device ->
        val cast = CastSession(device)
        object : HandoffSession {
            private val claim = ServingClaim(LocalMediaHost.shared)
            override val state = cast.state
            override val playerState = cast.playerState
            override val mediaRejected = cast.mediaRejected
            override val receiverApps = cast.runningApps
            override suspend fun start(media: SharedMedia) = withContext(Dispatchers.IO) {
                val url = if (media.localName != null) startLocal(device, media.contentType, claim) else media.url
                cast.startCast(DEFAULT_MEDIA_RECEIVER, url, media.contentType, streamType = "BUFFERED")
            }
            override suspend fun pause() = withContext(Dispatchers.IO) { cast.pause() }
            override suspend fun play() = withContext(Dispatchers.IO) { cast.play() }
            override suspend fun stop() = withContext(Dispatchers.IO) { cast.stopPlayback() }
            override fun close() = cast.close()
            // Stops only the session this cast started (never another share's server); safe before, during or after start.
            override fun release() = claim.release()
        }
    }, { host -> isCaptureHostBusy(host, CastForegroundService.devicesFlow.value, WebRtcForegroundService.sessionFlow.value) })
    // Holds the one fd opened under the share's URI grant; the server gets dup'd copies, so leaving this screen
    // does not end serving, and the grant is never needed again after accept time.
    @Volatile private var pending: LocalMediaHandle? = null
    private var openJob: Job? = null

    private suspend fun startLocal(device: CastDevice, contentType: String, claim: ServingClaim): String {
        val handle = pending ?: throw HandoffException("The shared file is no longer available. Share it again.")
        val receiver = LocalMediaPolicy.parseReceiverAddress(device.host)
            ?: throw HandoffException("Casting a file from this phone needs the receiver's IP address. Add it by IP.")
        val link = NetworkUtils.getWifiLink(getApplication())?.takeIf { LocalMediaPolicy.isLanBindAddress(it.address) }
            ?: throw HandoffException("Connect to a private Wi-Fi network to cast a file from this phone.")
        if (!LocalMediaPolicy.isAcceptableReceiver(receiver, link.address)) throw HandoffException("Not a valid receiver address.")
        if (!LocalMediaPolicy.inSubnet(receiver, link.address, link.prefixLength)) {
            throw HandoffException("This receiver isn't on the same Wi-Fi network as this phone.")
        }
        val source = handle.openSource() ?: throw HandoffException(LocalMediaOpener.NOT_ON_PHONE)
        // begin + claim happen as one uncancellable step so a Stop/cancel can never leave an unowned server behind.
        val started = withContext(NonCancellable) {
            val st = try { LocalMediaHost.shared.begin(source, contentType, receiver, link.address) }
                catch (_: Exception) { throw HandoffException("Could not start sharing the file from this phone.") }
            if (claim.attach(st.id)) st else { LocalMediaHost.shared.stop(st.id); null }
        } ?: throw CancellationException("share ended while starting")
        try {
            currentCoroutineContext().ensureActive()
            LocalMediaForegroundService.start(getApplication())
        } catch (e: CancellationException) { claim.release(); throw e }
        catch (e: RuntimeException) {
            claim.release()
            throw HandoffException("Android did not allow background sharing. Keep the app open and try again.")
        }
        return started.url
    }

    /** True while a local file is being served, even with no controls attached (notification may be hidden). */
    val sharingActive: StateFlow<Boolean> = LocalMediaHost.shared.active
    fun stopSharing() = LocalMediaHost.shared.stop()
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private fun replacePending(next: LocalMediaHandle?) {
        val old = pending; pending = next; old?.close()
    }

    fun receiveInitial(intent: Intent) { if (!initialized) { initialized = true; receive(intent) } }
    fun receive(intent: Intent) {
        openJob?.cancel(); openJob = null
        val local = MediaShareIntent.local(intent)
        val request = local.request
        if (request == null) {
            replacePending(null)
            controller.preview(if (local.error != null) SharedMediaResult(error = local.error) else MediaShareIntent.parse(intent))
            return
        }
        controller.preview(SharedMediaResult())
        openJob = viewModelScope.launch {
            // The open itself is short; shield it so a fd it produced can never be orphaned by cancellation.
            val opened = withContext(ioDispatcher + NonCancellable) {
                LocalMediaOpener.open(ContentResolverBackend(getApplication<Application>().contentResolver), request)
            }
            if (!isActive) { opened.handle?.close(); return@launch }
            replacePending(opened.handle)
            val type = opened.contentType
            controller.preview(
                if (opened.handle != null && type != null) SharedMediaResult(SharedMedia("", type, opened.displayName))
                else SharedMediaResult(error = opened.error)
            )
        }
    }
    fun startDiscovery() { if (lease == null) lease = discovery.acquire() }
    fun stopDiscovery() { lease?.release(); lease = null }
    override fun onCleared() { stopDiscovery(); controller.disconnect(); replacePending(null); super.onCleared() }
}

internal fun isCaptureHostBusy(
    host: String,
    hls: Map<String, CastForegroundService.DeviceCast>,
    webRtc: WebRtcForegroundService.SessionSnapshot?,
): Boolean = hls[host]?.castState in setOf("CONNECTING", "CASTING") ||
    (webRtc?.device?.host == host && webRtc.status in setOf("CONNECTING", "LAUNCHING", "SIGNALING", "CASTING"))
