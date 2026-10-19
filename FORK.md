# Fork scope and attribution

This independent fork preserves the history, Apache-2.0 [LICENSE](LICENSE), and credits of [ddagunts/ScreenCast](https://github.com/ddagunts/ScreenCast). Its upstream base is version 0.14.1, commit [`7e66bbe`](https://github.com/ddagunts/ScreenCast/commit/7e66bbe). Original app functionality and third-party components remain credited to their upstream authors. This fork is not affiliated with or endorsed by GrapheneOS.

## Changes

- Bound the receiver launch wait at 30 seconds instead of 5 seconds.
- Remove `RECORD_AUDIO` from the audio-guard debug manifest for video-only testing.
- Add a Quick Settings tile that opens ScreenCast's device picker. Starting a cast then uses Android's standard capture picker and consent flow.

## Verified behavior and limits

Manual testing on GrapheneOS with Android 17 confirmed visible HLS video, Stop All stopping capture and closing the server, and the tile opening the app's device picker. The launch-wait change passed 100 unit tests; the final tile build passed assembleDebug and lint, with 37 warnings and one hint remaining.

Audio, WebRTC, remote control, multi-receiver synchronization, and broader compatibility remain unverified by this fork. Lag remains unresolved. These checks are not a security audit or a production-readiness assessment. See [SECURITY.md](SECURITY.md) for known limitations.

## Roadmap

- Measure and improve HLS latency without weakening capture consent or shutdown behavior.
- Review HTTP stream exposure, receiver authentication, and WebRTC lifecycle and audio handling.
- Test inherited modes and shutdown behavior across more devices.
- Establish a distinct fork release identity and publish signed releases; both remain pending.
