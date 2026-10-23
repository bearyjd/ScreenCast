// Fork modification by bearyjd: session resource boundary.
package io.github.ddagunts.screencast.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpCapabilities
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.RTCStatsReport
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

// One-shot wrapper around libwebrtc's PeerConnectionFactory + PeerConnection.
// Lifecycle mirrors the cast session: build() once, addScreenSource() once,
// createOffer() → setLocalDescription() → network exchange → setRemoteDescription(answer)
// → addIceCandidate* → close(). Safe to call close() repeatedly.
//
// Threading: libwebrtc callbacks arrive on internal worker threads. We only
// expose them via StateFlows (connectionState, iceCandidates), so the Kotlin
// coroutine side is responsible for its own thread discipline.
internal class WebRtcPeer(
    private val context: Context,
    private val config: WebRtcSessionConfig,
    private val onIceCandidate: (IceCandidate) -> Unit,
) : SessionPeer {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val eglBase: EglBase = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var audioController: PlaybackAudioController? = null
    // Null unless the user opted into the mic mix AND playback audio is active (createMicMixController).
    private var micMix: MicMixController? = null
    private var audioPort: RealAdmPort? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    // Stats sampler: own scope so teardown never depends on the session scope.
    private val samplerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val statsGuard = StatsCallGuard()

    private val _connectionState = MutableStateFlow(PeerConnection.PeerConnectionState.NEW)
    override val connectionState: StateFlow<PeerConnection.PeerConnectionState> = _connectionState

    private val _codecMismatch = MutableStateFlow<String?>(null)
    override val codecMismatch: StateFlow<String?> = _codecMismatch
    private val _micMixDisplay = MutableStateFlow(MicMixDisplay.OFF)
    override val micMixDisplay: StateFlow<MicMixDisplay> = _micMixDisplay
    private val mismatchReporter = CodecMismatchReporter(
        requested = config.videoCodecPreference,
        warn = { logW(it) },
        publish = { _codecMismatch.value = it },
        applied = { codecApplied },
    )
    @Volatile private var codecApplied = true

    // Factory init is a process-wide singleton call inside libwebrtc — guard
    // against callers doing it multiple times in a single app lifetime. The
    // static init is cheap after the first call, but the AAR's native code
    // asserts on re-init of certain subsystems on some ABIs.
    override fun build() {
        synchronized(WebRtcPeer::class.java) {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .createInitializationOptions()
                )
                factoryInitialized = true
            }
        }
        // Keep system routing, including VPN policy, intact.
        val pcfOptions = PeerConnectionFactory.Options().apply {
            // Avoid AndroidNetworkMonitor binding individual sockets to an
            // underlying Network independently of the system default route.
            disableNetworkMonitor = true
        }

        // Build the factory. When audio is enabled we install our custom ADM
        // (JavaAudioDeviceModule + AudioBufferCallback) so MediaProjection PCM
        // can be fed into libwebrtc's audio pipeline — the AudioRecord itself
        // is attached later in addScreenSource() once MediaProjection is
        // available. When audio is disabled we add no local audio track; the
        // library may still create its default ADM. No
        // m=audio section appears in the OFFER, and there's nothing to
        // configure — cheapest way to honor the user's audio toggle.
        val factoryBuilder = PeerConnectionFactory.builder()
            .setOptions(pcfOptions)
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))

        // The ADM is ALWAYS ours and ALWAYS built with source REMOTE_SUBMIX, even
        // with audio off, so the default mic-capable (VOICE_COMMUNICATION) ADM is
        // never created. With audio off its recording is disabled before the
        // factory exists and no audio track is added. With audio on, the real
        // playback recorder is installed in addScreenSource().
        val controller = PlaybackAudioController(log = { logW(it) })
        audioController = controller
        val builder = JavaAudioDeviceModule.builder(context.applicationContext)
            .setAudioSource(AUDIO_SOURCE_REMOTE_SUBMIX)
            .setSampleRate(WEBRTC_AUDIO_SAMPLE_RATE)
            .setUseStereoInput(WEBRTC_AUDIO_CHANNELS == 2)
            // HW AEC + NS are VoIP mic algorithms that mangle playback
            // capture (music gets noise-gated, echoes get "cancelled"
            // against a reference signal that doesn't exist).
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioRecordStateCallback(controller.stateCallback)
            .setAudioRecordErrorCallback(controller.errorCallback)
        if (config.audioActive) {
            val meter = WebRtcAudioMeter(
                recordingState = { controller.record?.let { if (it.isRecording) 3 else 1 } ?: -1 },
                enabled = controller::meterEnabled,
            )
            // Default (micMix off): exactly the meter, unchanged. The mic reader is NOT part of the ADM.
            val mixer = createMicMixController(
                config,
                micPermissionGranted = {
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context.applicationContext, android.Manifest.permission.RECORD_AUDIO,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                },
                factory = { rate -> AndroidMicSource.create(rate) },
                log = { logW(it) },
                onDisplay = { _micMixDisplay.value = it },
            )
            micMix = mixer
            if (mixer != null) _micMixDisplay.value = MicMixDisplay.STARTING
            if (mixer != null) wireMicToGuard(controller, mixer) // guard trip -> mic stopped (off the audio thread)
            builder.setAudioBufferCallback(
                if (mixer == null) meter
                else MicMixBufferCallback(meter, mixer, enabled = controller::meterEnabled, log = { logW(it) }),
            )
        }
        run {
            val adm = builder.createAudioDeviceModule()
            val port = try { RealAdmPort(adm) } catch (t: Throwable) {
                logW("webrtc audio: ADM reflection failed — video only: $t"); null
            }
            audioPort = port
            controller.bind(port, fallbackDisable = { runCatching { adm.setAudioRecordEnabled(false) } })
            controller.prepare(config.audioActive)
            audioModule = adm
            factoryBuilder.setAudioDeviceModule(adm)
        }

        factory = factoryBuilder.createPeerConnectionFactory()

        // Empty ICE server list: both peers are on the same LAN, so host
        // candidates alone are reachable. STUN would add latency for no gain;
        // TURN is pointless without Internet-routed servers we'd have to run.
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // continualGatheringPolicy defaults to GATHER_ONCE, which is fine
            // for a LAN — no network changes expected mid-cast.
            //

        }

        peer = factory!!.createPeerConnection(rtcConfig, observer)
            ?: error("PeerConnectionFactory.createPeerConnection returned null")
        startStatsSampler()
    }

    // Attach a screen-source track. Uses libwebrtc's ScreenCapturerAndroid which
    // wraps MediaProjection internally. The MediaProjection callback onStop is
    // forwarded via the `onProjectionStopped` lambda so the caller can tear
    // the whole session down when the user revokes consent from the notification.
    //
    // On Android 14+, MediaProjection must be obtained via Intent within a
    // foreground service that's already started with the mediaProjection FGS
    // type — ScreenCapturerAndroid.initialize() does that internally using
    // the result data we pass through.
    override fun addScreenSource(
        projectionData: Intent,
        onProjectionStopped: () -> Unit,
    ) {
        val pc = peer ?: error("addScreenSource: build() first")
        val f = factory ?: error("addScreenSource: build() first")

        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                logI("MediaProjection stopped — tearing WebRTC peer down")
                onProjectionStopped()
            }
        }
        val cap = ScreenCapturerAndroid(projectionData, projectionCallback)
        capturer = cap

        val source = f.createVideoSource(cap.isScreencast)
        videoSource = source

        val helper = SurfaceTextureHelper.create("WebRtcScreenCapture", eglBase.eglBaseContext)
        surfaceTextureHelper = helper
        cap.initialize(helper, context, source.capturerObserver)
        val preset = config.videoPreset
        cap.startCapture(preset.width, preset.height, preset.fps)

        val track = f.createVideoTrack(WEBRTC_VIDEO_TRACK_ID, source)
        videoTrack = track
        // Stream ids come from webRtcStreamIds(): separate by default (audio can
        // choppily share a queue with large video frames), or one shared id so
        // libwebrtc lip-syncs the two tracks. See WebRtcConfig.kt.
        val sender = pc.addTrack(track, webRtcStreamIds(WebRtcTrackKind.VIDEO))
        configureVideoSender(sender)
        applyCodecPreference(pc, f, sender)

        if (config.audioActive) {
            // Audio track. ScreenCapturerAndroid owns the MediaProjection
            // instance (obtained lazily inside startCapture); share it with
            // AudioPlaybackCapture so we don't need a second consent prompt.
            // Any failure leaves the cast video-only with no audio track.
            val controller = audioController ?: return
            val projection = cap.mediaProjection
            audioPort?.projection = projection
            if (!controller.attach(projection != null)) return
            // Audio constraints: disable libwebrtc's audio-processing chain for
            // our non-voice source. googEchoCancellation / googAutoGainControl
            // / googNoiseSuppression / googHighpassFilter are VoIP-microphone
            // features; leaving them on mangles music playback capture.
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
            }
            val aSource = f.createAudioSource(audioConstraints)
            audioSource = aSource
            val aTrack = f.createAudioTrack(WEBRTC_AUDIO_TRACK_ID, aSource)
            audioTrack = aTrack
            pc.addTrack(aTrack, webRtcStreamIds(WebRtcTrackKind.AUDIO))
            // The opt-in mic reader starts when the connection reaches CONNECTED (see startMicOnConnect),
            // not here, so signaling time and a never-connected cast never record the microphone.
            startMicOnConnect()
        }
    }

    // Override libwebrtc's conservative defaults for a LAN-only cast:
    //  • maxBitrateBps — without this the BWE settles around 1–2 Mbps even on a
    //    gigabit Wi-Fi. Giving it an explicit ceiling lets the encoder spend
    //    the bandwidth we actually have.
    //  • degradationPreference = MAINTAIN_RESOLUTION — BALANCED (the default)
    //    will drop resolution under CPU pressure, which looks terrible for
    //    screen content (text smears). We'd rather drop frames and keep
    //    pixels sharp. Frame drops are far less noticeable on UI/video casts
    //    than resolution shifts.
    private fun configureVideoSender(sender: org.webrtc.RtpSender) {
        val params = sender.parameters
        if (params.encodings.isEmpty()) {
            logW("configureVideoSender: no encodings on sender, skipping bitrate/degradation tuning")
            return
        }
        val maxBitrate = config.maxBitrateBps
        params.encodings[0].maxBitrateBps = maxBitrate
        params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        // setParameters() returns false when libwebrtc rejects the update
        // (e.g. if a renegotiation clobbered it). Log it — the defaults will
        // still work, just conservatively.
        val applied = sender.setParameters(params)
        if (applied) {
            logI("video sender configured: preset=${config.videoPreset.label} maxBitrate=${maxBitrate / 1_000_000} Mbps degradation=MAINTAIN_RESOLUTION")
        } else {
            logW("video sender.setParameters returned false — bitrate/degradation not applied")
        }
    }

    // Best effort: any failure leaves libwebrtc's default codec order in place.
    private fun applyCodecPreference(pc: PeerConnection, f: PeerConnectionFactory, sender: org.webrtc.RtpSender) {
        val pref = config.videoCodecPreference
        if (pref == VideoCodecPreference.AUTO) {
            logI("video codec preference: $pref (applied=true)")
            return
        }
        val applied = try {
            val transceiver: RtpTransceiver? = pc.transceivers.firstOrNull { it.sender.id() == sender.id() }
            val caps = f.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            val result = orderCodecs(caps.map { CapabilityCodec(it) }, pref)
            if (transceiver == null || !result.applied) {
                if (!result.applied) logW("video codec $pref not supported by this device; using default order")
                false
            } else {
                transceiver.setCodecPreferences(result.codecs.map { it.capability })
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logW("video codec preference failed, using defaults: ${e.javaClass.simpleName}")
            false
        }
        codecApplied = applied
        logI("video codec preference: $pref (applied=$applied)")
    }

    private class CapabilityCodec(val capability: RtpCapabilities.CodecCapability) : CodecLike {
        override val mimeType: String get() = capability.mimeType
        override val parameters: Map<String, String> get() = capability.parameters
    }

    // Failure/denial degrades to playback-only inside the controller; start() never throws and is
    // idempotent (a reconnect re-enters CONNECTED harmlessly; a stopped controller refuses to start).
    private fun startMicOnConnect() {
        val mic = micMix ?: return
        samplerScope.launch {
            _connectionState.first { it == PeerConnection.PeerConnectionState.CONNECTED }
            mic.start()
        }
    }

    // Runs while CONNECTED; collectLatest cancels the loop on any other state.
    private fun startStatsSampler() {
        samplerScope.launch {
            _connectionState.collectLatest { state ->
                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                    VideoStatsSampler(fetch = ::fetchStats, log = { logI(it) }, warn = { logW(it) }, onSample = mismatchReporter::onSample).run()
                }
            }
        }
    }

    private suspend fun fetchStats(): Map<String, StatEntry>? {
        val pc = peer?.takeUnless { closed.get() } ?: return null
        val deferred = CompletableDeferred<RTCStatsReport>()
        // Only the (fast) initiation is guarded; the report arrives via callback.
        statsGuard.run { pc.getStats { deferred.complete(it) } } ?: return null
        val report = withTimeoutOrNull(STATS_TIMEOUT_MS) { deferred.await() } ?: return null
        return report.statsMap.mapValues { (_, v) -> StatEntry(v.type, v.timestampUs, v.members) }
    }

    override suspend fun createOffer(): String {
        val pc = peer ?: error("createOffer: build() first")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        val offerDeferred = CompletableDeferred<SessionDescription>()
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) { offerDeferred.complete(desc) }
            override fun onCreateFailure(err: String?) {
                offerDeferred.completeExceptionally(RuntimeException("createOffer failed: $err"))
            }
        }, constraints)
        val offer = offerDeferred.await()

        val setDeferred = CompletableDeferred<Unit>()
        pc.setLocalDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() { setDeferred.complete(Unit) }
            override fun onSetFailure(err: String?) {
                setDeferred.completeExceptionally(RuntimeException("setLocalDescription failed: $err"))
            }
        }, offer)
        setDeferred.await()
        return offer.description
    }

    override suspend fun setRemoteAnswer(sdp: String) {
        val pc = peer ?: error("setRemoteAnswer: build() first")
        val done = CompletableDeferred<Unit>()
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() { done.complete(Unit) }
            override fun onSetFailure(err: String?) {
                done.completeExceptionally(RuntimeException("setRemoteDescription failed: $err"))
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        done.await()
    }

    override fun addRemoteIceCandidate(candidate: IceCandidate) {
        peer?.addIceCandidate(candidate)
            ?: logW("addRemoteIceCandidate: peer is null (late message?)")
    }

    private fun onMainThread() = android.os.Looper.getMainLooper().thread === Thread.currentThread()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        samplerScope.cancel()
        // Never dispose the native peer while a getStats call is mid-JNI.
        if (!statsGuard.closeAndAwait(STATS_CLOSE_WAIT_MS)) {
            logW("close: in-flight stats call did not finish in ${STATS_CLOSE_WAIT_MS} ms; disposing anyway")
        }
        // Stop the mic first so the record (and the system mic indicator) ends promptly.
        runCatching { micMix?.stop() }.onFailure { logW("close: mic stop: $it") }
        micMix = null
        // Disable ADM recording and wait (bounded) for the capture record to
        // stop before the projection goes away; otherwise it logs read errors.
        runCatching { audioController?.close(if (onMainThread()) 60 else 300) }
            .onFailure { logW("close: audio quiesce: $it") }
        runCatching { capturer?.stopCapture() }.onFailure { logW("close: stopCapture: $it") }
        runCatching { capturer?.dispose() }.onFailure { logW("close: capturer.dispose: $it") }
        runCatching { videoTrack?.dispose() }.onFailure { logW("close: videoTrack.dispose: $it") }
        runCatching { videoSource?.dispose() }.onFailure { logW("close: videoSource.dispose: $it") }
        runCatching { surfaceTextureHelper?.dispose() }.onFailure { logW("close: helper.dispose: $it") }
        runCatching { audioTrack?.dispose() }.onFailure { logW("close: audioTrack.dispose: $it") }
        runCatching { audioSource?.dispose() }.onFailure { logW("close: audioSource.dispose: $it") }
        runCatching { audioModule?.release() }.onFailure { logW("close: audioModule.release: $it") }
        runCatching { peer?.close() }.onFailure { logW("close: peer.close: $it") }
        runCatching { peer?.dispose() }.onFailure { logW("close: peer.dispose: $it") }
        runCatching { factory?.dispose() }.onFailure { logW("close: factory.dispose: $it") }
        runCatching { audioController?.releaseRecord() }.onFailure { logW("close: releaseRecord: $it") }
        runCatching { eglBase.release() }.onFailure { logW("close: eglBase.release: $it") }
        capturer = null; videoTrack = null; videoSource = null
        surfaceTextureHelper = null; peer = null; factory = null
        audioTrack = null; audioSource = null
        audioModule = null; audioController = null; audioPort = null
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            logI("signaling state: $state")
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            logI("ice state: $state")
            // The mic must not outlive a dead link. A transient DISCONNECTED that recovers does not
            // restart it: a stopped controller never restarts (privacy over convenience).
            if (shouldStopMicOnIce(state)) micMix?.stopAsync()
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            logI("ice gathering: $state")
        }
        override fun onIceCandidate(candidate: IceCandidate) {
            onIceCandidate.invoke(candidate)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {
            logI("renegotiation needed")
        }
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            logI("peer connection state: $newState")
            _connectionState.value = newState
            if (newState == PeerConnection.PeerConnectionState.FAILED) {
                logE("peer connection FAILED — ICE could not establish")
            }
        }
    }

    // SDP observer default-empty base. Kotlin doesn't allow us to implement
    // just two of four methods; this saves the boilerplate at each call site.
    private abstract class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(err: String?) {}
        override fun onSetFailure(err: String?) {}
    }

    companion object {
        @Volatile
        private var factoryInitialized = false
        private const val STATS_TIMEOUT_MS = 2_000L
        private const val STATS_CLOSE_WAIT_MS = 2_500L
    }
}
