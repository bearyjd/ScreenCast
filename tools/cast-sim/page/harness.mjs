// Receiver-side latency harness: locates and decodes the phone's time barcode
// in every presented video frame (wherever it sits in the frame) and tracks
// WebRTC stats. Answers report-request / frame-request from the sim server.
import { BarcodeTracker, makeView, unwrap } from '/lib/barcode.mjs';
import { buildReport, summarizeRtcStats, summarize, isPlausible } from '/lib/stats.mjs';

const MAX_SAMPLES = 200000;
const RELOCATE_EVERY = 3; // while no barcode is located, do the (costly) full-frame search every Nth frame
const video = document.getElementById('video');
const canvas = document.createElement('canvas');
const ctx = canvas.getContext('2d', { willReadFrequently: true });
const tracker = new BarcodeTracker();

// Phone->receiver clock offset (server - phone), refreshed from the sim's own /offset.
let offsetMs = 0;
async function refreshOffset() {
  try {
    const o = await (await fetch('/offset', { cache: 'no-store' })).json();
    offsetMs = Number.isFinite(o.offsetMs) ? o.offsetMs : 0;
  } catch { /* keep the last offset */ }
}
refreshOffset();
setInterval(refreshOffset, 2000);

let st;
function reset() {
  st = {
    samples: [], presented: 0, decodeFailures: 0, firstFrameMs: null, lastFrameMs: null, rtc: null, recent: [],
    failReasons: { notFound: 0, syncMismatch: 0, checksum: 0, implausible: 0 }, lastBox: null, lastLow: null, sinceLocate: 0,
  };
  tracker.box = null;
}
reset();

function failure(reason) {
  st.decodeFailures++;
  const k = { 'not-found': 'notFound', 'sync-mismatch': 'syncMismatch', checksum: 'checksum', implausible: 'implausible' }[reason] ?? 'notFound';
  st.failReasons[k]++;
}

// Read a region of the current video frame at native resolution (no scaling).
function readRegion(x, y, w, h) {
  if (x === undefined) { x = 0; y = 0; w = video.videoWidth; h = video.videoHeight; }
  if (canvas.width !== w) canvas.width = w;
  if (canvas.height !== h) canvas.height = h;
  ctx.drawImage(video, x, y, w, h, 0, 0, w, h);
  const img = ctx.getImageData(0, 0, w, h);
  return makeView(img.data, w, h, x, y);
}

function onFrame(now, meta) {
  // Wall clock == sim server clock (same host). `now` is vsync-aligned and can
  // precede the frame's arrival, so: presentation = expectedDisplayTime (falls
  // back to a callback-time read), callback = performance.now() inside the callback.
  const cbMs = performance.timeOrigin + performance.now();
  const recvMs = performance.timeOrigin + (meta && meta.expectedDisplayTime ? meta.expectedDisplayTime : performance.now());
  st.presented++;
  st.firstFrameMs ??= cbMs;
  st.lastFrameMs = cbMs;
  const vw = video.videoWidth;
  const vh = video.videoHeight;
  if (!vw || !vh) failure('not-found');
  else if (!tracker.box && ++st.sinceLocate % RELOCATE_EVERY !== 0) failure('not-found'); // skipped search
  else {
    const r = tracker.decode(readRegion, { width: vw, height: vh });
    if (r.ok) {
      const phoneMs = unwrap(r.low, cbMs - offsetMs);
      st.lastBox = { ...r.box, frameWidth: vw, frameHeight: vh };
      st.lastLow = r.low;
      if (!isPlausible(phoneMs, cbMs, offsetMs)) { tracker.box = null; failure('implausible'); }
      else {
        if (st.samples.length < MAX_SAMPLES) st.samples.push({ recvMs, cbMs, phoneMs });
        st.recent.push(cbMs - phoneMs);
        if (st.recent.length > 120) st.recent.shift();
      }
    } else failure(r.reason);
  }
  video.requestVideoFrameCallback(onFrame);
}
video.requestVideoFrameCallback(onFrame);

