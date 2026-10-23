# ScreenCast 0.15.0-rc1

This is a pre-release for testing. Upgrade only if you want to help evaluate
these changes; device behavior and compatibility still need testing.

## Highlights
- Use Android Share → "Cast shared media" to cast a single MP4/MP3 URL.
- WebRTC can send playback audio without using the microphone.
- An HLS "Share phone audio" banner helps recover missing audio permission.
- Developers can use `tools/cast-sim` for receiver and latency experiments.

## Optional and experimental
- "Mix in microphone" is OFF by default (Android 11+). Each enable asks for
  consent; a separate recorder mixes microphone audio into the WebRTC cast.
  A noise gate and feedback guard are included, but there is no echo
  cancellation. Headphones on the phone do not prevent echo from a loud TV.
- "Mute phone while casting" is experimental and OFF by default. It uses
  the media stream mute flag and attempts to restore it when casting stops.
- The shared WebRTC stream ID experiment is behind a compile-time flag,
  OFF by default. An A/V sync fix is not claimed.

## Permissions and privacy
- Audio permission now has a one-time explanation dialog, hidden below
  Android 10. Playback capture still needs Android's audio permission.
- The manifest adds `FOREGROUND_SERVICE_MICROPHONE` and the microphone
  service type. The microphone is used only if you opt into the mic mix;
  the microphone service type is requested only for that mix.
- Cast control still accepts any TLS certificate without Cast device
  authentication. This pre-existing limitation is documented in SECURITY.md.

## Known limitations
- A/V sync and latency remain unverified; mic echo/gate thresholds need
  device tuning. Phone mute was checked once on a Pixel 10 Pro Fold (TV kept playing the cast audio while the phone was silent); other devices and outputs are untested.
- Android 17 AudioHardening reportedly logs that background app volume
  control "would be ignored". Unmute-at-stop could be ignored if enforced;
  this behavior is not verified for this release.

## Installation and signing
Debug builds use a separate `.debug` application ID and a debug signing key.
Release builds require your own signing credentials; without them the APK
is unsigned and not installable. Release signing has not been verified here.
An in-place upgrade requires the same application ID and signing key as
your installed build.

## Verify your download
Signing certificate SHA-256 (app-release.apk):
`54:52:E1:1D:6F:DB:F7:EA:A5:99:00:7A:ED:49:22:94:8F:9A:06:6D:3D:42:6C:E6:97:5A:76:69:81:19:AE:49`

Check with `apksigner verify --print-certs app-release.apk`. Installing over an upstream ScreenCast release will fail (different signing key): uninstall first.
