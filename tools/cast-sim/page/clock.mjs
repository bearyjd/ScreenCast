// Stopwatch/barcode page for the phone (or any sender). Every animation frame
// draws a 48-cell barcode of Date.now() (ms) across the top plus the digits as
// text, and in the background does NTP-style clock sync against the sim server
// (POST /sync) so the harness can convert phone time to server time.
import { encodeBits, CELLS } from '/lib/barcode.mjs';
import { createSyncClient, syncStatus, startSyncLoop } from '/lib/syncloop.mjs';

const canvas = document.getElementById('c');
const ctx = canvas.getContext('2d', { alpha: false });
const client = Math.random().toString(36).slice(2, 12);
let syncInfo = 'sync: starting';
let frames = 0;
let layout = null;

// The barcode must lie fully inside the visible viewport at any width: size
// everything from the smallest of the layout/visual viewport, in device pixels
// (capped at 2x to keep the per-frame fill cheap), with >= 3 cells of white
// quiet zone on each side.
function resize() {
  const vv = window.visualViewport;
  const cssW = Math.floor(Math.min(window.innerWidth, document.documentElement.clientWidth || Infinity, vv ? vv.width : Infinity));
  const cssH = Math.floor(Math.min(window.innerHeight, document.documentElement.clientHeight || Infinity, vv ? vv.height : Infinity));
  const scale = Math.min(window.devicePixelRatio || 1, 2);
  const W = Math.max(CELLS * 2 + 12, Math.round(cssW * scale));
  const H = Math.max(120, Math.round(cssH * scale));
  canvas.width = W;
  canvas.height = H;
  const cell = Math.max(2, Math.floor(W / (CELLS + 6)));
  const barW = cell * CELLS;
  layout = {
    W, H, cell, x0: Math.floor((W - barW) / 2),
    y0: Math.max(cell * 3, Math.round(H * 0.03)), // white margin above the barcode
    barH: Math.max(40, Math.round(H * 0.26)), // as tall as practical
  };
}
resize();
addEventListener('resize', resize);
window.visualViewport?.addEventListener('resize', resize);

// Draw `text` centred at y, shrinking the font so it always fits within maxW.
function fitText(text, y, maxW, size, weight = '') {
  ctx.font = `${weight} ${size}px monospace`;
  const w = ctx.measureText(text).width;
  if (w > maxW) ctx.font = `${weight} ${Math.max(8, size * (maxW / w))}px monospace`;
  ctx.fillText(text, layout.W / 2, y);
}

function draw() {
  const t = Date.now(); // sampled once per frame; this is what the barcode encodes
  const { W, H, cell, x0, y0, barH } = layout;
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, W, H);
  const bits = encodeBits(t);
  ctx.fillStyle = '#000';
  for (let i = 0; i < CELLS; i++) if (bits[i]) ctx.fillRect(x0 + i * cell, y0, cell, barH);
  const d = new Date(t);
  const pad = (n, l = 2) => String(n).padStart(l, '0');
  const text = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
  const maxW = W - 2 * cell * 3;
  ctx.fillStyle = '#000';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  const below = y0 + barH;
  const rest = H - below;
  fitText(text, below + rest * 0.25, maxW, rest * 0.2, 'bold');
  fitText(`${t}  #${frames++}`, below + rest * 0.5, maxW, rest * 0.07);
  fitText(syncInfo, below + rest * 0.65, maxW, rest * 0.06);
  requestAnimationFrame(draw);
}
requestAnimationFrame(draw);

// --- clock sync -------------------------------------------------------------
const syncClient = createSyncClient({ client });
async function syncOnce() {
  try {
    const r = await syncClient.once();
    if (r) syncInfo = syncStatus(client, r.best, r.n);
  } catch (e) {
    syncInfo = `sync error: ${e.message}`;
  }
}
// Fast burst at start to converge quickly, then every 500 ms to track drift;
// each run starts only after the previous one finished or timed out.
startSyncLoop({ once: syncOnce, onError: e => console.error('sync loop', e) });
