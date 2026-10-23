# Follow-ups: TDD notes

## 1. RECORD_AUDIO at launch (done, `fix:`)
- Finding: AudioRecord needs RECORD_AUDIO even for playback capture. The default HLS mode (CastForegroundService) has no per-setting flow and streams silence without it, so the prompt is legitimately needed.
- Launch: a one-time Compose Material3 "Share audio?" dialog (`ui/MicRationaleDialog.kt`), visibility held in `rememberSaveable` so rotation / dark-mode toggle keep it. It evaluates only after the launch permission prompts finish.
- SDK gate: `micLaunchAction(declared, granted, rationaleShown, sdkInt)` returns NONE below API 29 (no playback capture there), so API 26-28 never sees the dialog.
- State machine (`micDialogReduce`, pure): Evaluate shows the dialog without persisting anything; `mic_rationale_shown` (prefs `launch_prompts`) is written ONLY by Continue / Not now / dismiss (dismiss = Not now). Answers while hidden are no-ops (re-entry guard); recreating before answering shows it again.
- Recovery path: `MicPermissionBanner` (`ui/MicPermissionBanner.kt`) on the HLS main screen, shown at SDK >= 29 when RECORD_AUDIO is declared but not granted and the dialog was already answered. "Allow" launches the system prompt; once the system will no longer ask (denied and no rationale) it becomes "Open app settings" with a blocked-permission message, which is also the feedback for an immediate denial after Continue. Decision is the pure `micBanner()` reusing `permissionAction()`.
- Tests: `MicLaunchPromptTest` (6), `MicDialogStateTest` (9), `MicBannerTest` (7).
- RED/GREEN: gate test observed failing at runtime (1 failed) before the gate; state-machine and banner tests observed failing to compile (unresolved references) before implementation; then all pass (full suite 505, 0 failures). Not re-verified on a device. No Robolectric ActivityScenario recreate test: MainActivity needs three ViewModels and discovery/network services, not feasible with the current setup; recreation is covered by the pure state machine plus the device check below.
- Device checks: fresh install shows the dialog once and Continue shows the system prompt; Not now, then HLS is silent, the banner appears, Allow works; deny twice (or permanently), the banner shows Open app settings and it opens app info; rotate or toggle dark mode with the dialog open and it stays; relaunch after an answer shows no dialog; API 26-28 shows no dialog or banner.

## 2. Receiver-status listener move (skipped)
- No receiver-status listener exists in the local-share path (`LocalMediaForegroundService`, `MediaHandoffController`, `MediaShareViewModel`, `LocalMediaHost`). RECEIVER_STATUS parsing lives only in `CastSession` and `WebRtcCastSession`, tightly bound to session state. Target is unclear, so no behavior-preserving move was attempted.

## 3. Cast device-auth (done, `test:`)
- Finding: `CastChannel` uses a trust-all manager and runs no Cast device-auth; already documented in SECURITY.md and FORK.md. Not implemented here.
- `CastChannelTrustTest` (2): the first test pins a KNOWN GAP (SECURITY.md): `CastChannel.connect` completes a handshake with a self-signed server, asserted server-side by a latch. The second connects first, then asserts a plain default-context handshake to the same server is still rejected and `HttpsURLConnection`'s default factory is unchanged (trust-all stays scoped). Test JVM gets `--add-opens java.base/java.net` so Conscrypt can run a real server socket.

## 4. Notification Stop action (done, `test:`)
- `WebRtcNotificationStopTest` (1): after a live cast, the notification's "Stop" action is an immutable service PendingIntent with `ACTION_STOP`; delivering it clears `liveCast`, stops the service, and the fake transport records the receiver STOP and channel CLOSE messages being sent.
- Device check: tap Stop on the notification during a WebRTC cast; the TV session ends and the notification disappears.
