# A/V sync: single shared msid stream (TDD evidence)

Branch feat/av-sync-single-stream. Status: unit-verified, NOT measured. Sync is not claimed fixed.

## Hypothesis
Audio arrives ~75 ms later than video (cast-sim: jitter buffer 9 ms video vs 84 ms audio).
libwebrtc only lip-syncs tracks in the same sync group, derived from the msid stream id.
The sender used distinct ids, so the receiver never synchronised the two.

## Evidence from code
- Confirmed: `WebRtcPeer.kt` used `listOf("screen-video")` and `listOf("screen-audio")`. CHANGELOG ~200-201 says the split lets "the receiver handle them independently"; it does not mention choppy audio. The "choppy audio" rationale came from a code comment removed in this diff, so treat it as unverified lore.
- The history is confounded: the split landed in 0.8.0 (commit 658d0be) together with READ_NON_BLOCKING, disabling AEC/NS/AGC, and receiver Web Audio changes. Any audio improvement cannot be attributed to the split. The receiver has since moved to one merged MediaStream, which weakens the original reason further.
- `receiver/receiver.js` `ontrack` adds `evt.track` to one MediaStream and never reads `evt.streams`; the old comment about it was updated. Receiver does not depend on the ids.
- cast-sim does not itself expose an `audio_vs_video` field; the delta is computed from the two jitterBuffer lines.

## Odds, honestly
- The mechanism (distinct msid -> separate sync groups -> no lip-sync) is likely right.
- That it fixes PERCEIVED lip-sync is unproven: capture-side audio latency is upstream of RTP timestamping and sync cannot see it.
- Latency risk: sync delays video up to audio, so video latency may rise ~75 ms.

## Alternative: receiver-only
Set `jitterBufferTarget` (or legacy `playoutDelayHint`) of ~75 ms on the video RTCRtpReceiver in `receiver/receiver.js`. No APK rebuild, no msid change; worth trying first or alongside. It adds the same latency but cannot engage true sync.

## Change
- `WebRtcConfig.kt`: `webRtcStreamIds(kind, shared)`, `WEBRTC_SHARED_STREAM_ID`, `WEBRTC_*_STREAM_ID_LEGACY`, track-id constants, `WEBRTC_SHARED_STREAM_ID_DEFAULT = false`.
- `WebRtcPeer.kt` uses them; track ids unchanged and distinct.
- Default is OFF (old behavior): the original split was made for a device-observed choppy-audio problem I cannot rule out from code or unit tests, and the sync benefit is unmeasured. Flip the constant to true after the A/B.

## TDD
- RED: `StreamIdsTest` failed to compile (unresolved `webRtcStreamIds`, `WebRtcTrackKind`).
- GREEN: `--tests '*StreamIdsTest'` BUILD SUCCESSFUL after implementation.
- Tests: shared -> identical single id; legacy -> original ids; track ids distinct; default pinned false; source guard (`peerDoesNotHardCodeStreamIds`, RED with hard-coded ids, GREEN after revert).
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