async function pollStats() {
  const pc = [...window.__castSimPcs].reverse().find(p => p.connectionState !== 'closed');
  if (!pc) return;
  try {
    const rep = await pc.getStats();
    st.rtc = summarizeRtcStats(Array.from(rep.values()));
  } catch { /* keep the last good snapshot */ }
}
setInterval(pollStats, 500);
window.addEventListener('castsim-newpc', reset);

// PNG of the current frame, optionally with the located barcode boxed, optionally cropped around it.
function framePng({ crop, overlay }) {
  const w = video.videoWidth;
  const h = video.videoHeight;
  if (!w || !h) return null;
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  const g = c.getContext('2d');
  g.drawImage(video, 0, 0);
  const b = st.lastBox;
  if (overlay) {
    g.lineWidth = Math.max(2, Math.round(w / 640));
    g.strokeStyle = b ? '#ff2d2d' : '#ffaa00';
    g.fillStyle = g.strokeStyle;
    g.font = `${Math.max(14, Math.round(h / 45))}px monospace`;
    if (b) {
      g.strokeRect(b.x, b.y, b.w, b.h);
      g.fillText(`barcode ${Math.round(b.w)}x${b.h} cell ${b.cell.toFixed(2)}`, b.x, Math.max(14, b.y - 6));
    } else g.fillText('barcode NOT located', 10, 24);
    g.fillText(`${w}x${h} fails ${JSON.stringify(st.failReasons)}`, 10, h - 10);
  }
  if (crop && b) {
    const pad = b.w * 0.4;
    const x = Math.max(0, Math.floor(b.x - pad)); const y = Math.max(0, Math.floor(b.y - pad));
    const cw = Math.min(w - x, Math.ceil(b.w + 2 * pad)); const ch = Math.min(h - y, Math.ceil(b.h + 2 * pad));
    const o = document.createElement('canvas');
    o.width = cw; o.height = ch;
    o.getContext('2d').drawImage(c, x, y, cw, ch, 0, 0, cw, ch);
    return o.toDataURL('image/png');
  }
  return c.toDataURL('image/png');
}

window.__castSim.onMessage(async m => {
  if (m.kind === 'reset') reset();
  else if (m.kind === 'report-request') {
    await pollStats();
    const decode = { box: st.lastBox, failReasons: st.failReasons, frameWidth: video.videoWidth || st.lastBox?.frameWidth, frameHeight: video.videoHeight || st.lastBox?.frameHeight };
    window.__castSim.send({ kind: 'report', id: m.id, report: buildReport({ ...st, offset: m.offset, decode, decodeFailures: st.decodeFailures }) });
  } else if (m.kind === 'frame-request') {
    window.__castSim.send({ kind: 'frame', id: m.id, dataUrl: framePng(m) });
  }
});

let lastPresented = 0;
setInterval(() => {
  if (!st.presented) return;
  const fps = Math.max(0, st.presented - lastPresented) / 2;
  lastPresented = st.presented;
  const s = summarize(st.recent);
  const v = st.rtc?.video;
  const f = st.failReasons;
  window.__castSim.send({
    kind: 'live',
    live: `${fps.toFixed(1)} fps, raw(unsynced) age p50 ${s.p50?.toFixed(0) ?? 'n/a'} ms, decode-fail ${st.decodeFailures}/${st.presented} `
      + `(nf ${f.notFound} sync ${f.syncMismatch} crc ${f.checksum} impl ${f.implausible}), box ${st.lastBox ? 'yes' : 'NO'}, `
      + `jb ${v?.jitterBufferDelayAvgMs?.toFixed(0) ?? 'n/a'} ms ${v?.codec ?? ''} ${v?.width ?? ''}x${v?.height ?? ''}`,
  });
}, 2000);
