# Opt-in microphone mix for WebRTC playback casting

Status: implemented on `feat/mic-mix` (hardening round after the security re-review: SAFE TO MERGE as default-off); awaiting device checks.
Product decision: visible toggle ("Mix in microphone"), OFF by default, Android 11 (API 30) or newer only.
Unit tests: 613 pass, 0 failed (575 before the hardening round; mic-mix suites: controller 42, policy 16, invariants 11, store 3, mixer 25, ring 6+3). Gates: testDebugUnitTest, assembleDebug, lintDebug.

## Design

Playback capture is unchanged: the libwebrtc ADM is built with `AUDIO_SOURCE_REMOTE_SUBMIX` and is
watched by `PlaybackAudioGuard`, which disables recording if the ADM ever starts any other record.
The microphone is captured by a **separate** `AudioRecord` (`AndroidMicSource`, `AudioSource.MIC`,
48 kHz mono PCM16) outside the ADM. Pieces (all in `webrtc/`):

| File | Role |
|---|---|
| `PcmMixer.kt` | Pure mixer + `MicGate` + `FeedbackGuard` + `GateOpenWatch` (one-time log `gate never opened in 30 s while the mic is active`). Mic: -9 dB (Q15 11627), then a gate with 5 ms attack / 150 ms hold / 100 ms release ramps (opens at raw sample >= 1500). Playback is attenuated by -3 dB only in proportion to the gate gain, so it is byte-identical while the gate is closed. Sum saturated. Byte-wise LE access; trailing partial frame untouched. Feedback guard: mixed-in mic peak >= 9000 continuously for 3 s -> trips. |
| `MicRingBuffer.kt` | Bounded (100 ms) FIFO, drop-oldest on overflow, `trimTo(target)`. |
| `MicMix.kt` | `MicSource` seam, `MicMixController` (permission re-check, create/start outside the lock, reader thread, degrade, `stop`/`stopAsync`, silence detection, latency trimming, no-consumer watchdog, display status for the notification; the reader thread releases the record itself after its loop exits, `stop()` only calls `rec.stop()`, signals and joins with a bound, and never releases under a live reader), `createMicMixController` (the only gate), `wireMicToGuard`, `MicMixBufferCallback` (mix point). |
| `AndroidMicSource.kt` | The only file that constructs a microphone `AudioRecord`. |
| `WebRtcForegroundType.kt` | Pure `foregroundServiceType(...)` and `startForegroundWithMicFallback(...)` (the seam the service uses). |
| `PlaybackAudioPolicy.kt` | Copy, `micMixSwitchVisible`, `micMixToggleAction`, `micMixConsentAction`, `webRtcNotificationBody`. |

Flow: `WebRtcSessionConfig.micMixActive = micMix && audioActive && sdkInt >= 30`. `WebRtcPeer.build()` calls
`createMicMixController(...)`: **null unless micMixActive**, in which case the ADM buffer callback is exactly the
pre-existing `WebRtcAudioMeter`. The mic is started when the peer connection first reaches CONNECTED (not at
`addTrack`, so signaling time and a cast that never connects never record) and stopped first in `close()`.
Not unit-tested (WebRtcPeer needs libwebrtc); `start()` is idempotent and a stopped controller refuses to start (both tested).

The mix runs inside the ADM `AudioBufferCallback` on the audio thread only while the controller is ACTIVE, the guard
is not tripped, and the buffer is 48 kHz / 2 ch / PCM16, at buffer base offset 0 (not `position()`).
Anything that makes mixing permanently invalid ends the mic via `stopAsync()` (a short-lived thread; the audio thread
never waits on `AudioRecord`): guard tripped, odd format (logged once), mixer exception (logged once), feedback guard.
`PlaybackAudioGuard` also has an `onTrip` hook (not called on orderly shutdown) wired through
`PlaybackAudioController.onGuardTrip` to `MicMixController.stopAsync()`, so an ADM failure ends the reader thread,
releases the record and removes the indicator.

