// Fork modification by bearyjd: service lifecycle boundary for resource tests.
package io.github.ddagunts.screencast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import io.github.ddagunts.screencast.webrtc.MediaMuteGuard
import io.github.ddagunts.screencast.webrtc.MediaMuteGuards
import io.github.ddagunts.screencast.webrtc.WebRtcCastSession
import io.github.ddagunts.screencast.webrtc.WebRtcState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.launch

// Separate FGS from the HLS-mode CastForegroundService. Only ever runs one
// device at a time — unlike HLS mode, WebRTC is a single-peer connection and
// there's no cheap "fan out one encoder to N receivers" story, so we don't
// bother.
// Second-device casting would require a second PeerConnection + a second
// MediaProjection, which re-prompts the user and doubles CPU. Out of scope.
open class WebRtcForegroundService : Service() {

    internal open val workerDispatcher: kotlinx.coroutines.CoroutineDispatcher get() = Dispatchers.Default
    private val scope by lazy { CoroutineScope(workerDispatcher + SupervisorJob()) }
    internal open fun createSession(device: CastDevice, config: io.github.ddagunts.screencast.webrtc.WebRtcSessionConfig) =
        WebRtcCastSession(this, device, config)
    internal open fun createMuteGuard(): MediaMuteGuard = MediaMuteGuards.get(this)
    private val muteGuard by lazy { createMuteGuard() }
    @Volatile private var session: WebRtcCastSession? = null
    private var sessionJob: Job? = null
    // Latest asynchronous stop-restore; a following mute waits for it so restore always precedes it.
    @Volatile private var restoreJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // A previous process may have died while we had the phone muted. This service has no session
        // yet, but a ViewModel-triggered recovery must not unmute a live cast, so liveCast is re-checked
        // under the guard lock. Off the main thread: it reads/commits SharedPreferences.
        scope.launch { muteGuard.recover { liveCast } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        when (action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopCast()
            ACTION_SET_VOLUME -> {
                val level = intent.getDoubleExtra(EXTRA_VOLUME_LEVEL, Double.NaN)
                val s = session
                if (s != null && !level.isNaN()) {
                    scope.launch { runCatching { s.setVolume(level) } }
                }
            }
            ACTION_SET_MUTE -> {
                val muted = intent.getBooleanExtra(EXTRA_MUTED, false)
                val s = session
                if (s != null) {
                    scope.launch { runCatching { s.setMute(muted) } }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (session != null) {
            logW("webrtc: already casting; ignoring duplicate start")
            return
        }
        val name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "?"
        val host = intent.getStringExtra(EXTRA_DEVICE_HOST) ?: return
        val port = intent.getIntExtra(EXTRA_DEVICE_PORT, 8009)
        val appId = intent.getStringExtra(EXTRA_APP_ID) ?: ""
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) {
            logE("webrtc: ACTION_START missing projection result data (code=$code)")
            stopSelfIfIdle()
            return
        }
        if (appId.isBlank()) {
            logE("webrtc: ACTION_START with empty appId")
            _session.value = SessionSnapshot(
                device = CastDevice(name, host, port),
                status = "ERROR",
                errorMessage = "custom App ID is empty",
            )
            stopSelfIfIdle()
            return
        }

        // Snapshot the user's tunable prefs here — the session holds the
        // values for its lifetime. Changing prefs mid-cast has no effect
        // (UI disables the controls while casting); the next startCast
        // picks up any changes.
        val snap = io.github.ddagunts.screencast.webrtc.WebRtcConfigStore(this).snapshot()
        // Fail closed: if the system refuses the microphone FGS type, the mic mix is switched off.
        val micFgs = startForegroundNow(name, snap)
        val cfg = if (snap.micMix && !micFgs) snap.copy(micMix = false) else snap
        if (snap.micMixActive && !micFgs) logW("webrtc: microphone mix unavailable (no microphone foreground type); playback audio only")
        acquireWakeLock()

        val device = CastDevice(name, host, port)
        _session.value = SessionSnapshot(device = device, status = "CONNECTING")

        val s = createSession(device, cfg)
        session = s
        liveCast = true
        sessionJob = scope.launch {
            // Observe state and mirror to the global flow so the UI can see it.
            launch {
                s.state.dropWhile { it is WebRtcState.Idle }.collect { st ->
                    if (session !== s) return@collect
                    val currentVol = _session.value?.volume
                        ?: io.github.ddagunts.screencast.cast.CastVolume()
                    _session.value = when (st) {
                        is WebRtcState.Idle -> null
                        is WebRtcState.Connecting -> SessionSnapshot(device, "CONNECTING", volume = currentVol, codecMismatch = s.codecMismatch.value)
                        is WebRtcState.Launching -> SessionSnapshot(device, "LAUNCHING", volume = currentVol, codecMismatch = s.codecMismatch.value)
                        is WebRtcState.Signaling -> SessionSnapshot(device, "SIGNALING", volume = currentVol, codecMismatch = s.codecMismatch.value)
                        is WebRtcState.Casting -> SessionSnapshot(device, "CASTING", volume = currentVol, codecMismatch = s.codecMismatch.value)
                        is WebRtcState.Error -> SessionSnapshot(device, "ERROR", st.message, volume = currentVol)
                    }
                    refreshNotification()
                    // Mute only once the cast is really up, so a failed connect never silences the phone.
                    // stillWanted runs under the guard lock: a stop that already ran cannot be followed by a mute.
                    if (st is WebRtcState.Casting && cfg.muteWhileCasting) {
                        restoreJob?.join()
                        muteGuard.mute(java.util.UUID.randomUUID().toString()) { liveCast && session === s }
                    }
                    if (st is WebRtcState.Idle || st is WebRtcState.Error) {
                        finishTerminalSession(s)
                    }
                }
            }
            // Mirror receiver volume onto the snapshot so the UI slider tracks
            // device-side changes (mute button on the TV, Google Home app, etc).
            launch {
                s.codecMismatch.collect { m ->
                    val snap = _session.value ?: return@collect
                    if (session === s) _session.value = snap.copy(codecMismatch = m)
                }
            }
            // The notification's microphone line follows the controller's real status.
            launch {
                s.micMixDisplay.collect { d ->
                    if (session !== s) return@collect
                    micDisplay = d
                    refreshNotification()
                }
            }
            launch {
                s.volume.collect { v ->
                    val snap = _session.value ?: return@collect
                    _session.value = snap.copy(volume = v)
                }
            }
            runCatching {
                s.startCast(appId, data) {
                    // MediaProjection revoked from the notification — treat
                    // as an explicit stop.
                    logW("webrtc: projection onStop → stopping service")
                    stopCast()
                }
            }.onFailure { e ->
                if (e is CancellationException || session !== s) return@onFailure
                logE("webrtc: startCast threw", e)
                _session.value = SessionSnapshot(device, "ERROR", e.message ?: "start failed")
                finishTerminalSession(s)
            }
        }
    }

    private fun finishTerminalSession(finished: WebRtcCastSession) {
        if (session !== finished) return
        liveCast = false
        micDisplay = io.github.ddagunts.screencast.webrtc.MicMixDisplay.OFF
        try {
            finished.close()
        } catch (e: RuntimeException) {
            logW("finishTerminalSession: close failed: $e")
        } finally {
            muteGuard.restore()
        }
        session = null
        sessionJob?.cancel()
        sessionJob = null
        releaseWakeLock()
        stopSelfIfIdle()
    }

    // asyncRestore: the AudioManager call and the marker commit stay off the main thread. onDestroy
    // passes false and restores itself, with a bound, because the scope is about to be cancelled.
    private fun stopCast(asyncRestore: Boolean = true) {
        // stop() first: it closes the session synchronously, so cancelling the job
        // afterwards cannot pre-empt the farewell with close().
        liveCast = false
        micDisplay = io.github.ddagunts.screencast.webrtc.MicMixDisplay.OFF
        try {
            runCatching { session?.stop() }.onFailure { logW("stopCast: $it") }
        } finally {
            if (asyncRestore) restoreJob = scope.launch { muteGuard.restore() }
        }
        sessionJob?.cancel()
        sessionJob = null
        session = null
        _session.value = null
        releaseWakeLock()
        stopSelfIfIdle()
    }

    private fun stopSelfIfIdle() {
        if (session == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // Returns true when the foreground type includes MICROPHONE (so the mic mix may run). The mic type
    // is requested only for an active opt-in; if the system rejects it (e.g. not allowed from this
    // state) we fall back to today's type and the caller turns the mic mix off.
    // True while this cast has the microphone mix on (mic FGS type accepted); drives the notification disclosure.
    @Volatile private var micDisplay = io.github.ddagunts.screencast.webrtc.MicMixDisplay.OFF

    private fun micStartDisplay(on: Boolean) =
        if (on) io.github.ddagunts.screencast.webrtc.MicMixDisplay.STARTING else io.github.ddagunts.screencast.webrtc.MicMixDisplay.OFF

    private fun startForegroundNow(deviceName: String, cfg: io.github.ddagunts.screencast.webrtc.WebRtcSessionConfig): Boolean {
        val micType = io.github.ddagunts.screencast.webrtc.foregroundServiceType(
            cfg.micMixActive, cfg.recordAudioGranted, Build.VERSION.SDK_INT,
        )
        val base = io.github.ddagunts.screencast.webrtc.foregroundServiceType(false, false, Build.VERSION.SDK_INT)
        val ok = io.github.ddagunts.screencast.webrtc.startForegroundWithMicFallback(
            micType, base,
            start = { type ->
                micDisplay = micStartDisplay(type != base) // the notification posted with the mic type already discloses the microphone
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(deviceName), type)
            },
            log = { logW(it) },
        )
        micDisplay = micStartDisplay(ok)
        return ok
    }

    private fun refreshNotification() {
        val snap = _session.value
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val name = snap?.device?.name ?: "WebRTC"
        nm.notify(NOTIFICATION_ID, buildNotification(name))
    }

    private fun buildNotification(deviceName: String): Notification {
        val snap = _session.value
        val title = when (snap?.status) {
            "CASTING" -> "WebRTC → $deviceName"
            "SIGNALING" -> "Connecting WebRTC to $deviceName"
            "LAUNCHING" -> "Launching receiver on $deviceName"
            "ERROR" -> "WebRTC error"
            else -> "WebRTC → $deviceName"
        }
        val body = io.github.ddagunts.screencast.webrtc.webRtcNotificationBody(snap?.errorMessage, micDisplay)
        val stopIntent = Intent(this, WebRtcForegroundService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "screencast:webrtc-session",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            .onFailure { logW("release webrtc wakeLock: $it") }
        wakeLock = null
    }

    override fun onDestroy() {
        val terminalError = _session.value?.takeIf { it.status == "ERROR" }
        stopCast(asyncRestore = false)
        restoreWithin(RESTORE_TIMEOUT_MS)
        if (terminalError != null) _session.value = terminalError
        scope.cancel()
        super.onDestroy()
    }

    // Bounded blocking restore for a dying process; a hung AudioManager cannot stall teardown forever.
    private fun restoreWithin(timeoutMs: Long) {
        val t = Thread { muteGuard.restore() }.apply { isDaemon = true; start() }
        t.join(timeoutMs)
        if (t.isAlive) logW("onDestroy: mute restore did not finish within ${timeoutMs}ms")
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "WebRTC cast", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    // UI-visible snapshot. Error and terminal states persist until the user
    // explicitly stops — same pattern as HLS mode's DeviceCast.
    data class SessionSnapshot(
        val device: CastDevice,
        // "CONNECTING" | "LAUNCHING" | "SIGNALING" | "CASTING" | "ERROR"
        val status: String,
        val errorMessage: String? = null,
        val codecMismatch: String? = null,
        val volume: io.github.ddagunts.screencast.cast.CastVolume =
            io.github.ddagunts.screencast.cast.CastVolume(),
    )

    companion object {
        // True from session creation until stop/terminal; read by mute recovery (any thread).
        @Volatile internal var liveCast = false
        const val ACTION_START = "io.github.ddagunts.screencast.webrtc.START"
        const val ACTION_STOP = "io.github.ddagunts.screencast.webrtc.STOP"
        const val ACTION_SET_VOLUME = "io.github.ddagunts.screencast.webrtc.SET_VOLUME"
        private const val RESTORE_TIMEOUT_MS = 2_000L
        const val ACTION_SET_MUTE = "io.github.ddagunts.screencast.webrtc.SET_MUTE"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_DEVICE_HOST = "device_host"
        const val EXTRA_DEVICE_PORT = "device_port"
        const val EXTRA_APP_ID = "app_id"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_VOLUME_LEVEL = "volume_level"
        const val EXTRA_MUTED = "muted"
        const val CHANNEL_ID = "webrtc_cast"
        const val NOTIFICATION_ID = 2

        private val _session = MutableStateFlow<SessionSnapshot?>(null)
        val sessionFlow: StateFlow<SessionSnapshot?> = _session
    }
}
