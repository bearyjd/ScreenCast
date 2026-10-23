# Opt-in microphone mix for WebRTC playback casting

Status: implemented on `feat/mic-mix`; awaiting security review and device checks.
Product decision: visible toggle ("Mix in microphone"), OFF by default.

## Design

Playback capture is unchanged: the libwebrtc ADM is built with `AUDIO_SOURCE_REMOTE_SUBMIX` and is
watched by `PlaybackAudioGuard`, which disables recording if the ADM ever starts any other record.

The microphone is captured by a **separate** `AudioRecord` (`AndroidMicSource`, `AudioSource.MIC`,
48 kHz mono PCM16) that is outside the ADM and outside the guard. Pieces (all in `webrtc/`):

| File | Role |
|---|---|
| `PcmMixer.kt` | Pure mixer. Mono mic upmixed to both channels; each source scaled -3 dB (Q15 23170), summed, saturated to Short. Underrun = silent mic with the same playback gain (no level pumping). Byte-wise LE access: independent of ByteBuffer order; trailing partial frame untouched. |
| `MicRingBuffer.kt` | Bounded (100 ms) FIFO. Overflow drops the oldest samples (counted); underrun returns fewer samples. Monitor held only for an array copy. |
| `MicMix.kt` | `MicSource` seam, `MicMixController` (permission re-check, create, start, reader thread, degrade, idempotent stop), `createMicMixController` (the only gate), `MicMixBufferCallback` (mix point). |
| `AndroidMicSource.kt` | The only file allowed to name a microphone `AudioSource` (enforced by a test). |
| `WebRtcForegroundType.kt` | Pure `foregroundServiceType(micMix, permissionGranted, sdkInt)`. |

Flow: `WebRtcSessionConfig.micMixActive = micMix && audioActive` (audioActive already implies
RECORD_AUDIO granted, API >= 29, audio requested). `WebRtcPeer.build()` calls
`createMicMixController(config, ...)`: **null unless micMixActive**, in which case the ADM buffer
callback is exactly the pre-existing `WebRtcAudioMeter`. The mic is started only after a playback
audio track exists (`addScreenSource`), and stopped first in `close()`.
The mix runs inside the ADM `AudioBufferCallback` on the ADM audio thread, only while the controller
is ACTIVE, the guard is not tripped, and the buffer is 48 kHz / 2 ch / PCM16; otherwise the buffer is
untouched. Exceptions in the mixer disable mixing, never reach the audio thread.

Drift: mic and ADM clocks are independent and there is no resampler. Mic fast -> ring overflow drops
oldest samples (rare click; latency capped at 100 ms). Mic slow -> underrun -> silent mic for that buffer.
Added latency on the mic path is bounded by the ring (<= 100 ms); the playback path adds none.

Degrade (never touches the ADM or guard; cast continues with playback only, logged to the log panel):
permission denied (incl. GrapheneOS per-app mic toggle) -> `DENIED`; factory throws / record not
INITIALIZED / `startRecording` fails / read error / 100 empty reads -> `FAILED`, record released.

Foreground service: `foregroundServiceType="mediaProjection|microphone"` in the manifest, plus
`FOREGROUND_SERVICE_MICROPHONE`. At runtime the MICROPHONE type is passed to `startForeground` only when
micMixActive and RECORD_AUDIO is granted and API >= 30; otherwise the type is exactly today's
MEDIA_PROJECTION. If the system rejects the mic type (`SecurityException`/`ForegroundServiceStartNotAllowedException`),
the service restarts foreground with today's type and **turns the mic mix off for that session** (fail closed).

UI: second switch under the audio switch, shown only when playback audio is on, disabled while casting.
Turning it on without RECORD_AUDIO shows an explanatory dialog first, then the system prompt; a denial leaves
the switch off. The line "The microphone is not used" is shown only while the mix is off
(`audioSettingDescription`), and the permission note has a variant for the on state.

## Privacy invariants

Preserved: with the toggle off (default, also after upgrade: pref absent = false) no mic `AudioRecord` is
created, the foreground type is unchanged, the ADM is pinned to REMOTE_SUBMIX, the guard is unmodified.
The ADM never records the microphone, in either state.
Changed: with the toggle on, the app records the microphone via a separate record and transmits it to the
receiver. Android's mic indicator will show. The mic also picks up room/TV sound (echo risk; no AEC is applied).
Mic audio is never logged or stored; only status lines are logged (no sample data).

## Threat notes: what a hostile reviewer should check

1. Gate: grep for `AndroidMicSource` / `AudioSource.MIC`: only `createMicMixController` -> `MicMixController.start` reaches it.
   `MicMixSourceInvariantTest` fails if another file names a mic AudioSource.
2. `micMixActive` requires `audioActive`; snapshot reads RECORD_AUDIO at cast start and the controller re-checks at start.
3. The guard is untouched; confirm `PlaybackAudioGuardTest` / `PlaybackAudioControllerTest` unchanged and green.
4. Mic stops on every exit path: `close()` (first step), reader error, controller `stop()`. Verify service stop/terminal paths all reach `WebRtcPeer.close()`.
5. Mix point mutates the buffer in place: confirm it cannot run before the guard check and only handles 48k/2ch/PCM16.
6. Manifest: only `WebRtcForegroundService` carries the microphone type (tested). Check no new exported component.
7. Copy truthfulness in both states (tested for the audio description and permission note).
8. Pref `mic_mix` lives in the app-private `webrtc_config` prefs; not backed up differently from other prefs (check `allowBackup`/data extraction rules).

## Device checks required (not verifiable in unit tests)

- Mic indicator appears only when the toggle is on and a cast is running, and disappears at stop (and on app kill).
- Toggle off (default): no indicator; behavior identical to main.
- GrapheneOS per-app mic toggle off / permission denied: cast continues, log shows degrade, no crash, no ADM guard trip.
- Headphones vs TV speaker echo; voice level vs media level balance (-3 dB each).
- FGS: assumption is that the service is started while the app is in the foreground (user taps Cast, MediaProjection consent activity),
  which satisfies the "while-in-use" requirement for the MICROPHONE type on Android 14+; a start from the quick-settings tile
  or any background path may be refused and then runs with the mic mix off. Verify on API 30, 34, 35/36. On API 29 the MICROPHONE type does not exist, so `startForegroundNow` gets no mic type and
  the session runs with the mic mix **off** (unit-tested via the pure function; confirm the log line on a device if available).
- Latency/AV sync with mix on vs off; CPU impact of the extra record thread.
- Mic revoked mid-cast: record read error -> degrade.

## Residual risks

- No echo cancellation; room/TV audio is re-captured.
- Mic and ADM clock drift produces occasional clicks (drop-oldest) or short gaps.
- On API 29 the mix is silently unavailable (no mic FGS type); the switch still shows on.
- Mixing mutates the ADM-owned buffer in place; correctness depends on libwebrtc's callback contract (buffer valid for `bytesRead`).
- RECORD_AUDIO is a single permission shared with playback capture; revoking it disables both.
