# cast-sim

Software Chromecast + latency harness for the ScreenCast WebRTC sender. It
advertises a fake Cast device, speaks enough Cast V2 for the Android app to
launch and signal, and runs the project's **unmodified** `receiver/index.html`
+ `receiver/receiver.js` in headless Chromium (the CAF script is swapped for
`page/cast-shim.js` by request interception). It then measures capture-to-
presentation latency from a time barcode drawn on the phone.

> **WARNING: while running it advertises a FAKE Chromecast ("Sim TV (cast-sim)")
> on your LAN. Dev tool only; never run it unattended. Stop it with Ctrl-C.**

## Run (Docker)

```sh
tools/cast-sim/run.sh [--ip 192.168.1.167] [--report-after 60]
# stop: Ctrl-C, or: docker stop cast-sim
```

`run.sh` builds `tools/cast-sim/Dockerfile` (context = repo root) and runs:
`docker run --rm --init --network host --user $UID:$GID --read-only --tmpfs /tmp
--cap-drop ALL --security-opt no-new-privileges --shm-size 512m -v tools/cast-sim/out:/out cast-sim:local`.
`--network host` is required for mDNS (UDP 5353), TCP 8009 and WebRTC UDP.
If docker is wrapped (distrobox): `DOCKER="distrobox-host-exec docker" tools/cast-sim/run.sh`.

Without Docker: `cd tools/cast-sim && npm ci && CHROME_PATH=/usr/bin/google-chrome node server.mjs --out out`.

Servers bind to the auto-detected private LAN IPv4 only (override `--ip`).

Ports and what is reachable from where:

| Listener | Default | Reachable from | Serves |
|---|---|---|---|
| Cast TLS | 8009 (LAN IP) | LAN | Cast V2 (self-signed cert generated at start); at most 4 connections per IP and 4 LAUNCHes per IP per minute (then `LAUNCH_ERROR`), a connection that never LAUNCHes is closed after 30 s, a new LAUNCH closes the connection it takes over, TLS handshake limited to 5 s |
| HTTP (LAN) | 8765 (LAN IP) | LAN | **only** `/clock.html` (and `/`), its assets (`/page/clock.mjs`, `/lib/barcode.mjs`, `/lib/stats.mjs`, `/lib/syncloop.mjs`), `GET /time`, `POST /sync`, `GET /offset` |
| HTTP (loopback) | 8766 (`--local-port`, 127.0.0.1) | the sim host only | everything above plus `/receiver/*`, `/page/*`, `/lib/*`, `GET /report`, `GET /frame.png[?crop=1&overlay=0]`, `POST /reset`, and the page WebSocket `/ws`; `/report`, `/frame.png` and `POST /reset` need the per-run token |

Every request must carry the exact expected `Host` header (otherwise `421`). `/ws` additionally needs a
random per-run token (handed to the headless page in the URL fragment) and, if an `Origin` is sent, a
same-origin one. `/report`, `/frame.png` and `POST /reset` need the same token as header `x-cast-sim-token`
(or `?t=`), compared in constant time (`401` otherwise). `POST /sync` and `POST /reset` require
`Content-Type: application/json`; bodies over 4 KiB get `413`. Other local users or pages on the sim host
therefore cannot read the screen or reset stats without the token, which is printed (as ready-to-paste curl
lines) at startup. The debug routes are deliberately not on the LAN listener. `run.sh` uses host networking, so on the
machine running the sim (`$TOKEN` = the token from the startup output):

```sh
curl -H "x-cast-sim-token: $TOKEN" http://127.0.0.1:8766/report
curl -H "x-cast-sim-token: $TOKEN" -o frame.png 'http://127.0.0.1:8766/frame.png?crop=1'
curl -X POST -H "x-cast-sim-token: $TOKEN" -H 'content-type: application/json' -d '{}' http://127.0.0.1:8766/reset
```
HTTP servers use a 10 s header / 15 s request timeout; the LAN listener accepts at most 64 connections.

`/frame.png` returns the latest presented video frame with the located barcode boxed (`crop=1` crops
around it); `/report` includes the box and decode-failure reasons (not-found / sync-mismatch / checksum /
implausible; while nothing is located only every 3rd frame is searched, the rest count as not-found).
Reports are capped at 1 MiB; report files in `out/` are written at most every 10 s (the final shutdown report is exempt), and once 500 report files exist no new ones are written (nothing is pruned: delete old ones yourself).

