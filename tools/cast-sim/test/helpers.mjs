// Synthetic "phone casting Chrome" frames: 1920x1080 black frame, a narrow
// pillarboxed content strip (phone portrait scaled to the frame height), a
// toolbar-like noisy band above the page, the barcode scaled by `scale`, then
// Gaussian blur + noise (a stand-in for H.264 at low bitrate).
import { renderBitsRgba, encodeBits, CELLS } from '../lib/barcode.mjs';

export function rng(seed) {
  let s = seed >>> 0;
  const u = () => { s = (Math.imul(s, 1664525) + 1013904223) >>> 0; return s / 2 ** 32; };
  const gauss = () => Math.sqrt(-2 * Math.log(u() || 1e-9)) * Math.cos(2 * Math.PI * u());
  return { u, gauss };
}

function areaScale(gray, w, h, scale) {
  const nw = Math.round(w * scale); const nh = Math.round(h * scale);
  const out = new Float32Array(nw * nh);
  for (let y = 0; y < nh; y++) {
    const y0 = Math.floor(y / scale); const y1 = Math.max(y0 + 1, Math.min(h, Math.floor((y + 1) / scale)));
    for (let x = 0; x < nw; x++) {
      const x0 = Math.floor(x / scale); const x1 = Math.max(x0 + 1, Math.min(w, Math.floor((x + 1) / scale)));
      let sum = 0; let n = 0;
      for (let yy = y0; yy < y1; yy++) for (let xx = x0; xx < x1; xx++) { sum += gray[yy * w + xx]; n++; }
      out[y * nw + x] = sum / n;
    }
  }
  return { data: out, width: nw, height: nh };
}

function blur(img, sigma) {
  if (sigma <= 0) return img;
  const r = Math.ceil(sigma * 3);
  const k = Array.from({ length: 2 * r + 1 }, (_, i) => Math.exp(-((i - r) ** 2) / (2 * sigma * sigma)));
  const sum = k.reduce((a, b) => a + b, 0);
  const { width: w, height: h } = img;
  const tmp = new Float32Array(w * h); const out = new Float32Array(w * h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    let a = 0; for (let i = -r; i <= r; i++) a += k[i + r] * img.data[y * w + Math.min(w - 1, Math.max(0, x + i))];
    tmp[y * w + x] = a / sum;
  }
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    let a = 0; for (let i = -r; i <= r; i++) a += k[i + r] * tmp[Math.min(h - 1, Math.max(0, y + i)) * w + x];
    out[y * w + x] = a / sum;
  }
  return { data: out, width: w, height: h };
}

// Returns RGBA ImageData-like {data,width,height} plus the expected geometry.
export function phoneFrame({ ms, bits = encodeBits(ms), scale = 0.457, sigma = 1.2, noiseSd = 6, seed = 1,
  frameW = 1920, frameH = 1080, toolbarPx = 250, pageCellPx = 20, bg = 0, stripX = null } = {}) {
  const R = rng(seed);
  const PW = 1080; // phone width in px
  const cell = pageCellPx;
  const margin = Math.floor((PW - CELLS * cell) / 2);
  const bar = renderBitsRgba(bits, cell, 400, margin);
  const pageH = 700;
  const phone = new Float32Array(PW * (toolbarPx + pageH)).fill(255);
  // toolbar: light grey with dark "url pill" blobs and icons
  for (let y = 0; y < toolbarPx; y++) for (let x = 0; x < PW; x++) phone[y * PW + x] = 225 + R.u() * 20;
  for (let n = 0; n < 40; n++) {
    const bx = Math.floor(R.u() * (PW - 80)); const by = Math.floor(R.u() * (toolbarPx - 30)); const bw = 20 + Math.floor(R.u() * 200);
    const v = R.u() * 120;
    for (let y = by; y < by + 24; y++) for (let x = bx; x < Math.min(PW, bx + bw); x++) phone[y * PW + x] = v;
  }
  const top = 60; // barcode top margin inside the page
  for (let y = 0; y < bar.height; y++) for (let x = 0; x < bar.width; x++) {
    phone[(toolbarPx + top + y) * PW + x] = bar.data[(y * bar.width + x) * 4];
  }
  const small = areaScale(phone, PW, toolbarPx + pageH, scale);
  const sx = stripX ?? Math.floor((frameW - small.width) / 2);
  const full = new Float32Array(frameW * frameH).fill(bg);
  for (let y = 0; y < Math.min(small.height, frameH); y++) for (let x = 0; x < small.width; x++) full[y * frameW + sx + x] = small.data[y * small.width + x];
  const b = blur({ data: full, width: frameW, height: frameH }, sigma);
  const data = new Uint8ClampedArray(frameW * frameH * 4);
  for (let i = 0; i < frameW * frameH; i++) {
    const v = b.data[i] + R.gauss() * noiseSd;
    data[i * 4] = data[i * 4 + 1] = data[i * 4 + 2] = v; data[i * 4 + 3] = 255;
  }
  return {
    data, width: frameW, height: frameH,
    expect: { x: sx + margin * scale, y: (toolbarPx + top) * scale, w: CELLS * cell * scale, h: 400 * scale, cell: cell * scale },
  };
}

// read(x,y,w,h) -> view over an RGBA frame, like canvas getImageData.
export function readerFor(frame) {
  return (x, y, w, h) => {
    if (x === undefined) return { data: frame.data, width: frame.width, height: frame.height, ox: 0, oy: 0 };
    const data = new Uint8ClampedArray(w * h * 4);
    for (let yy = 0; yy < h; yy++) {
      const s = ((y + yy) * frame.width + x) * 4;
      data.set(frame.data.subarray(s, s + w * 4), yy * w * 4);
    }
    return { data, width: w, height: h, ox: x, oy: y };
  };
}

// WebSocket URL/options for the page link of a running sim (loopback listener + token).
export const wsUrl = sim => `ws://127.0.0.1:${sim.localPort}/ws?t=${sim.token}`;
export const wsOpts = () => ({ headers: {} });
