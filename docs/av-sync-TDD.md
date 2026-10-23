# A/V sync: single shared msid stream (TDD evidence)

Branch feat/av-sync-single-stream. Status: unit-verified, NOT measured. Sync is not claimed fixed.

## Hypothesis
Audio arrives ~75 ms later than video (cast-sim: jitter buffer 9 ms video vs 84 ms audio).
libwebrtc only lip-syncs tracks in the same sync group, derived from the msid stream id.
The sender used distinct ids, so the receiver never synchronised the two.

## Evidence from code
- Confirmed: `WebRtcPeer.kt` used `listOf("screen-video")` and `listOf("screen-audio")` (CHANGELOG 200-201 says this was deliberate, for choppy audio on the TV).
- `receiver/receiver.js` `ontrack` adds `evt.track` to one MediaStream and never reads `evt.streams`; the old comment about it was updated. Receiver does not depend on the ids.
- cast-sim does not itself expose an `audio_vs_video` field; the delta is computed from the two jitterBuffer lines.

## Change
- `WebRtcConfig.kt`: `webRtcStreamIds(kind, shared)`, `WEBRTC_SHARED_STREAM_ID`, track-id constants, `WEBRTC_SHARED_STREAM_ID_DEFAULT = false`.
- `WebRtcPeer.kt` uses them; track ids unchanged and distinct.
- Default is OFF (old behavior): the original split was made for a device-observed choppy-audio problem I cannot rule out from code or unit tests, and the sync benefit is unmeasured. Flip the constant to true after the A/B.

## TDD
- RED: `StreamIdsTest` failed to compile (unresolved `webRtcStreamIds`, `WebRtcTrackKind`).
- GREEN: `--tests '*StreamIdsTest'` BUILD SUCCESSFUL after implementation.
- Tests: shared -> identical single id; legacy -> original ids; track ids distinct; default pinned false.
- Receiver: `node --test receiver/receiver.test.cjs scripts/package-receiver.test.cjs` 31 pass, 0 fail.

## Verified vs not
Verified: the id list builder, default pin, receiver ignores stream ids, build/lint (see gates in the commit run).
Not verified: that libwebrtc applies lip-sync on the real peer SDP (no SDP-level test; the native peer is not unit-testable here), actual delay change, audio smoothness, real TV behavior.

## Residual risks
- Lip-sync may raise video latency (video buffer grows to match audio).
- Choppy-audio regression from the original split may return.
- Shared id may interact with the PlaybackSwap path (not examined in depth).

## Morning device check
1. Run the cast-sim A/B in `tools/cast-sim/README.md` (old vs `true`), 3x each.
2. Cast to the real TV with the flag true: listen for choppiness, check clap/lip sync by eye, check latency.
3. If good, flip `WEBRTC_SHARED_STREAM_ID_DEFAULT` to true and update `StreamIdsTest` pin plus CHANGELOG.