Echo / feedback: the TV speaker re-enters the phone mic 200-400 ms later. Mitigations: -9 dB mic gain, noise gate
(quiet TV sound is closed out, not mixed), feedback guard (sustained loud mic -> mic mix stopped for the rest of the cast, logged),
and honest copy (no AEC; mute the TV or keep the phone away; phone headphones do not prevent TV-to-mic echo).
Limits, stated honestly: (1) loud TV audio reaching the phone mic can itself open the gate (threshold is raw -27 dBFS); while it is open the
playback is attenuated by up to -3 dB and the TV audio re-enters the mix, which is audible as level pumping of the TV audio and echo.
(2) `FeedbackGuard` trips only when the post-gain mic peak stays >= 9000 (about -11 dBFS) for 3 continuous seconds, so it catches howls near
clipping only; any quieter block resets its run, so a slow-building or moderate-level feedback loop is NOT caught. (3) The gate threshold
may be too high for quiet speech at 30-50 cm on `AudioSource.MIC` (see device checks); if it never opens, the mic contributes nothing and a
one-time log line `gate never opened in 30 s while the mic is active` is the only hint.

Latency: the consumer trims the ring to ~30 ms (drop oldest) on its first read and whenever it exceeds ~60 ms; the
100 ms ring is a hard cap only. Drift: mic fast -> trims/clicks, mic slow -> silent mic for that buffer.

Degrade (never touches the ADM or guard; cast continues with playback only, logged): permission denied (incl. GrapheneOS
per-app toggle) -> `DENIED`; factory throws / record not initialized / start fails / read error or exception (logged with
the exception) / 100 empty reads / reader thread cannot be created -> `FAILED`, record released once.
No consumer: if the controller is ACTIVE but `fillMic` has not been called for 2 s (injectable clock; the ADM is not delivering buffers) the mic is stopped
and logged once. ICE `DISCONNECTED`/`FAILED`/`CLOSED` (`shouldStopMicOnIce`) also stop the mic via `stopAsync()`; a transient DISCONNECTED that
recovers does NOT restart it (a stopped controller never restarts), so the mix stays off for the rest of that cast: privacy over convenience.
Silence: 200 consecutive all-zero 10 ms reads (~2 s) -> `statusText` "mic silent (blocked by system?)", logged once,
playback audio unaffected, never reported as active; cleared when audio returns. Surfaced in the log panel only.

Foreground service: manifest `mediaProjection|microphone` + `FOREGROUND_SERVICE_MICROPHONE`. The manifest now declares this permission and the
`microphone` service type: the **Play Console** foreground-service-type declaration (microphone) and the **F-Droid** metadata need updating, and
the release notes must mention the optional microphone mix. The MICROPHONE type is requested only
when micMixActive and RECORD_AUDIO granted and API >= 30 (API 29 has no such type; the switch is hidden below API 30).
`startForegroundWithMicFallback` (tested): a refused mic type (any RuntimeException) -> retry with today's type and the
mic mix is switched **off for that session** (fail closed). Default (off): one call with today's type, same as main.

## UI / disclosure

- Switch shown only when playback audio is on and API >= 30; disabled while casting.
- **Every** off->on toggle shows the consent dialog (also when RECORD_AUDIO is already granted); the permission request follows only if it is missing; a denial leaves the switch off.
- Notification body follows the controller's real status (`MicMixDisplay`, pure `micMixDisplay`/`webRtcNotificationBody`, refreshed on every change through peer -> session -> service): "Microphone is mixed in" only while ACTIVE and not silent; "Microphone mix on, no sound detected" when silent; "Microphone mix starting" before the mic connects; "Microphone mix stopped" after DENIED/FAILED/STOPPED (including feedback-muted, guard trip, no consumer, ICE loss). Default off: text unchanged ("Tap Stop to end").
- Copy states: indicator on Android 12+, echo expected when the TV speaker is audible near the phone, headphones on the phone do not prevent it, the switch is locked during a cast so turn it off before the next cast. No mid-cast "Mic off" action (the simplest truthful option was chosen).
- Turning playback audio off clears the stored mic mix (store-level, plus the ViewModel state) so turning audio back on never re-enables the mic.

## Privacy invariants

Preserved with the toggle off (default, also after upgrade: pref absent = false): no mic `AudioRecord`, same FGS type, ADM pinned to REMOTE_SUBMIX, guard
modified only by an optional no-op `onTrip`, same ADM buffer callback (`WebRtcAudioMeter`). The ADM never records the microphone.
Changed with the toggle on: a separate recorder captures the mic and it is transmitted to the receiver. Mic audio is never logged or stored.

## Static invariants (`MicMixSourceInvariantTest`)

