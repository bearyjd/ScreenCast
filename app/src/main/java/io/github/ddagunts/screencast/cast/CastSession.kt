// Fork modification by bearyjd: validate receiver identity/media acknowledgement, redact secrets and dispose failed direct handoffs.
package io.github.ddagunts.screencast.cast

import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal suspend fun waitForReceiverSession(
    hasSession: () -> Boolean,
    timeoutMillis: Long = 30_000L,
): Boolean = withTimeoutOrNull(timeoutMillis) {
    while (!hasSession()) delay(50)
    true
} ?: false

sealed class CastState {
    data object Idle : CastState()
    data class Connecting(val device: CastDevice) : CastState()
    data class Casting(val device: CastDevice) : CastState()
    data class Error(val message: String) : CastState()
}

// controlType: "master" (device owns volume), "attenuation" (relative only), or
// "fixed" (cannot be changed — surface amp / soundbar). UI must disable the
// slider when fixed; sending SET_VOLUME will be silently ignored by the receiver.
data class CastVolume(
    val level: Double = 1.0,
    val muted: Boolean = false,
    val controlType: String = "master",
) {
    val isFixed: Boolean get() = controlType.equals("fixed", ignoreCase = true)
}

class CastSession(val device: CastDevice, private val channel: CastTransport = CastChannel(device.host, device.port)) {
    private val _state = MutableStateFlow<CastState>(CastState.Idle)
    val state: StateFlow<CastState> = _state

    // Player state authority is the receiver — every transition comes from a
    // MEDIA_STATUS push. We never optimistically flip this on a local command;
    // doing so masks LOAD_FAILED and PAUSE rejections.
    private val _playerState = MutableStateFlow("IDLE")
    val playerState: StateFlow<String> = _playerState

    private val _volume = MutableStateFlow(CastVolume())
    val volume: StateFlow<CastVolume> = _volume

    // Current playback position as reported by the receiver's latest
    // MEDIA_STATUS. Units are seconds; for HLS LIVE without PDT these are
    // receiver-local offsets from the first manifest fetch — comparable
    // across status updates for the same receiver, *not necessarily* across
    // different receivers. Used by the sync-align flow with a GET_STATUS
    // refresh immediately before reading.
    private val _currentTime = MutableStateFlow(0.0)
    val currentTime: StateFlow<Double> = _currentTime

    private var scope: CoroutineScope? = null
    private var readerJob: Job? = null
    private var transportId: String? = null
    private var sessionId: String? = null
    private var mediaSessionId: Int? = null
    @Volatile private var diagnosticStage = "idle"
    private var requestedAppId: String? = null

    // Timestamp of the most recent receiver-originated traffic on the
    // heartbeat namespace. Seeded at connect time; updated on every PONG
    // reply and on every PING the receiver initiates (answering those
    // proves the channel is alive in both directions). If this falls more
    // than HEARTBEAT_TIMEOUT_MS behind we declare the session dead — TCP
    // half-open states otherwise leave writes succeeding while the peer is
    // gone, so we can't rely on send() to throw.
    @Volatile private var lastHeartbeatMs: Long = 0L

    private var launchRequestId: Int? = null
    private var loadRequestId: Int? = null
    private var loadedUrl: String? = null
    @Volatile private var disposed = false

    suspend fun startCast(appId: String, mediaUrl: String, contentType: String, autoplay: Boolean = true, streamType: String = "LIVE") {
        requestedAppId = appId
        loadedUrl = mediaUrl
        disposed = false
        _state.value = CastState.Connecting(device)
        val s = CoroutineScope(Dispatchers.IO)
        scope = s
        try {
            diagnosticStage = "tls_connect"
            readerJob = channel.connect {
                if (!disposed) fail("Receiver disconnected. Reopen the share screen to reconnect.")
            }
            currentCoroutineContext().ensureActive()
            channel.send(connectMsg())
            lastHeartbeatMs = System.currentTimeMillis()
            s.launch { heartbeatLoop() }
            s.launch(start = CoroutineStart.UNDISPATCHED) { handleIncoming() }
            diagnosticStage = "receiver_status_wait"
            val (launch, launchId) = launchMsg(appId)
            launchRequestId = launchId
            channel.send(launch)
            if (!waitForReceiverSession({ transportId != null && sessionId != null || disposed })) {
                fail("Receiver launch timed out.")
                return
            }
            if (disposed) return
            val tid = transportId ?: return
            val sid = sessionId ?: return
            channel.send(connectMsg(tid))
            val (load, requestId) = mediaLoadMsg(tid, sid, mediaUrl, contentType, autoplay, streamType)
            loadRequestId = requestId
            diagnosticStage = "media_status_wait"
            channel.send(load)
            logI("LOAD sent requestId=$requestId streamType=$streamType")
            s.launch {
                delay(30_000)
                if (_state.value is CastState.Connecting) fail("Receiver did not confirm playback. Check media URL access and codec support.")
            }
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Exception) {
            logE("cast failure stage=$diagnosticStage exceptionType=${e.javaClass.simpleName}")
            fail("Could not load media on receiver. Check network, URL access and codec support.")
        }
    }

