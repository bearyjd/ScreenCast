# Mute phone while casting - TDD evidence

Setting `muteWhileCasting` (default OFF), WebRTC mode only. STREAM_MUSIC volume only; no ringer/DND.

## RED
Tests `MediaMuteGuardTest` (14) and `MuteWhileCastingStoreTest` (3) written first; test compile failed
(`Unresolved reference MediaVolumeControl / MuteMarker / MuteMarkerStore / PrefsMuteMarkerStore`).

## GREEN
`MediaMuteGuard` + `PrefsMuteMarkerStore` + `AudioManagerVolume` (webrtc/MediaMuteGuard.kt), pref in
`WebRtcConfigStore`, VM/UI row, service wiring. Full suite: 448 tests, 0 failures.
`testDebugUnitTest assembleDebug lintDebug`: BUILD SUCCESSFUL, lint 0 errors.

Covered: mute+restore, marker written before volume change, user-changed volume not clobbered, crash recovery
with fresh guard, no marker no-op, idempotent restore, already-silent writes no marker, stale marker restored
before new mute, marker-write / set / get failures logged and never thrown, corrupt marker discarded,
recoverIfIdle skips while casting, pref default/persistence, marker store round trip.

## Wiring
Service: mute after session creation if snapshot.muteWhileCasting; restore in stopCast and
finishTerminalSession (ERROR/Idle); recoverIfIdle in service onCreate; also in WebRtcViewModel init.

## Residual risks
- Service wiring itself has no unit test (no existing service harness for AudioManager); covered by guard tests only.
- If the user manually sets volume to exactly 0 mid-cast, it is indistinguishable and gets restored.
- Android may show volume UI / block setStreamVolume under DND on some devices (SecurityException is logged, cast continues).
- Marker is only recovered when the app or service next starts; a crashed process leaves the phone muted until then.
- Hardware volume keys during cast change the volume; not intercepted.

## Device checks needed
1. Enable setting, cast: phone media volume drops to 0, TV audio unaffected; stop: original restored.
2. Change volume mid-cast, stop: volume stays as set.
3. Force-stop app mid-cast, relaunch app: volume restored.
4. Setting off: volume never touched. Ringer/DND unchanged throughout.
5. Test on GrapheneOS with DND on.
