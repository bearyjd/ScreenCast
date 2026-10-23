# Follow-ups: TDD notes

## 1. RECORD_AUDIO at launch (done, `fix:`)
- Finding: AudioRecord needs RECORD_AUDIO even for playback capture. The default HLS mode (CastForegroundService) has no per-setting flow and streams silence without it, so the prompt is legitimately needed at launch.
- Change: launch no longer fires the bare system prompt. A one-time rationale dialog ("Share audio?") is shown; the decision is stored in `launch_prompts/mic_rationale_shown` and never re-asked. "Not now" keeps casting working (silent HLS audio; WebRTC audio setting still offers its own request).
- Pure decision: `micLaunchAction()` in `ui/MicLaunchPrompt.kt`; tests `MicLaunchPromptTest` (4).
- RED/GREEN: test and function were written together (compile-RED only); GREEN verified.
- Device check: fresh install shows the dialog once; Continue then the system prompt; relaunch shows nothing; Not now then relaunch shows nothing.

## 2. Receiver-status listener move (skipped)
- No receiver-status listener exists in the local-share path (`LocalMediaForegroundService`, `MediaHandoffController`, `MediaShareViewModel`, `LocalMediaHost`). RECEIVER_STATUS parsing lives only in `CastSession` and `WebRtcCastSession`, tightly bound to session state. Target is unclear, so no behavior-preserving move was attempted.

## 3. Cast device-auth (done, `test:`)
- Finding: `CastChannel` uses a trust-all manager and runs no Cast device-auth; already documented in SECURITY.md and FORK.md. Not implemented here.
- `CastChannelTrustTest` (2): a self-signed TLS server is accepted; the JVM default context still rejects it (trust-all stays scoped). Tests fail if the behavior changes, forcing a deliberate decision.

## 4. Notification Stop action (done, `test:`)
- `WebRtcNotificationStopTest` (1): after a live cast, the notification's "Stop" action is an immutable service PendingIntent with `ACTION_STOP`; delivering it clears `liveCast` and stops the service.
- Device check: tap Stop on the notification during a WebRTC cast; the TV session ends and the notification disappears.
