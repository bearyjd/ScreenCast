<!-- Fork modification by bearyjd: document WebRTC safety scope and verification limits. -->
# Fork scope and attribution

This independent fork preserves the history, Apache-2.0 [LICENSE](LICENSE), and credits of [ddagunts/ScreenCast](https://github.com/ddagunts/ScreenCast). Its upstream base is version 0.14.1, commit [`7e66bbe`](https://github.com/ddagunts/ScreenCast/commit/7e66bbe). Original app functionality and third-party components remain credited to their upstream authors. This fork is not affiliated with or endorsed by GrapheneOS.

## Changes

- Bound the receiver launch wait at 30 seconds instead of 5 seconds.
- Remove `RECORD_AUDIO` from the audio-guard debug manifest for video-only testing.
- Make WebRTC video-only: reject audio-enabled sessions and disable the unreviewed audio setting, including previously enabled preferences.
- Close WebRTC capture/peer/transport resources on Stop and terminal failures (peer disposal waits for an in-flight synchronous native startup operation to return); release foreground-service and wake-lock ownership while preserving errors and allowing retry.
- Remove WebRTC process-wide Wi-Fi binding, VPN candidate exclusion and Wi-Fi preference; disable the native Android network monitor that can bind per-network sockets. System routing remains authoritative.
- Add a Quick Settings tile that opens ScreenCast's device picker. Starting a cast then uses Android's standard capture picker and consent flow.

## Verified behavior and limits

Manual testing on GrapheneOS with Android 17 confirmed visible HLS video, Stop All stopping capture and closing the server, and the tile opening the app's device picker. The launch-wait change passed 100 unit tests; the final tile build passed assembleDebug and lint, with 37 warnings and one hint remaining.

Physical WebRTC playback, capture indicators, VPN routing, latency, audio, remote control, multi-receiver synchronization and broader compatibility remain unverified by this fork. JVM tests exercise production WebRTC session/service lifecycle through injected transport/native-resource boundaries; they do not initialize JNI or measure device performance. Lag remains unresolved. WebRTC has no audio support in this fork; its disabled setting explains the limitation. These checks are not a security audit or a production-readiness assessment. See [SECURITY.md](SECURITY.md) for known limitations.

## Roadmap

- Measure and improve HLS latency without weakening capture consent or shutdown behavior.
- Review HTTP stream exposure and receiver authentication; validate WebRTC native capture teardown, routing and performance on physical devices. A future audio implementation requires separate permission/microphone privacy review.
- Test inherited modes and shutdown behavior across more devices.
- Establish a distinct fork release identity and publish signed releases; both remain pending.
