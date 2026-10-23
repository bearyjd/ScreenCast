#!/usr/bin/env bash
# Build (cached) and run cast-sim. DEV TOOL: while running, this advertises a FAKE
# Chromecast ("Sim TV (cast-sim)") on your LAN. Never run unattended. Stop: Ctrl-C.
#
#   tools/cast-sim/run.sh [server.mjs flags, e.g. --ip 192.168.1.167 --report-after 60]
#
# --network host is required: mDNS multicast (UDP 5353), TCP 8009 (Cast TLS),
# TCP 8765 (clock page) + 127.0.0.1:8766 (reports, debug) and the WebRTC UDP media ports.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
IMAGE="${CAST_SIM_IMAGE:-cast-sim:local}"
NAME="${CAST_SIM_CONTAINER:-cast-sim}"
OUT="${CAST_SIM_OUT_DIR:-$HERE/out}"
# DOCKER may be several words, e.g. DOCKER="distrobox-host-exec docker".
read -ra DOCKER <<<"${DOCKER:-docker}"

mkdir -p "$OUT"
"${DOCKER[@]}" build -q -f "$HERE/Dockerfile" -t "$IMAGE" "$ROOT" >/dev/null

echo "WARNING: advertising a fake Chromecast on the LAN until you stop this (Ctrl-C). Reports -> $OUT" >&2
# --init: reap Chromium children, forward signals. Read-only rootfs, no
# capabilities, no privilege escalation; Chromium runs with --no-sandbox INSIDE
# this container boundary (no user namespaces available), so keep it LAN-only.
exec "${DOCKER[@]}" run --rm --init --name "$NAME" \
  --network host \
  --user "$(id -u):$(id -g)" \
  --read-only --tmpfs /tmp:rw,size=512m,exec \
  --cap-drop ALL --security-opt no-new-privileges \
  --shm-size 512m \
  -v "$OUT:/out" \
  "$IMAGE" "$@"