    suspend fun stopPlayback() {
        try {
            val tid = transportId
            val mid = mediaSessionId
            if (tid != null && mid != null) channel.send(mediaStopMsg(tid, mid).first)
        } finally { close() }
    }

    private fun fail(message: String) {
        _state.value = CastState.Error(message)
        close()
    }

    fun stop() {
        val tid = transportId
        val mid = mediaSessionId
        scope?.launch {
            runCatching {
                if (tid != null && mid != null) channel.send(mediaStopMsg(tid, mid).first)
                channel.send(closeMsg())
            }
            close()
        }
    }

    // Pause/play are no-ops until LAUNCH completes and the receiver has sent
    // back a MEDIA_STATUS with a mediaSessionId. The UI is expected to keep the
    // buttons disabled until `state` becomes Casting; this is belt-and-suspenders.
    suspend fun pause() {
        val tid = transportId ?: return logW("pause: no transportId yet")
        val mid = mediaSessionId ?: return logW("pause: no mediaSessionId yet")
        channel.send(mediaPauseMsg(tid, mid).first)
    }

    suspend fun play() {
        val tid = transportId ?: return logW("play: no transportId yet")
        val mid = mediaSessionId ?: return logW("play: no mediaSessionId yet")
        channel.send(mediaPlayMsg(tid, mid).first)
    }

    // Volume lives on the receiver, not the media session, so it works before
    // LOAD completes. No transport/session id needed.
    suspend fun setVolume(level: Double) {
        if (_volume.value.isFixed) return logW("setVolume: receiver reports fixed volume")
        channel.send(setVolumeMsg(level).first)
    }

    suspend fun setMute(muted: Boolean) {
        channel.send(setMuteMsg(muted).first)
    }

    // Seek the receiver to `seconds`. resumeState is forwarded to the Cast
    // media namespace: PLAYBACK_PAUSE (default) lands paused at the new
    // offset, PLAYBACK_START keeps playback rolling — the latter is what
    // steady-state drift correction needs. Returns immediately after the
    // write; the receiver echoes MEDIA_STATUS when the seek completes.
    suspend fun seek(seconds: Double, resumeState: String = "PLAYBACK_PAUSE") {
        val tid = transportId ?: return logW("seek: no transportId yet")
        val mid = mediaSessionId ?: return logW("seek: no mediaSessionId yet")
        channel.send(mediaSeekMsg(tid, mid, seconds, resumeState).first)
    }

    // Prompt the receiver to emit a fresh MEDIA_STATUS so `_currentTime` is
    // up-to-date. Receivers push MEDIA_STATUS on state changes but not
    // continuously; without this nudge the last-seen currentTime can be
    // several seconds stale.
    suspend fun requestStatus() {
        val tid = transportId ?: return logW("requestStatus: no transportId yet")
        channel.send(mediaGetStatusMsg(tid).first)
    }

