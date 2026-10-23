# Mute phone while casting - TDD evidence

Setting `muteWhileCasting` (default OFF), WebRTC mode only. STREAM_MUSIC only; no ringer/DND.

## Mechanism
Android keeps a separate STREAM_MUSIC volume per output device, so "set 0 / restore number" can strand
the speaker muted, restore onto the wrong device, or undo a deliberate mute. Instead:
- mute: `adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE, 0)`; restore: `ADJUST_UNMUTE` + `FLAG_SHOW_UI`,
  and only if `isStreamMute(STREAM_MUSIC)` is still true.
- Already muted at cast start: nothing done, no marker (the user's mute stays). `isVolumeFixed`: skipped (logged).
- Marker (sessionId, Settings.Global.BOOT_COUNT, elapsedRealtime) is written with `commit()` before muting and cleared with `commit()`;
  a failed marker write aborts the mute. A marker with no muted stream is just cleared.
- One process-wide `MediaMuteGuards.get(ctx)`; every public method is synchronized on one lock.
- Mute happens when the session reaches Casting (a failed connect never silences the phone), with a
  `stillWanted` check under the lock so a stop that raced ahead cannot be followed by a mute.
- Recovery unmutes only a FRESH marker: same boot count and 0 <= elapsed <= 6 h. Other boot, expired,
  negative/unreadable elapsed, unreadable boot count or an old-format marker -> marker cleared, NO unmute
  (so a stale marker can never undo a deliberate later user mute).
- After a mute attempt `isMuted()` is re-read: marker cleared only if it reads false; kept if it reads
  true or the re-read throws.
- Stop restores on the service worker scope; a following mute first joins that restore job. `onDestroy`
  does a bounded (2 s) blocking restore. Mute and unmute both pass FLAG_SHOW_UI.
- Recovery (`recover { liveCast }`, cast state re-checked under the lock before unmuting) runs from the
  service `onCreate` and the ViewModel init, off the main thread. There is no Application subclass.

## RED / GREEN (final fix round)
RED: tests first (compile failure: `bootCount`, 3-arg `MuteMarker`). GREEN: 480 unit tests, 0 failures; assembleDebug and lintDebug pass.
Mutation spot-checks: `stillWanted` -> `{ true }` fails the lock-race wiring test; `recover { liveCast }` ->
`recover { false }` fails the live-cast recovery test.

## Verified by unit tests
Guard: mute/restore, marker-before-mute, already-muted no-op, user unmuted mid-cast untouched, volume 0
without mute flag untouched, fixed volume skipped, stale marker + muted + idle -> unmute, stale marker +
not muted -> clear only, recover skipped while live, unmute/isMuted/clear/mute failures leave marker and
state consistent, ignored mute clears marker, mute throwing after taking effect keeps marker, failing
verify read keeps marker, other-boot / expired / clock-regression / old-format / unreadable-boot markers
clear only, 6 h boundary still unmutes, lock prevents mute/restore interleaving.
Store: commit (never apply) for write and clear.
Service wiring (Robolectric, `WebRtcServiceMuteWiringTest`): setting on -> mute at Casting only; setting
off -> never touched; stopCast -> restore; terminal error -> restore; `close()` throwing -> restore still
runs; late CONNECTED after stop -> not left muted; onCreate -> fresh stale-session mute recovered; liveCast=true -> not unmuted; stop racing a Casting event
parked on the lock -> no mute; stop then immediate restart -> mute, unmute, mute; onDestroy restores
synchronously.

## Device-only checks (not verifiable here)
1. BLOCKING PREMISE CHECK, do first: cast with the phone muted via ADJUST_MUTE and confirm cast audio is STILL AUDIBLE ON THE TV.
   Playback capture may or may not be affected by a muted stream; if it is, this setting must be reworded
   or removed.
1b. After `ADJUST_MUTE`, `isStreamMute(STREAM_MUSIC)` must be true immediately (the verify read relies on it;
   if not, the marker is cleared and the feature silently does nothing).
2. Speaker, wired and Bluetooth outputs: mute applies, unmute restores, switching output mid-cast is sane.
3. Unmute with the volume key mid-cast, then stop: stays unmuted. Mute manually before casting: stays muted.
4. Force-stop mid-cast, relaunch: phone is unmuted.
5. GrapheneOS with DND on; setting off never touches audio.

## Known limits
- Crash recovery runs only when the service or the app (ViewModel) next starts; until then the phone stays muted.
- Marker expiry: recovery ignores markers from another boot, older than 6 h, with a negative elapsed time,
  or in the old format (they are cleared, never unmuted). A normal stop always restores regardless of age.
- Unmuting a stream whose volume is 0 may raise it to the lowest audible step.
- If the user mutes again mid-cast (after unmuting), the stop-time restore sees a muted stream and unmutes it.
- Mutes all STREAM_MUSIC audio incl. navigation voice and text-to-speech; hardware volume keys are not intercepted.
- Stop-time restore is asynchronous (worker scope); only `onDestroy` is a bounded blocking restore.
