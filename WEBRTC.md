<!-- Fork modification by bearyjd: describe the experimental WebRTC safety foundation. -->
# Experimental WebRTC video mirroring

WebRTC uses a custom receiver and Android's normal screen-capture consent. Full-display sharing may follow app switches; selected-app sharing stays within the platform selection. Source changes cannot update a hosted receiver: a compatible receiver must be deployed and registered separately.

This fork supports video-only WebRTC. Audio is disabled in Settings, legacy enabled preferences read as off, and an explicit audio-enabled session is rejected before network or native capture resources are created. There is no claim of validated audio or microphone privacy behavior. The library can create a default audio device module even when no local audio track is added.

For an established capture, Stop synchronously closes the local peer/capture and control transport without waiting for BYE delivery. If a synchronous native build or capture-setup call is still in flight, terminal shutdown closes transport and cancels session work immediately, then disposes the peer once that call returns, including resources allocated after the shutdown callback. No lifecycle monitor is held across native calls or disposal; setup that blocks indefinitely also delays peer disposal. Connect, launch, offer, answer, ICE and heartbeat failures follow terminal cleanup; errors remain visible while service wake-lock and foreground ownership are released. A new attempt requests fresh platform consent.

WebRTC respects system routing. It does not bind the process to Wi-Fi or deliberately bind native sockets through the Android network monitor. VPN/firewall policy can block LAN access; change policy yourself only if desired. Native routing must still be checked on physical devices.

Tests run the production session and service with injected transport/native peer boundaries under Robolectric. They establish lifecycle ownership at those boundaries, not libwebrtc encoder behavior, microphone indicators, actual MediaProjection teardown, Chromecast compatibility or performance. Physical latency remains unmeasured.

Routing implementation reference: [WebRTC Java options](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/PeerConnectionFactory.java) and [JNI factory network-monitor selection](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/src/jni/pc/peer_connection_factory.cc). These explain the public disableNetworkMonitor option and its Android monitor factory effect; the build also verifies availability in the pinned SDK.
