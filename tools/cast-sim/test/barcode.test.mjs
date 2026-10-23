import test from 'node:test';
import assert from 'node:assert/strict';
import {
  encodeBits, decodeBits, crc8, unwrap, locate, decodeAtBox, BarcodeTracker, makeView, renderBarcodeRgba, CELLS, MOD,
} from '../lib/barcode.mjs';
import { phoneFrame, readerFor, rng } from './helpers.mjs';

const T = 1_780_000_123_456;
const view = f => makeView(f.data, f.width, f.height);

test('bits round trip; sync, marker and CRC are enforced with distinct reasons', () => {
  const bits = encodeBits(T);
  assert.equal(bits.length, CELLS);
  assert.equal(decodeBits(bits).low, T % MOD);
  const flipData = bits.slice(); flipData[20] ^= 1;
  assert.equal(decodeBits(flipData).reason, 'checksum');
  const flipSync = bits.slice(); flipSync[0] ^= 1;
  assert.equal(decodeBits(flipSync).reason, 'sync-mismatch');
  const flipMarker = bits.slice(); flipMarker[CELLS - 1] ^= 1;
  assert.equal(decodeBits(flipMarker).reason, 'sync-mismatch');
  assert.equal(crc8(Buffer.from('123456789')), 0xf4);
  assert.throws(() => encodeBits(-1));
});

test('unwrap recovers the full time from the 28-bit value across the wrap', () => {
  for (const delta of [-5000, -1, 0, 3, 250, 60_000]) {
    for (const ref of [T, Math.floor(T / MOD) * MOD + 5, Math.floor(T / MOD) * MOD + MOD - 5]) {
      const truth = ref + delta;
      assert.equal(unwrap(truth % MOD, ref), truth);
    }
  }
});

test('ideal barcode in a plain image is located (no bars, no toolbar)', () => {
  const img = renderBarcodeRgba(T, 10, 60, 40);
  const r = locate(makeView(img.data, img.width, img.height));
  assert.equal(r.ok, true);
  assert.equal(r.low, T % MOD);
  assert.ok(Math.abs(r.box.x - 40) < 1.5 && Math.abs(r.box.cell - 10) < 0.1);
});

test('device-like frame: 1920x1080, 493px pillarboxed strip, toolbar noise, 0.457 scale, blur + noise', () => {
  for (const seed of [1, 2, 3, 4, 5]) {
    const ms = T + seed * 7919;
    const f = phoneFrame({ ms, seed });
    const r = locate(view(f));
    assert.equal(r.ok, true, `seed ${seed}: ${r.reason}`);
    assert.equal(r.low, ms % MOD);
    assert.ok(Math.abs(r.box.x - f.expect.x) < 3, `x ${r.box.x} vs ${f.expect.x}`);
    assert.ok(Math.abs(r.box.cell - f.expect.cell) < 0.25, `cell ${r.box.cell} vs ${f.expect.cell}`);
    assert.ok(Math.abs(r.box.y - f.expect.y) < 10);
  }
});

test('works across downscale 0.3-1.0, heavier blur, off-centre strip and letterbox-like offsets', () => {
  for (const scale of [0.3, 0.4, 0.457, 0.6, 0.8, 1.0]) {
    const f = phoneFrame({ ms: T, scale, seed: 11, sigma: scale < 0.4 ? 0.8 : 1.5, stripX: scale === 1 ? 300 : null });
    const r = locate(view(f));
    assert.equal(r.ok, true, `scale ${scale}: ${r.reason}`);
    assert.equal(r.low, T % MOD);
  }
  const heavy = phoneFrame({ ms: T, sigma: 2.0, noiseSd: 12, seed: 9 });
  assert.equal(locate(view(heavy)).ok, true);
  const lightBg = phoneFrame({ ms: T, bg: 40, seed: 3 });
  assert.equal(locate(view(lightBg)).low, T % MOD);
});

test('tracker locates once, then uses region reads (and re-locates after a move)', () => {
  const tr = new BarcodeTracker();
  const f1 = phoneFrame({ ms: T, seed: 1 });
  const size = { width: f1.width, height: f1.height };
  const r1 = tr.decode(readerFor(f1), size);
  assert.deepEqual([r1.ok, r1.via], [true, 'locate']);
  for (let i = 1; i <= 5; i++) {
    const f = phoneFrame({ ms: T + i * 33, seed: 1 + i });
    const r = tr.decode(readerFor(f), size);
    assert.deepEqual([r.ok, r.via, r.low], [true, 'box', (T + i * 33) % MOD]);
  }
  const moved = phoneFrame({ ms: T + 999, seed: 20, stripX: 200 });
  const r = tr.decode(readerFor(moved), size);
  assert.deepEqual([r.ok, r.via], [true, 'locate']);
});

test('rejects non-barcode frames with reasons; never false-decodes noise', () => {
  const black = { data: new Uint8ClampedArray(640 * 360 * 4).fill(0), width: 640, height: 360 };
  assert.deepEqual(locate(view(black)), { ok: false, reason: 'not-found' });
  const R = rng(42);
  let falsePositives = 0;
  for (let n = 0; n < 300; n++) {
    const d = new Uint8ClampedArray(400 * 80 * 4);
    for (let i = 0; i < d.length; i += 4) d[i] = d[i + 1] = d[i + 2] = R.u() * 255, d[i + 3] = 255;
    if (locate(makeView(d, 400, 80)).ok) falsePositives++;
  }
  assert.equal(falsePositives, 0);
  // toolbar-only frame (no barcode)
  const f = phoneFrame({ ms: T, seed: 4 });
  for (let y = 120; y < f.height; y++) f.data.fill(255, y * f.width * 4, (y + 1) * f.width * 4); // erase page below the toolbar
  assert.equal(locate(view(f)).ok, false);
});

test('a corrupted code is reported as checksum / sync-mismatch, not decoded', () => {
  const bits = encodeBits(T);
  const bad = bits.slice(); bad[15] ^= 1;
  const rc = locate(view(phoneFrame({ ms: T, bits: bad, seed: 6 })));
  assert.deepEqual([rc.ok, rc.reason], [false, 'checksum']);
  const badSync = bits.slice(); badSync[3] ^= 1; badSync[4] ^= 1; // keeps the run pattern shape check likely, breaks sync
  const rs = locate(view(phoneFrame({ ms: T, bits: badSync, seed: 6 })));
  assert.equal(rs.ok, false);
  const img = renderBarcodeRgba(T, 10, 40, 30);
  const box = decodeAtBox(makeView(img.data, img.width, img.height), { x: 30, y: 5, w: 480, h: 30, cell: 10 });
  assert.equal(box.ok, true);
});