Scans all of `src/main` (kt + java, comments stripped, working-directory independent; files are keyed and allow-listed by path relative to `src/main`, so same-named files in other packages are scanned separately): `AudioRecord(`, `AudioRecord.Builder`, `MediaRecorder`,
`setAudioSource(` only in the allow list (`AndroidMicSource.kt`, `WebRtcPlaybackSwap.kt`; `AudioEncoder.kt`, the pre-existing HLS
playback-capture recorder, may use `AudioRecord.Builder` only, never a raw `AudioRecord(` constructor, and is asserted to use `setAudioPlaybackCaptureConfig` and no audio source); `setAudioSource(` elsewhere
must be the pinned REMOTE_SUBMIX; microphone `AudioSource.*` names only in `AndroidMicSource.kt`; `AndroidMicSource` referenced only from
`WebRtcPeer.kt`/`MicMix.kt`; manifest mic type only on `WebRtcForegroundService`.
Mutation checks run (each made the named test fail, then removed): scratch `AudioRecord(1,`, `AudioRecord.Builder()`, `MediaRecorder()`, `setAudioSource(1)`; plus removing the guard `onTrip` call, the callback's disabled->stop,
the playback-attenuation gating, and the ring high-water trim (each failed 1-4 tests). Hardening round: a same-named scratch `AndroidMicSource.kt` in another package containing `AudioRecord(1,` fails (the old name-keyed test passed it), and a raw `AudioRecord(1,` appended to `AudioEncoder.kt` fails; scratch changes removed.

## Device checks required (not verifiable in unit tests)

- Gate tuning: speak normally at 30-50 cm from the phone with the TV muted, mix on: the gate MUST open and the voice be audible on the TV. The -27 dBFS raw threshold may be too high for quiet speech on `AudioSource.MIC`; if the log shows `gate never opened in 30 s while the mic is active`, lower `MIC_GATE_OPEN_THRESHOLD`. Then repeat with loud TV audio and check for pumping of the TV level.
- Echo/howl: cast with the TV at normal volume and the phone nearby, mix on: does the gate + feedback guard prevent audible howl; tune thresholds if not.
- Mic indicator appears only while the mix is on and connected, and disappears on stop, on guard trip and on app kill. Android 11 shows none (copy says 12+).
- Toggle off (default): no indicator, behavior identical to main.
- GrapheneOS per-app mic permission denied: cast continues, log shows degrade, no crash, no ADM guard trip. Note: a runtime permission revoke kills the app process (it is not an in-app error); the OS sensor-privacy mic toggle instead yields silence, not errors: verify the "mic silent (blocked by system?)" log line after ~2 s and that playback is unaffected.
- Android 11-13: a mic `AudioRecord` started without while-in-use access can deliver silence rather than fail; verify silence detection covers it.
- FGS start rules on API 34+ / Android 17: the service starts after the projection consent activity finishes; confirm the mic type is accepted (else the log shows the fallback and the mix is off). Also try a start that is not from the foreground (tile).
- Mic start at CONNECTED: no missing first words; latency/AV sync with mix on vs off; CPU of the extra thread.
- Notification shows "Microphone is mixed in" only while the mic is really active; deny the permission / flip the OS mic switch / drop the network and confirm it changes to "no sound detected" or "stopped".

## Residual risks

- No echo cancellation; gate, -9 dB and the feedback guard reduce but do not remove echo. Thresholds are untuned guesses until device-tested.
- The feedback guard is level-based: sustained loud speech could in theory trip it (then the mic stays off until the next cast); it catches only howls near clipping (post-gain peak >= 9000 for 3 s) and resets on any quieter block, so moderate feedback is not caught.
- Loud TV audio can open the gate and cause pumping of the TV audio; the gate is not an echo canceller.
- A transient ICE DISCONNECTED stops the mic for the rest of the cast.
- Mic/ADM clock drift: occasional clicks (drop-oldest) or short gaps.
- Mixing mutates the ADM-owned buffer in place; depends on libwebrtc's callback contract (valid from index 0 for `bytesRead`).
- Silence detection is logged and shown in the notification text only; there is no in-app status.
- `WebRtcPeer` wiring (start at CONNECTED, guard hook, callback choice) is exercised only through its parts, not end to end.
- RECORD_AUDIO is shared with playback capture; revoking it disables both (and kills the process).
