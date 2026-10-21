// Fork modification by bearyjd: injectable session resource boundaries.
package io.github.ddagunts.screencast.webrtc

import android.content.Context
import android.content.Intent
import io.github.ddagunts.screencast.cast.CastChannel
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.cast.CastMessage
import io.github.ddagunts.screencast.cast.CastNs
import io.github.ddagunts.screencast.cast.CastVolume
import io.github.ddagunts.screencast.cast.DEFAULT_RECEIVER_ID
import io.github.ddagunts.screencast.cast.SENDER_ID
import io.github.ddagunts.screencast.cast.closeMsg
import io.github.ddagunts.screencast.cast.connectMsg
import io.github.ddagunts.screencast.cast.launchMsg
import io.github.ddagunts.screencast.cast.pingMsg
import io.github.ddagunts.screencast.cast.pongMsg
import io.github.ddagunts.screencast.cast.setMuteMsg
import io.github.ddagunts.screencast.cast.stopAppMsg
import io.github.ddagunts.screencast.cast.setVolumeMsg
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.webrtc.IceCandidate

// WebRTC-mode session lifecycle. Mirrors the shape of CastSession (cast/) but
// the media pipeline here is WebRTC rather than HLS, so there is no mediaSessionId,
// no SEEK/PAUSE/PLAY, no volume control (for now). The receiver HTML page
// handles rendering.
//
// State machine:
//   Idle → Connecting → Launching → Signaling → Casting → Idle|Error
sealed class WebRtcState {
    data object Idle : WebRtcState()
    data class Connecting(val device: CastDevice) : WebRtcState()
    data class Launching(val device: CastDevice) : WebRtcState()
    data class Signaling(val device: CastDevice) : WebRtcState()
    data class Casting(val device: CastDevice) : WebRtcState()
    data class Error(val message: String) : WebRtcState()
}

