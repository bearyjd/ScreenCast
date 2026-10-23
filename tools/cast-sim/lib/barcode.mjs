// Time barcode, 48 cells, 1 = black:
//   8 sync (10110010) | 28 data = Date.now() mod 2^28 ms (MSB first) | 8 CRC-8 | 4 end marker (0101)
// The first black cell's left edge and the last black cell's right edge span
// exactly 48 cells, which gives the cell width precisely. The barcode is
// located anywhere in an arbitrary frame (pillarbox/letterbox bars, toolbars,
// downscale, blur): see locate(). Isomorphic (browser + node), no imports.

export const CELLS = 48;
export const SYNC = [1, 0, 1, 1, 0, 0, 1, 0];
export const MARKER = [0, 1, 0, 1];
export const DATA_BITS = 28;
export const MOD = 2 ** DATA_BITS;
const MIN_CONTRAST = 60;
const MIN_CELL_PX = 3;
const MAX_CROSSINGS = 400;

export function crc8(bytes) {
  let crc = 0;
  for (const b of bytes) {
    crc ^= b;
    for (let i = 0; i < 8; i++) crc = (crc & 0x80) ? ((crc << 1) ^ 0x07) & 0xff : (crc << 1) & 0xff;
  }
  return crc;
}

function valueBytes(v) {
  return [(v >>> 24) & 0xff, (v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff];
}

export function encodeBits(ms) {
  if (!Number.isFinite(ms) || ms < 0) throw new RangeError('bad time');
  const low = Math.floor(ms) % MOD;
  const bits = [...SYNC];
  for (let i = DATA_BITS - 1; i >= 0; i--) bits.push(Math.floor(low / 2 ** i) % 2);
  const crc = crc8(valueBytes(low));
  for (let i = 7; i >= 0; i--) bits.push((crc >> i) & 1);
  bits.push(...MARKER);
  return bits;
}

// -> {low} or {reason: 'sync-mismatch' | 'checksum'}
export function decodeBits(bits) {
  if (bits.length !== CELLS) return { reason: 'sync-mismatch' };
  for (let i = 0; i < SYNC.length; i++) if (bits[i] !== SYNC[i]) return { reason: 'sync-mismatch' };
  for (let i = 0; i < MARKER.length; i++) if (bits[CELLS - MARKER.length + i] !== MARKER[i]) return { reason: 'sync-mismatch' };
  let low = 0;
  for (let i = 0; i < DATA_BITS; i++) low = low * 2 + bits[SYNC.length + i];
  let crc = 0;
  for (let i = 0; i < 8; i++) crc = (crc << 1) | bits[SYNC.length + DATA_BITS + i];
  if (crc8(valueBytes(low)) !== crc) return { reason: 'checksum' };
  return { low };
}

// Full time from the 28-bit value, given a reference within +-37 h of it.
export function unwrap(low, refMs) {
  const ref = Math.round(refMs);
  const d = (((low - ref) % MOD) + MOD) % MOD;
  return ref + (d >= MOD / 2 ? d - MOD : d);
}

// ---- image access: view = {data: RGBA, width, height, ox, oy} (ox/oy = frame coords of data[0]) ----
export const makeView = (data, width, height, ox = 0, oy = 0) => ({ data, width, height, ox, oy });
const lum = (v, x, y) => {
  const i = ((y - v.oy) * v.width + (x - v.ox)) * 4;
  return (v.data[i] * 77 + v.data[i + 1] * 150 + v.data[i + 2] * 29) >> 8;
};
const inView = (v, x, y) => x >= v.ox && y >= v.oy && x < v.ox + v.width && y < v.oy + v.height;

// Mean luminance of each cell centre (3 rows x ~u/2 columns). null if outside the view.
function sampleCells(v, x0, u, y) {
  if (!inView(v, Math.floor(x0), y - 1) || !inView(v, Math.ceil(x0 + CELLS * u) - 1, y + 1)) return null;
  const hw = Math.max(0, Math.floor(u / 4));
  const vals = new Array(CELLS);
  for (let c = 0; c < CELLS; c++) {
    const cx = Math.round(x0 + (c + 0.5) * u);
    let sum = 0;
    let n = 0;
    for (let yy = y - 1; yy <= y + 1; yy++) for (let xx = cx - hw; xx <= cx + hw; xx++) { sum += lum(v, xx, yy); n++; }
    vals[c] = sum / n;
  }
  return vals;
}

// Adaptive threshold from the known sync/marker cells (so dim/blurred codes still work).
function cellsToBits(vals) {
  const known = [...SYNC, ...Array(CELLS - SYNC.length - MARKER.length).fill(-1), ...MARKER];
  let b = 0; let nb = 0; let w = 0; let nw = 0;
  known.forEach((k, i) => { if (k === 1) { b += vals[i]; nb++; } else if (k === 0) { w += vals[i]; nw++; } });
  const black = b / nb;
  const white = w / nw;
  if (white - black < MIN_CONTRAST) return null;
  const mid = (black + white) / 2;
  return vals.map(v => (v < mid ? 1 : 0));
}

function decodeAt(v, x0, u, y) {
  const vals = sampleCells(v, x0, u, y);
  if (!vals) return { reason: 'not-found' };
  const bits = cellsToBits(vals);
  if (!bits) return { reason: 'not-found' };
  return decodeBits(bits);
}

const RANK = { 'not-found': 0, 'sync-mismatch': 1, checksum: 2 };
const worse = (a, b) => (RANK[b] > RANK[a] ? b : a);

// Known geometry (box from locate()): try a few rows through the barcode.
export function decodeAtBox(v, box) {
  let reason = 'not-found';
  for (const f of [0.5, 0.25, 0.75, 0.4, 0.6]) {
    const y = Math.round(box.y + box.h * f);
    const r = decodeAt(v, box.x, box.cell, y);
    if (r.low !== undefined) return { ok: true, low: r.low, box };
    reason = worse(reason, r.reason);
  }
  return { ok: false, reason };
}

// Search a whole frame view for the barcode. Returns {ok, low, box} or {ok:false, reason}.
export function locate(v, { step = 3 } = {}) {
  let reason = 'not-found';
  const W = v.width;
  const row = new Float32Array(W);
  for (let y = v.oy + 1; y < v.oy + v.height - 1; y += step) {
    let lo = 255; let hi = 0;
    for (let x = 0; x < W; x++) {
      const val = lum(v, v.ox + x, y);
      row[x] = val;
      if (val < lo) lo = val;
      if (val > hi) hi = val;
    }
    if (hi - lo < MIN_CONTRAST) continue;
    const cx = []; // crossing positions (frame coords) and directions
    const cd = []; // true = falling (white -> black)
    for (let x = 1; x < W; x++) {
      const a = row[x - 1]; const b = row[x];
      if ((a >= 128) !== (b >= 128)) {
        cx.push(v.ox + x - 1 + (128 - a) / (b - a));
        cd.push(b < 128);
        if (cx.length > MAX_CROSSINGS) break;
      }
    }
    if (cx.length > MAX_CROSSINGS) continue;
    for (let i = 0; i + 5 < cx.length; i++) {
      if (!cd[i]) continue;
      const u0 = (cx[i + 5] - cx[i]) / 7;
      if (u0 < MIN_CELL_PX) continue;
      const r = k => cx[i + k + 1] - cx[i + k];
      if (Math.abs(r(0) - u0) > 0.35 * u0 || Math.abs(r(1) - u0) > 0.35 * u0
        || Math.abs(r(2) - 2 * u0) > 0.5 * u0 || Math.abs(r(3) - 2 * u0) > 0.5 * u0 || Math.abs(r(4) - u0) > 0.35 * u0) continue;
      const x0 = cx[i];
      if (i > 0 ? x0 - cx[i - 1] < 1.5 * u0 : x0 - v.ox < 1.5 * u0) continue; // quiet zone
      let j = -1;
      for (let k = i + 6; k < cx.length && cx[k] <= x0 + 49.7 * u0; k++) {
        if (!cd[k] && cx[k] >= x0 + 46.3 * u0) j = k;
      }
      if (j < 0) continue;
      if (j + 1 < cx.length && cx[j + 1] - cx[j] < 1.3 * u0) continue; // must be followed by quiet zone
      const u = (cx[j] - x0) / CELLS;
      if (Math.abs(u / u0 - 1) > 0.08) continue;
      const res = decodeAt(v, x0, u, y);
      if (res.low === undefined) { reason = worse(reason, res.reason); continue; }
      const same = yy => { const q = decodeAt(v, x0, u, yy); return q.low === res.low; };
      let top = y; let bottom = y;
      while (top - 1 > v.oy && same(top - 1)) top--;
      while (bottom + 1 < v.oy + v.height - 1 && same(bottom + 1)) bottom++;
      return { ok: true, low: res.low, box: { x: x0, y: top, w: CELLS * u, h: bottom - top + 1, cell: u } };
    }
  }
  return { ok: false, reason };
}

// Stateful decoder: remembers the located box so most frames only need a small region read.
// read(x, y, w, h) -> view; read() -> the whole frame.
export class BarcodeTracker {
  constructor() { this.box = null; }
  decode(read, frameSize) {
    if (this.box) {
      const b = this.box;
      const x = Math.max(0, Math.floor(b.x) - 2);
      const y = Math.max(0, b.y - 2);
      const w = Math.min(frameSize.width - x, Math.ceil(b.w) + 6);
      const h = Math.min(frameSize.height - y, b.h + 5);
      const r = decodeAtBox(read(x, y, w, h), b);
      if (r.ok) return { ...r, via: 'box' };
      this.box = null;
    }
    const r = locate(read());
    if (r.ok) this.box = r.box;
    return { ...r, via: 'locate' };
  }
}

// ---- test/utility renderer: ideal barcode on white, cell width `cell` px, `margin` px quiet zone ----
export const renderBarcodeRgba = (ms, cell, height, margin) => renderBitsRgba(encodeBits(ms), cell, height, margin);
export function renderBitsRgba(bits, cell, height, margin) {
  const width = CELLS * cell + 2 * margin;
  const out = new Uint8ClampedArray(width * height * 4).fill(255);
  for (let y = 0; y < height; y++) {
    for (let c = 0; c < CELLS; c++) {
      if (!bits[c]) continue;
      for (let x = 0; x < cell; x++) {
        const i = (y * width + margin + c * cell + x) * 4;
        out[i] = out[i + 1] = out[i + 2] = 0;
      }
    }
  }
  return { data: out, width, height };
}