    fun close() {
        if (disposed) return
        disposed = true
        readerJob?.cancel()
        channel.close()
        scope?.cancel()
        loadedUrl = null
        if (_state.value !is CastState.Error) _state.value = CastState.Idle
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            delay(HEARTBEAT_INTERVAL_MS)
            val since = System.currentTimeMillis() - lastHeartbeatMs
            if (since > HEARTBEAT_TIMEOUT_MS) {
                logE("heartbeat: no PONG in ${since}ms, declaring receiver dead")
                _state.value = CastState.Error("receiver heartbeat timeout")
                close()
                return
            }
            runCatching { channel.send(pingMsg()) }.onFailure { return }
        }
    }

    private suspend fun handleIncoming() {
        channel.incoming.collect { msg ->
            val obj = runCatching { JSONObject(msg.payloadUtf8) }.getOrNull() ?: return@collect
            val type = obj.optString("type")
            when {
                // Either direction counts as "receiver alive". Respond to
                // receiver-initiated PINGs; just note the timestamp on PONGs
                // we asked for.
                msg.namespace == CastNs.HEARTBEAT && type == "PING" -> {
                    lastHeartbeatMs = System.currentTimeMillis()
                    channel.send(pongMsg())
                }
                msg.namespace == CastNs.HEARTBEAT && type == "PONG" -> {
                    lastHeartbeatMs = System.currentTimeMillis()
                }
                msg.namespace == CastNs.RECEIVER && type == "RECEIVER_STATUS" -> parseReceiverStatus(obj)
                msg.namespace == CastNs.RECEIVER && type == "LAUNCH_ERROR" -> {
                    // Only known protocol reasons are safe to log; never dump the payload.
                    val reason = obj.optString("reason").takeIf {
                        it in setOf("CANCELLED", "TIMEOUT", "NOT_FOUND", "SYSTEM_ERROR", "INVALID_PARAMETER")
                    } ?: "other_or_missing"
                    logE("receiver LAUNCH_ERROR: stage=$diagnosticStage requestId=${obj.opt("requestId") as? Number} reason=$reason")
                    if (msg.sourceId == DEFAULT_RECEIVER_ID && obj.optInt("requestId", -1) == launchRequestId) fail("Receiver could not launch the media app.")
                }
                msg.namespace == CastNs.MEDIA && type == "MEDIA_STATUS" && msg.sourceId == transportId -> parseMediaStatus(obj)
                msg.namespace == CastNs.MEDIA && type == "LOAD_FAILED" -> {
                    if (msg.sourceId == transportId && obj.optInt("requestId", -1) == loadRequestId) {
                        fail("Receiver rejected media. Check URL access and MP4/MP3 codec support.")
                    }
                }
                msg.namespace == CastNs.CONNECTION && type == "CLOSE" &&
                    msg.sourceId in setOf(DEFAULT_RECEIVER_ID, transportId) -> {
                    fail("Receiver disconnected. Playback may continue without controls.")
                }
            }
        }
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        // 3× interval — tolerates a single dropped PONG before bailing. Any
        // lower and the sync-maintain pause/seek/play dance (which briefly
        // stalls media traffic) can stumble into a false positive.
        private const val HEARTBEAT_TIMEOUT_MS = 15_000L
    }

    private fun parseReceiverStatus(obj: JSONObject) {
        val status = obj.optJSONObject("status") ?: run {
            logI("receiver status: stage=$diagnosticStage hasStatus=false requestId=${obj.opt("requestId") as? Number}")
            return
        }
        // Volume is reported at the receiver level independent of any active app,
        // so parse it before bailing on the empty-applications case.
        status.optJSONObject("volume")?.let { v ->
            val current = _volume.value
            _volume.value = CastVolume(
                level = v.optDouble("level", current.level),
                muted = v.optBoolean("muted", current.muted),
                controlType = v.optString("controlType").ifEmpty { current.controlType },
            )
        }
        val apps = status.optJSONArray("applications")
        logI("receiver status: stage=$diagnosticStage hasStatus=true hasApplications=${apps != null} applicationCount=${apps?.length() ?: 0} requestId=${obj.opt("requestId") as? Number}")
        if (apps == null) return
        if (apps.length() == 0) return
        val app = (0 until apps.length()).mapNotNull { apps.optJSONObject(it) }
            .firstOrNull { it.optString("appId") == requestedAppId } ?: return
        transportId = app.optString("transportId").ifEmpty { null }
        sessionId = app.optString("sessionId").ifEmpty { null }
        logI("receiver app: stage=$diagnosticStage appMatchesRequested=${app.optString("appId") == requestedAppId} hasTransportId=${transportId != null} hasSessionId=${sessionId != null}")
    }

    private fun parseMediaStatus(obj: JSONObject) {
        val arr = obj.optJSONArray("status") ?: return
        if (arr.length() == 0) return
        val s = arr.getJSONObject(0)
        val msid = s.optInt("mediaSessionId", -1)
        if (msid <= 0) return
        if (mediaSessionId == null && obj.optInt("requestId", -1) != loadRequestId &&
            s.optJSONObject("media")?.optString("contentId") != loadedUrl) return
        if (mediaSessionId != null && mediaSessionId != msid) return
        mediaSessionId = msid
        if (s.optString("playerState") == "IDLE" && s.optString("idleReason") in setOf("ERROR", "CANCELLED", "INTERRUPTED")) {
            fail("Receiver could not play this media. Check URL access and codec support.")
            return
        }
        if (s.optString("playerState") in setOf("PLAYING", "PAUSED", "BUFFERING")) _state.value = CastState.Casting(device)
        val ps = s.optString("playerState").takeIf { it in setOf("IDLE", "PLAYING", "PAUSED", "BUFFERING", "LOADING") } ?: "UNKNOWN"
        if (ps.isNotEmpty()) _playerState.value = ps
        if (s.has("currentTime")) _currentTime.value = s.optDouble("currentTime", _currentTime.value)
        logI("media status: playerState=$ps mediaSessionId=$msid currentTime=${"%.3f".format(_currentTime.value)}")
    }
}
