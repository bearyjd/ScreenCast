# Mute phone while casting - TDD evidence

Setting `muteWhileCasting` (default OFF), WebRTC mode only. STREAM_MUSIC only; no ringer/DND.

## Mechanism
Android keeps a separate STREAM_MUSIC volume per output device, so "set 0 / restore number" can strand
the speaker muted, restore onto the wrong device, or undo a deliberate mute. Instead:
- mute: `adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE, 0)`; restore: `ADJUST_UNMUTE` + `FLAG_SHOW_UI`,
  and only if `isStreamMute(STREAM_MUSIC)` is still true.
- Already muted at cast start: nothing done, no marker (the user's mute stays). `isVolumeFixed`: skipped (logged).
- Marker (sessionId, timestamp) is written with `commit()` before muting and cleared with `commit()`;
  a failed marker write aborts the mute. A marker with no muted stream is just cleared.
- One process-wide `MediaMuteGuards.get(ctx)`; every public method is synchronized on one lock.
- Mute happens when the session reaches Casting (a failed connect never silences the phone), with a
  `stillWanted` check under the lock so a stop that raced ahead cannot be followed by a mute.
- Recovery (`recover { liveCast }`, cast state re-checked under the lock before unmuting) runs from the
  service `onCreate` and the ViewModel init, off the main thread. There is no Application subclass.

## RED / GREEN (fix round)
RED: tests rewritten first; test compile failed (`isVolumeFixed`/`isMuted` override nothing, `recover`,
`MuteMarker(sessionId, ts)` unresolved). GREEN: 462 unit tests, 0 failures. Removing the guard lock
(fresh lock per call) fails the concurrency test.

## Verified by unit tests
Guard: mute/restore, marker-before-mute, already-muted no-op, user unmuted mid-cast untouched, volume 0
without mute flag untouched, fixed volume skipped, stale marker + muted + idle -> unmute, stale marker +
not muted -> clear only, recover skipped while live, unmute/isMuted/clear/mute failures leave marker and
state consistent, ignored mute clears marker, lock prevents mute/restore interleaving.
Store: commit (never apply) for write and clear.
Service wiring (Robolectric, `WebRtcServiceMuteWiringTest`): setting on -> mute at Casting only; setting
off -> never touched; stopCast -> restore; terminal error -> restore; `close()` throwing -> restore still
runs; late CONNECTED after stop -> not left muted; onCreate -> stale mute recovered.

## Device-only checks (not verifiable here)
1. FIRST: cast with the phone muted via ADJUST_MUTE and confirm cast audio is STILL AUDIBLE ON THE TV.
   Playback capture may or may not be affected by a muted stream; if it is, this setting must be reworded
   or removed.
2. Speaker, wired and Bluetooth outputs: mute applies, unmute restores, switching output mid-cast is sane.
3. Unmute with the volume key mid-cast, then stop: stays unmuted. Mute manually before casting: stays muted.
4. Force-stop mid-cast, relaunch: phone is unmuted.
5. GrapheneOS with DND on; setting off never touches audio.

## Known limits
- `restore()` on stop runs on the calling thread (small SharedPreferences commit).
- Hardware volume keys are not intercepted.