class WebRtcCastSession internal constructor(
    private val context: Context,
    val device: CastDevice,
    private val sessionConfig: WebRtcSessionConfig,
    private val channel: SessionTransport,
    private val peerFactory: ((IceCandidate) -> Unit) -> SessionPeer,
    private val dispatcher: CoroutineDispatcher,
) {
    constructor(context: Context, device: CastDevice, sessionConfig: WebRtcSessionConfig) : this(
        context, device, sessionConfig, CastSessionTransport(device.host, device.port),
        { onIce -> WebRtcPeer(context, sessionConfig, onIce) }, Dispatchers.IO,
    )
    private val _state = MutableStateFlow<WebRtcState>(WebRtcState.Idle)
    val state: StateFlow<WebRtcState> = _state

    // Receiver-level volume: works exactly the same as in HLS mode — volume
    // lives on the device (Chromecast), not the media session, so we can send
    // SET_VOLUME without a transportId. UI hides the slider when isFixed.
    private val _volume = MutableStateFlow(CastVolume())
    val volume: StateFlow<CastVolume> = _volume

    private val closed = AtomicBoolean(false)
    private var scope: CoroutineScope? = null
    private var readerJob: Job? = null
    @Volatile private var transportId: String? = null
    @Volatile private var sessionId: String? = null
    private var peer: SessionPeer? = null
    private class PeerLease(val peer: SessionPeer) {
        var operations = 0
        var releaseRequested = false
        var disposed = false
    }
    private var peerLease: PeerLease? = null

    @Volatile private var lastHeartbeatMs: Long = 0L

    suspend fun startCast(appId: String, projectionData: Intent, onProjectionStopped: () -> Unit) {
        try {
            start(appId, projectionData, onProjectionStopped)
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Exception) {
            logE("webrtc start failed", e)
            fail(e.message ?: "start failed")
        }
    }

    private suspend fun start(appId: String, projectionData: Intent, onProjectionStopped: () -> Unit) {
        if (closed.get()) return
        if (sessionConfig.audioEnabled) {
            fail("WebRTC audio is unsupported; use video-only capture")
            return
        }
        if (appId.isBlank()) {
            fail("custom App ID is not set — open Settings and paste it")
            return
        }
        val s = CoroutineScope(dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            fail(e.message ?: "session failed")
        })
        val installedScope = synchronized(this) {
            if (closed.get()) false else {
                scope = s
                _state.value = WebRtcState.Connecting(device)
                true
            }
        }
        if (!installedScope) { s.cancel(); return }
        try {
            val reader = channel.connect { e -> fail(e?.message ?: "disconnected") }
            val installedReader = synchronized(this) {
                if (closed.get()) false else { readerJob = reader; true }
            }
            if (!installedReader) { reader.cancel(); return }
        } catch (e: Throwable) {
            logE("webrtc connect failed", e)
            fail(e.message ?: "connect failed")
            return
        }
        channel.send(connectMsg())
        lastHeartbeatMs = System.currentTimeMillis()
        s.launch { heartbeatLoop() }
        s.launch { handleIncoming() }

        if (!transition(WebRtcState.Launching(device))) return
        val (launch, reqId) = launchMsg(appId)
        channel.send(launch)
        logI("LAUNCH sent (requestId=$reqId, appId=$appId)")

        val ok = withTimeoutOrNull(10_000) {
            while (!closed.get() && (transportId == null || sessionId == null)) delay(50)
            true
        }
        if (closed.get()) return
        val tid = transportId
        if (ok == null || tid == null) {
            fail("launch timed out — is the custom receiver App ID correct?")
            return
        }
        // Virtual-connection to the receiver app so it'll route our custom
        // namespace messages. Without this, sends to transportId are dropped.
        channel.send(CastMessage(SENDER_ID, tid, CastNs.CONNECTION, """{"type":"CONNECT"}"""))

        if (!transition(WebRtcState.Signaling(device))) return

        // Peer is only built now, after transportId is known — so every ICE
        // candidate the library emits has a valid destination from the start.
        val p = peerFactory { cand ->
            scope?.launch {
                runCatching {
                    channel.send(iceMsg(tid, cand.sdp, cand.sdpMid, cand.sdpMLineIndex))
                }.onFailure { fail("send ICE failed: ${it.message}") }
            }
        }
        val lease = PeerLease(p)
        val installedPeer = synchronized(this) {
            if (closed.get()) false else { peer = p; peerLease = lease; true }
        }
        if (!installedPeer) { disposePeer(p); return }
        if (!withPeerOperation(lease) { p.build() }) return
        if (!withPeerOperation(lease) {
                p.addScreenSource(projectionData) {
                    if (!closed.get()) { stop(); onProjectionStopped() }
                }
            }) return

        val offerSdp = p.createOffer()
        if (closed.get()) return
        channel.send(offerMsg(tid, offerSdp))
        logI("OFFER sent (${offerSdp.length} bytes)")

        // State flips to Casting once the peer reports CONNECTED. If that
        // never happens within 15 s the ICE most likely failed; we leave the
        // session in Signaling so the UI surfaces "connecting" rather than a
        // misleading Casting state.
        s.launch {
            p.connectionState.collect { cs ->
                when (cs.name) {
                    "CONNECTED" -> {
                        synchronized(this@WebRtcCastSession) {
                            if (_state.value is WebRtcState.Signaling) transition(WebRtcState.Casting(device))
                        }
                    }
                    "FAILED" -> fail("WebRTC ICE failed — are both devices on the same LAN?")
                    "CLOSED" -> {
                        close()
                    }
                    else -> {}
                }
            }
        }
    }

    // Explicit stop: local teardown is immediate; the receiver gets a bounded,
    // best-effort BYE/STOP/CLOSE so it clears its <video> instead of freezing.
    fun stop() = finish(null, farewell = true)

    fun close() = finish(null)

    private fun fail(message: String) = finish(message)

    @Synchronized
    private fun transition(next: WebRtcState): Boolean {
        if (closed.get()) return false
        _state.value = next
        return true
    }

    // Claim synchronous native initialization before invoking JNI without the
    // monitor. A disconnect can close transport immediately, but peer disposal
    // waits until the operation has finished installing native resources.
    private inline fun withPeerOperation(lease: PeerLease, operation: () -> Unit): Boolean {
        synchronized(this) {
            if (closed.get() || peerLease !== lease) return false
            lease.operations++
        }
        try {
            operation()
            return !closed.get()
        } finally {
            val disposal = synchronized(this) {
                lease.operations--
                takeReleasedPeer(lease)
            }
            disposePeer(disposal)
        }
    }

    // Called only while holding the session monitor.
    private fun takeReleasedPeer(lease: PeerLease): SessionPeer? {
        if (!lease.releaseRequested || lease.operations != 0 || lease.disposed) return null
        lease.disposed = true
        return lease.peer
    }

    private fun disposePeer(disposal: SessionPeer?) {
        runCatching { disposal?.close() }.onFailure { logW("close peer: $it") }
    }

    private fun finish(error: String?, farewell: Boolean = false) {
        val (resources, farewellTarget) = synchronized(this) {
            if (!closed.compareAndSet(false, true)) return
            _state.value = if (error == null) WebRtcState.Idle else WebRtcState.Error(error)
            val target = if (farewell && error == null && (transportId != null || sessionId != null))
                Farewell(transportId, sessionId) else null
            val disposal = peerLease?.let {
                it.releaseRequested = true
                takeReleasedPeer(it)
            }
            val owned = Triple(disposal, readerJob, scope)
            peer = null
            peerLease = null
            readerJob = null
            scope = null
            owned to target
        }
        // Native disposal can wait for callbacks; never hold the session monitor here.
        disposePeer(resources.first)
        resources.second?.cancel()
        // The transport must outlive the farewell, but never past the timeout.
        if (farewellTarget != null) sendFarewell(farewellTarget) else closeChannel()
        resources.third?.cancel()
    }

    private fun closeChannel() {
        runCatching { channel.close() }.onFailure { logW("close channel: $it") }
    }

    // Own short-lived scope: the session scope is cancelled by finish(). Joining
    // the send (rather than wrapping it in withTimeout) keeps the deadline
    // effective even if a socket write ignores cancellation.
    private class Farewell(val transportId: String?, val sessionId: String?)

    // BYE (app teardown hint), STOP (quit the receiver app so the TV clears the
    // frame even if its JS ignores BYE), then CLOSE. Ids are never logged.
    private suspend fun sendFarewellMessages(target: Farewell) {
        target.transportId?.let { channel.send(byeMsg(it)); logI("farewell: sent BYE") }
        target.sessionId?.let { channel.send(stopAppMsg(it)); logI("farewell: sent STOP") }
        channel.send(closeMsg()); logI("farewell: sent CLOSE")
    }

    private fun sendFarewell(target: Farewell) {
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        try {
            scope.launch {
                try {
                    val send = launch {
                        try {
                            sendFarewellMessages(target)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logW("farewell failed: $e")
                        }
                    }
                    if (withTimeoutOrNull(FAREWELL_TIMEOUT_MS) { send.join() } == null) logI("farewell: timed out")
                    send.cancel()
                } finally {
                    closeChannel()
                    scope.cancel()
                }
            }
        } catch (e: Throwable) {
            // Rejected launch: the farewell is best-effort, the transport is not.
            logW("farewell not scheduled: $e")
            closeChannel()
            scope.cancel()
        }
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            delay(HEARTBEAT_INTERVAL_MS)
            val since = System.currentTimeMillis() - lastHeartbeatMs
            if (since > HEARTBEAT_TIMEOUT_MS) {
                logE("heartbeat: no PONG in ${since}ms, declaring receiver dead")
                fail("receiver heartbeat timeout")
                return
            }
            runCatching { channel.send(pingMsg()) }.onFailure { fail("receiver heartbeat failed: ${it.message}"); return }
        }
    }

    private suspend fun handleIncoming() {
        channel.incoming.collect { msg ->
            val obj = runCatching { JSONObject(msg.payloadUtf8) }.getOrNull() ?: return@collect
            val type = obj.optString("type")
            when {
                msg.namespace == CastNs.HEARTBEAT && type == "PING" -> {
                    lastHeartbeatMs = System.currentTimeMillis()
                    channel.send(pongMsg())
                }
                msg.namespace == CastNs.HEARTBEAT && type == "PONG" -> {
                    lastHeartbeatMs = System.currentTimeMillis()
                }
                msg.namespace == CastNs.RECEIVER && type == "RECEIVER_STATUS" -> parseReceiverStatus(obj)
                msg.namespace == WEBRTC_NAMESPACE -> handleSignaling(obj, type)
                msg.namespace == CastNs.CONNECTION && type == "CLOSE" -> {
                    logW("receiver sent CLOSE")
                    close()
                }
            }
        }
    }

    private suspend fun handleSignaling(obj: JSONObject, type: String) {
        when (type) {
            SignalingType.READY -> logI("receiver reports READY")
            SignalingType.ANSWER -> {
                val sdp = obj.optString("sdp")
                if (sdp.isEmpty()) { logE("ANSWER missing sdp"); return }
                runCatching { peer?.setRemoteAnswer(sdp) }
                    .onFailure {
                        logE("setRemoteAnswer failed", it)
                        fail("remote answer rejected: ${it.message}")
                    }
                logI("ANSWER applied (${sdp.length} bytes)")
            }
            SignalingType.ICE -> {
                val c = obj.optJSONObject("candidate") ?: return
                val cand = c.optString("candidate")
                if (cand.isEmpty()) return
                val mid = c.optString("sdpMid").ifEmpty { null }
                val mIdx = c.optInt("sdpMLineIndex", 0)
                peer?.addRemoteIceCandidate(IceCandidate(mid, mIdx, cand))
            }
            SignalingType.BYE -> {
                logI("receiver sent BYE")
                close()
            }
        }
    }

    // Volume lives on the receiver, not the media session, so we can send
    // these without waiting for transportId. Coerce level into [0, 1] to
    // match the Cast V2 contract — receivers silently clamp anyway, but
    // clean bounds keep the slider behaving correctly.
    suspend fun setVolume(level: Double) {
        if (_volume.value.isFixed) {
            logW("setVolume: receiver reports fixed volume")
            return
        }
        channel.send(setVolumeMsg(level).first)
    }

    suspend fun setMute(muted: Boolean) {
        channel.send(setMuteMsg(muted).first)
    }

    private fun parseReceiverStatus(obj: JSONObject) {
        val status = obj.optJSONObject("status") ?: return
        // Volume arrives on both the unsolicited requestId=0 broadcast and
        // the LAUNCH response, so we pick it up immediately after connect
        // (before any applications are populated). Parse it unconditionally.
        status.optJSONObject("volume")?.let { v ->
            val current = _volume.value
            _volume.value = CastVolume(
                level = v.optDouble("level", current.level),
                muted = v.optBoolean("muted", current.muted),
                controlType = v.optString("controlType", current.controlType),
            )
        }
        val apps = status.optJSONArray("applications") ?: return
        if (apps.length() == 0) return
        val app = apps.getJSONObject(0)
        transportId = app.optString("transportId").ifEmpty { null }
        sessionId = app.optString("sessionId").ifEmpty { null }
        logI("""webrtc receiver status: {"transportId":"$transportId","sessionId":"$sessionId"}""")
    }

    companion object {
        private const val FAREWELL_TIMEOUT_MS = 500L
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val HEARTBEAT_TIMEOUT_MS = 15_000L
    }
}
