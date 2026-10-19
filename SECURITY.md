# Security and privacy

This independent fork has not undergone a security audit. Successful build checks and a limited HLS video test do not establish production readiness.

## Known limitations

- HLS video is served over plain HTTP. A random session URL token gates access but does not encrypt the stream or protect it from network observers. Use only a trusted LAN; do not expose the server through port forwarding or a public network.
- The Cast control channel accepts receiver certificates without authenticating the receiver. TLS alone therefore does not establish that the endpoint is the intended device.
- WebRTC lifecycle and audio handling need further review. WebRTC and Android TV remote control are inherited features that this fork has not validated.
- The audio-guard debug build omits `RECORD_AUDIO`; video-only testing does not validate audio capture or its privacy behavior in other builds.

The Quick Settings tile opens ScreenCast's device picker; starting a cast then uses Android's normal capture picker and consent flow. Stop All was manually verified to stop capture and close the HLS server in the tested configuration; broader lifecycle behavior still needs testing.

## Reporting issues

For non-sensitive defects, open an issue in this fork with sanitized reproduction steps, the app revision, Android version, and expected versus actual behavior. Do not post screen contents, raw device logs, personal account details, device identifiers, addresses, or session URLs and tokens.

A private disclosure channel has not yet been established. For a sensitive vulnerability, avoid public exploit details and request a private contact method first. There is currently no supported-release or security-response-time commitment.
