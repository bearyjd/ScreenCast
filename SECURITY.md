<!-- Fork modification by bearyjd: clarify WebRTC lifecycle, routing and playback-audio limits. -->
# Security and privacy

This independent fork has not undergone a security audit. Successful build checks and a limited HLS video test do not establish production readiness.

## Known limitations

- HLS video is served over plain HTTP. A random session URL token gates access but does not encrypt the stream or protect it from network observers. Use only a trusted LAN; do not expose the server through port forwarding or a public network.
- The Cast control channel accepts receiver certificates without authenticating the receiver. TLS alone therefore does not establish that the endpoint is the intended device.
- During an in-flight native WebRTC build/capture-setup call, shutdown closes the transport and cancels work immediately but defers peer disposal until that call returns, so late native allocations are included. An indefinitely blocked native startup call delays peer disposal.
- WebRTC session/service lifecycle now has JVM regression coverage for local Stop, terminal failures, idempotent cleanup, foreground/wake-lock release and retry. Native capture teardown, microphone indicators, receiver compatibility, latency and physical VPN routing remain unverified. Android TV remote control remains unvalidated.
- WebRTC audio is on by default and identical in debug and release builds. Privacy: **Sound from any app that allows audio capture is sent to the TV, even if you share only one app** (verified on a device: sharing Maps sent a movie playing in Chrome). Anything playing media or game audio on the phone can be heard on the TV. scope, permission, capture limits and the microphone-safety design are described in [WEBRTC.md](WEBRTC.md). The microphone recorder is never created or started by this app's code, and the library is configured (audio source REMOTE_SUBMIX) so it cannot build one by itself; if it tried, creation would fail and a guard disables audio. This rests on a bytecode review of the pinned library plus one device observation, not a guarantee. The swap uses reflection on libwebrtc internals (pinned 125.6422.07, guarded by a contract test and R8 keep rules), so any library bump needs re-review. The foreground service type stays `mediaProjection`. WebRTC media is encrypted with DTLS-SRTP (the app sets no option that disables encryption), but the receiver is not authenticated: the Cast control channel accepts any certificate and carries the DTLS fingerprint, so a LAN attacker who impersonates the receiver could receive the stream. HLS (not WebRTC) is plaintext HTTP. Device-verified on one phone and TV (Pixel 10 Pro Fold, Chromecast, Android 17): audio capture without the microphone, and picture plus sound both arriving on the TV once with Chrome shared. Audio/video sync and latency are NOT verified; background and locked-phone capture on Android 14-17 is unverified.
- WebRTC no longer binds the process to Wi-Fi, excludes VPN adapters, forces Wi-Fi priority or enables Android-specific per-network monitoring/binding. VPN/firewall policy can prevent LAN casting; this fork does not override that policy. These source changes are not a physical routing guarantee.
- Debug and release builds declare `RECORD_AUDIO` (needed by `AudioRecord` for playback capture); it is requested at runtime. Physical audio and privacy behavior was validated once on one phone and TV only.

The Quick Settings tile opens ScreenCast's device picker; starting a cast then uses Android's normal capture picker and consent flow. Stop All was manually verified to stop capture and close the HLS server in the tested configuration; broader lifecycle behavior still needs testing.

## Reporting issues

For non-sensitive defects, open an issue in this fork with sanitized reproduction steps, the app revision, Android version, and expected versus actual behavior. Do not post screen contents, raw device logs, personal account details, device identifiers, addresses, or session URLs and tokens.

A private disclosure channel has not yet been established. For a sensitive vulnerability, avoid public exploit details and request a private contact method first. There is currently no supported-release or security-response-time commitment.