## Measure latency

1. Start the sim. 2. On the phone open `http://<LAN-IP>:8765/clock.html`
(the barcode, 48 cells with a quiet zone, always fits the viewport; it also
syncs the phone clock to the sim). The receiver finds the barcode anywhere in the
frame (pillarbox, Chrome toolbar above the page, downscale, blur). 3. In the app pick "Sim TV (cast-sim)" in
WebRTC mode (App ID `9098830C`) and cast the screen. 4. Read the report:
`curl -H "x-cast-sim-token: $TOKEN" http://127.0.0.1:8766/report` (on the sim host), `--report-after N`, on STOP / disconnect, or
Ctrl-C. Reports print as a table and (with `--out`/run.sh) land in `out/*.json`.

The barcode carries Date.now() mod 2^28 ms (CRC-8 + sync + end marker); the full time is recovered against the receiver clock.

Latency = barcode draw -> capture -> encode -> network -> jitter buffer ->
decode -> presentation in headless Chromium. It **excludes the phone's display
latency and any real TV/HDMI/Chromecast hardware delay**, and its accuracy is
bounded by the reported clock-offset uncertainty (min-RTT/2). "to display" uses
`expectedDisplayTime` (includes up to ~1-2 synthetic vsyncs); "at callback" is
the rVFC callback time. Also reported: fps, decode-failure %, jitter-buffer and
decode averages, drops, freezes, codec/resolution, ICE RTT, audio jitter/concealment.

## Tests

```sh
cd tools/cast-sim && npm ci && npm test          # unit + integration (+ e2e if Chromium found)
CHROME_PATH=... node selftest.mjs                # no-phone loopback: Chromium "sender" -> sim -> receiver.js
```

## Limits

Not a general Cast implementation: no DRM, no media/DMR namespace, no device
auth, one app session at a time (multiple connections allowed; the last LAUNCH owns it; connections that never LAUNCH are closed after 30 s), only App ID `9098830C`
launches the WebRTC receiver (others get `LAUNCH_ERROR NOT_FOUND`). Receiver and sim share one host clock. A/V sync
measurement is not implemented (follow-up). Clock sync rejects pings whose phone clock differs from the sim's by more than 1 h
(400, the clock page shows the server's reason) and keeps using the first synced clock page until it is idle for 5 s (the report names it and lists every client's offset). The plausibility check
of decoded barcode times subtracts the synced clock offset. Chromium is the distro package
(unpinned apt); npm deps and the node base image are pinned.

## Known risks / accepted

- **Chromium runs with `--no-sandbox`** (needed inside the container as an unprivileged user without
  user namespaces) and, with `--network host`, shares the host network stack. The headless page only loads
  this sim's own loopback origin (everything else is blocked by request interception), but a Chromium
  renderer exploit would not be contained by Chromium's own sandbox. Mitigations: the container hardening in
  `run.sh` (read-only rootfs, all capabilities dropped, `no-new-privileges`, non-root user) and only running the
  sim attended, on a trusted LAN, while testing. The flag is deliberately unchanged.
- **Clock plausibility (M5):** the receiver and the sim share one host clock; the phone clock is converted with
  the synced offset before the 1 h plausibility check, so a phone clock that is far off no longer causes
  "implausible" decode failures once the clock page has synced. Before the first sync the offset is assumed 0.
- **Wrong device pinned (accepted limitation):** the pinned clock page is the first one with samples, not the one
  whose offset best fits the barcode. A laptop tab left open on `/clock.html` can hold the pin while the phone is
  measured. Mitigation: the clock page status line shows its client id and the report lists every client
  (id, offset, rtt, age); close other clock tabs. Automatic selection by median latency is not implemented.
- **Accepted LAN DoS:** the TLS `maxConnections` (2 x 16) is global rather than per IP, so one LAN host can fill
  the pre-handshake slots for the 5 s handshake timeout; the HTTP header timeout (10 s) is likewise coarse. Both are
  accepted for an attended, trusted-LAN dev tool.
