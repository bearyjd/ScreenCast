// Fix set 3: connection/report limits, error isolation, log hygiene.
import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { mkdtempSync, readdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';
import { createSim } from '../lib/sim.mjs';
import { FakeSender } from '../lib/fakesender.mjs';
import { NS } from '../lib/receiver.mjs';
import { wsUrl, wsOpts } from './helpers.mjs';

const receiverDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../receiver');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const T = { timeout: 15000 };

const REPORT = { frames: { presented: 3, decoded: 3, fps: 30, decodeFailures: 0, decodeFailurePct: 0 },
  latencyMs: { count: 3, min: 1, p50: 2, p90: 3, p99: 3, max: 3, mean: 2 }, durationS: 1, webrtc: null };

async function boot(extra = {}, reportFor = () => REPORT) {
  const logs = [];
  const sim = await createSim({ bind: '127.0.0.1', castPort: 0, httpPort: 0, receiverDir, mdns: false,
    log: (...a) => logs.push(a), print: () => {}, ...extra });
  const ws = new WebSocket(wsUrl(sim), wsOpts(sim));
  await new Promise((res, rej) => { ws.once('open', res); ws.once('error', rej); });
  ws.on('message', raw => {
    const m = JSON.parse(raw);
    if (m.kind === 'report-request') ws.send(JSON.stringify({ kind: 'report', id: m.id, report: reportFor(m) }));
  });
  ws.send(JSON.stringify({ kind: 'ready' }));
  while (!sim.bridge.ready) await sleep(5);
  return { sim, ws, logs };
}
const closed = s => new Promise(r => { if (s.socket.destroyed) r(); else s.once('close', r); });

test('a connection that never LAUNCHes is closed at the hard lifetime even if it keeps sending', T, async () => {
  const { sim, ws } = await boot({ noLaunchMs: 300 });
  const s = await new FakeSender().connect('127.0.0.1', sim.castPort);
  const ping = setInterval(() => { try { s.send(NS.HEARTBEAT, 'receiver-0', { type: 'PING' }); } catch { /* closed */ } }, 50);
  try {
    await Promise.race([closed(s), sleep(2000).then(() => { throw new Error('connection survived past its lifetime'); })]);
  } finally { clearInterval(ping); s.close(); ws.close(); await sim.close(); }
});

test('a connection that LAUNCHed outlives the no-launch lifetime', T, async () => {
  const { sim, ws } = await boot({ noLaunchMs: 300 });
  const s = await new FakeSender().connect('127.0.0.1', sim.castPort);
  try {
    await s.launch();
    await sleep(700);
    s.send(NS.HEARTBEAT, 'receiver-0', { type: 'PING' });
    await s.waitFor(m => m.json?.type === 'PONG');
  } finally { s.close(); ws.close(); await sim.close(); }
});

test('per-IP connection cap: the 5th simultaneous connection from one IP is refused', T, async () => {
  const { sim, ws } = await boot();
  const held = [];
  try {
    for (let i = 0; i < 4; i++) held.push(await new FakeSender().connect('127.0.0.1', sim.castPort));
    const extra = await new FakeSender().connect('127.0.0.1', sim.castPort);
    await Promise.race([closed(extra), sleep(2000).then(() => { throw new Error('5th connection was not refused'); })]);
    held[0].send(NS.HEARTBEAT, 'receiver-0', { type: 'PING' });
    await held[0].waitFor(m => m.json?.type === 'PONG');
  } finally { held.forEach(h => h.close()); ws.close(); await sim.close(); }
});

test('a handler error is logged and the connection stays open; a framing error closes it', T, async () => {
  const { sim, ws, logs } = await boot();
  sim.bridge.deliver = () => { throw new Error('boom'); };
  const s = await new FakeSender().connect('127.0.0.1', sim.castPort);
  const bad = await new FakeSender().connect('127.0.0.1', sim.castPort);
  try {
    const app = await s.launch();
    s.send(NS.WEBRTC, app.transportId, { type: 'OFFER', sdp: 'o' });
    await sleep(150);
    assert.ok(logs.some(l => l.join(' ').includes('boom')), 'handler error logged');
    s.send(NS.HEARTBEAT, 'receiver-0', { type: 'PING' });
    await s.waitFor(m => m.json?.type === 'PONG');
    bad.socket.write(Buffer.from([0x7f, 0xff, 0xff, 0xff])); // absurd frame length
    await Promise.race([closed(bad), sleep(2000).then(() => { throw new Error('bad framing not closed'); })]);
  } finally { s.close(); bad.close(); ws.close(); await sim.close(); }
});

test('attacker-controlled strings are stripped of control characters before logging', T, async () => {
  const { sim, ws, logs } = await boot();
  const s = await new FakeSender().connect('127.0.0.1', sim.castPort);
  try {
    s.send('urn:x-cast:evil\n[00:00] FAKE LINE\u001b[31m', 'web-x\r\u0000y', { type: 'X' });
    await sleep(150);
    const flat = logs.flat().filter(a => typeof a === 'string');
    assert.ok(flat.some(a => a.includes('evil')), 'message was logged');
    assert.ok(flat.every(a => !/[\x00-\x1f\x7f]/.test(a)), 'no control characters in any logged string');
  } finally { s.close(); ws.close(); await sim.close(); }
});

test('page WebSocket messages over 4 MiB are refused', T, async () => {
  const { sim, ws } = await boot();
  try {
    const gone = new Promise(r => ws.once('close', r));
    ws.send(JSON.stringify({ kind: 'report', id: 1, report: 'x'.repeat(5 << 20) }));
    await Promise.race([gone, sleep(3000).then(() => { throw new Error('oversized frame accepted'); })]);
  } finally { ws.terminate(); await sim.close(); }
});

test('a serialized report over 1 MiB is dropped', T, async () => {
  const { sim, ws } = await boot({}, () => ({ ...REPORT, junk: 'x'.repeat(2 << 20) }));
  try {
    assert.equal(await sim.getReport(), null);
  } finally { ws.close(); await sim.close(); }
});

test('report files: >= 10 s apart, at most 500 in out/', T, async () => {
  const outDir = mkdtempSync(path.join(tmpdir(), 'cast-sim-out-'));
  let t = Date.UTC(2026, 0, 1);
  const { sim, ws } = await boot({ outDir, now: () => t });
  const files = () => readdirSync(outDir).filter(f => f.startsWith('cast-sim-report-'));
  try {
    await sim.emitReport('a');
    await sim.emitReport('b');
    assert.equal(files().length, 1, 'second report within 10 s is not written');
    t += 10_000;
    await sim.emitReport('c');
    assert.equal(files().length, 2);
    for (let i = 0; i < 500; i++) writeFileSync(path.join(outDir, `cast-sim-report-fill-${i}.json`), '{}');
    t += 10_000;
    const before = files().length;
    const rep = await sim.emitReport('d');
    assert.ok(rep, 'report still returned/printed');
    assert.equal(files().length, before, 'no new file once 500 exist');
  } finally { ws.close(); await sim.close(); }
});
