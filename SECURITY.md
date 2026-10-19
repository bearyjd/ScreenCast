<!-- Fork modification by bearyjd: clarify WebRTC lifecycle, routing and video-only limits. -->
# Security and privacy

This independent fork has not undergone a security audit. Successful build checks and a limited HLS video test do not establish production readiness.

## Known limitations

- HLS video is served over plain HTTP. A random session URL token gates access but does not encrypt the stream or protect it from network observers. Use only a trusted LAN; do not expose the server through port forwarding or a public network.
- The Cast control channel accepts receiver certificates without authenticating the receiver. TLS alone therefore does not establish that the endpoint is the intended device.
- During an in-flight native WebRTC build/capture-setup call, shutdown closes the transport and cancels work immediately but defers peer disposal until that call returns, so late native allocations are included. An indefinitely blocked native startup call delays peer disposal.
- WebRTC session/service lifecycle now has JVM regression coverage for local Stop, terminal failures, idempotent cleanup, foreground/wake-lock release and retry. Native capture teardown, microphone indicators, receiver compatibility, latency and physical VPN routing remain unverified. Android TV remote control remains unvalidated.
- WebRTC is video-only in every variant: audio-enabled session configurations are rejected before connection or peer creation; the setting is disabled and legacy audio-enabled preferences read as off. The WebRTC library may create a default audio device module; JVM boundary tests do not prove microphone indicator behavior. A microphone-free audio implementation requires separate review.
- WebRTC no longer binds the process to Wi-Fi, excludes VPN adapters, forces Wi-Fi priority or enables Android-specific per-network monitoring/binding. VPN/firewall policy can prevent LAN casting; this fork does not override that policy. These source changes are not a physical routing guarantee.
- The audio-guard debug build omits `RECORD_AUDIO`; video-only testing does not validate audio capture or its privacy behavior in other builds.

The Quick Settings tile opens ScreenCast's device picker; starting a cast then uses Android's normal capture picker and consent flow. Stop All was manually verified to stop capture and close the HLS server in the tested configuration; broader lifecycle behavior still needs testing.

## Reporting issues

For non-sensitive defects, open an issue in this fork with sanitized reproduction steps, the app revision, Android version, and expected versus actual behavior. Do not post screen contents, raw device logs, personal account details, device identifiers, addresses, or session URLs and tokens.

A private disclosure channel has not yet been established. For a sensitive vulnerability, avoid public exploit details and request a private contact method first. There is currently no supported-release or security-response-time commitment.
