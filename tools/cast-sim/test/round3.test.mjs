// Fix round 3: sync recovery, pin hardening, per-client offsets, takeover report,
// latency outlier rejection, shutdown bounds, DevTools pipe, hygiene.
// See REVIEW-FIXES-TDD.md "Round 3".
import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import vm from 'node:vm';
import { mkdtempSync, readdirSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';
import { createSim } from '../lib/sim.mjs';
import { FakeSender } from '../lib/fakesender.mjs';
import { NS } from '../lib/receiver.mjs';
import { SyncStore } from '../lib/sync.mjs';
import { buildReport, formatTable } from '../lib/stats.mjs';
import { runShutdown } from '../lib/shutdown.mjs';
import { launchBrowser } from '../lib/browser.mjs';
import * as syncloop from '../lib/syncloop.mjs';
import { wsUrl, wsOpts } from './helpers.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const receiverDir = path.resolve(HERE, '../../../receiver');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const T = { timeout: 15000 };
const REPORT = { frames: { presented: 3, decoded: 3, fps: 30, decodeFailures: 0, decodeFailurePct: 0 },
  latencyMs: { count: 3, min: 1, p50: 2, p90: 3, p99: 3, max: 3, mean: 2 }, durationS: 1, webrtc: null };

async function boot(extra = {}) {
  const logs = [];
  const kinds = [];
  const sim = await createSim({ bind: '127.0.0.1', castPort: 0, httpPort: 0, receiverDir, mdns: false,
    log: (...a) => logs.push(a.join(' ')), print: () => {}, ...extra });
  const ws = new WebSocket(wsUrl(sim), wsOpts(sim));
  await new Promise((res, rej) => { ws.once('open', res); ws.once('error', rej); });
  ws.on('message', raw => {
    const m = JSON.parse(raw);
    kinds.push(m.kind);
    if (m.kind === 'report-request') ws.send(JSON.stringify({ kind: 'report', id: m.id, report: REPORT }));
    if (m.kind === 'frame-request') ws.send(JSON.stringify({ kind: 'frame', id: m.id, dataUrl: null }));
  });
  ws.send(JSON.stringify({ kind: 'ready' }));
  while (!sim.bridge.ready) await sleep(5);
  return { sim, ws, logs, kinds };
}

// ---- 1. sync recovery
test('1: client resets prev after a failed ping and recovers', async () => {
  const bodies = [];
  let call = 0;
  const post = async body => {
    bodies.push(body);
    call++;
    if (call === 2) throw new Error('sync HTTP 400: bad');
    return { id: body.id, serverMs: 5000 };
  };
  let t = 1000;
  const c = syncloop.createSyncClient({ client: 'me', post, now: () => (t += 4) });
  await c.once();
  await assert.rejects(c.once(), /400/);
  await c.once();
  assert.equal(bodies[1].prevId, 1, 'second ping carried the first rtt');
  assert.equal(bodies[2].prevId, null, 'after the failure prev is reset');
  assert.equal(bodies[2].prevRtt, null);
});

test('1: client never reports a negative rtt', async () => {
  const bodies = [];
  const times = [1000, 900, 2000, 2004];
  const c = syncloop.createSyncClient({ client: 'me', post: async b => { bodies.push(b); return { id: b.id, serverMs: 1 }; }, now: () => times.shift() });
  await c.once(); // tSend 1000, tRecv 900 -> negative
  await c.once();
  assert.equal(bodies[1].prevRtt, null);
});

test('1: server ignores a bad prevRtt but keeps syncing', () => {
  let now = 1_000_000;
  const s = new SyncStore({ now: () => now });
  s.ping({ client: 'c', id: 1, t: now });
  for (const [i, bad] of [20_000, -5, 'x', 0.5].entries()) {
    now += 10;
    const r = s.ping({ client: 'c', id: 2 + i, t: now, prevId: 1 + i, prevRtt: bad });
    assert.equal(r.id, 2 + i);
  }
  assert.equal(s.best(), null, 'bad samples recorded nothing');
  assert.equal(s.clients.get('c').last, now, 'ping still counted');
  now += 10;
  s.ping({ client: 'c', id: 9, t: now, prevId: 5, prevRtt: 12 });
  assert.equal(s.best().rttMs, 12, 'sync continues with good samples');
});

// ---- 2. pin bypass
test('2: the pinned client is never evicted and forged 0-RTT floods cannot take the pin', () => {
  let now = 1_000_000;
  const s = new SyncStore({ now: () => now, maxClients: 4 });
  s.ping({ client: 'phone', id: 1, t: now }); now += 5;
  s.ping({ client: 'phone', id: 2, t: now, prevId: 1, prevRtt: 5 });
  assert.equal(s.best().client, 'phone');
  for (let i = 0; i < 20; i++) {
    now += 1;
    s.ping({ client: `evil${i}`, id: 1, t: now - 50_000 });
    s.ping({ client: `evil${i}`, id: 2, t: now - 50_000, prevId: 1, prevRtt: 0 });
  }
  assert.ok(s.clients.has('phone'), 'pinned client survives eviction');
  assert.equal(s.best().client, 'phone');
  now += 6000; // phone idle: forged clients have no samples, so nothing can take over
  assert.equal(s.best().client, 'phone');
});

// ---- 3. per-client offsets
test('3: SyncStore.all() lists every client with offset, rtt and age', () => {
  let now = 1_000_000;
  const s = new SyncStore({ now: () => now });
  for (const c of ['a', 'b']) { s.ping({ client: c, id: 1, t: now }); now += 5; s.ping({ client: c, id: 2, t: now - 100, prevId: 1, prevRtt: 8 }); }
  now += 50;
  const all = s.all();
  assert.deepEqual(all.map(x => x.client).sort(), ['a', 'b']);
  for (const x of all) assert.deepEqual(Object.keys(x).sort(), ['ageMs', 'client', 'offsetMs', 'rttMs']);
  assert.equal(all.find(x => x.client === 'b').ageMs, 50);
});

test('3: the report carries every client offset and the table prints them', { ...T }, async () => {
  const { sim, ws } = await boot();
  try {
    for (const c of ['phone-a', 'laptop-b']) {
      await sim.sync.ping({ client: c, id: 1, t: Date.now() });
      await sleep(5);
      sim.sync.ping({ client: c, id: 2, t: Date.now(), prevId: 1, prevRtt: 5 });
    }
    const rep = await sim.getReport();
    assert.deepEqual(rep.clients.map(x => x.client).sort(), ['laptop-b', 'phone-a']);
    assert.match(formatTable({ ...REPORT, ...rep, offset: null, clockSynced: false }), /laptop-b/);
  } finally { ws.close(); await sim.close(); }
});

test('3: clock page status shows the client id', () => {
  const s = syncloop.syncStatus('abc123', { offsetMs: 1.5, uncertaintyMs: 2, rttMs: 4 }, 7);
  assert.match(s, /abc123/);
});

// ---- 4. takeover report
test('4: a second connection LAUNCH reports the first session before the reset', T, async () => {
  const outDir = mkdtempSync(path.join(tmpdir(), 'cast-sim-r3-'));
  const { sim, ws, kinds } = await boot({ outDir });
  const s1 = await new FakeSender('sender-1').connect('127.0.0.1', sim.castPort);
  const s2 = await new FakeSender('sender-2').connect('127.0.0.1', sim.castPort);
  try {
    await s1.launch();
    await sim.emitReport('warmup'); // consumes the 10 s rate-limit window
    kinds.length = 0;
    await s2.launch();
    await sleep(300);
    assert.ok(kinds.indexOf('report-request') >= 0, 'old session reported');
    assert.ok(kinds.indexOf('report-request') < kinds.indexOf('reset'), 'report precedes resetStats');
    const reasons = readdirSync(outDir).map(f => JSON.parse(readFileSync(path.join(outDir, f), 'utf8')).reason);
    assert.ok(reasons.includes('session-end'), `forced past the rate limiter: ${reasons}`);
  } finally { s1.close(); s2.close(); ws.close(); await sim.close(); }
});

// ---- 5. sync bound and error text
test('5: /sync accepts a phone clock up to 1 h off, rejects beyond', () => {
  const s = new SyncStore({ now: () => 10_000_000 });
  s.ping({ client: 'c', id: 1, t: 10_000_000 - 3_000_000 });
  assert.throws(() => s.ping({ client: 'c', id: 2, t: 10_000_000 + 3_700_000 }), TypeError);
});

test('5: postSync surfaces the server error text', async () => {
  const fetchFn = async () => ({ ok: false, status: 400, json: async () => ({ error: 'bad sync request: t too far' }) });
  await assert.rejects(syncloop.postSync({ id: 1 }, { fetchFn }), /400.*bad sync request: t too far/);
  const noBody = async () => ({ ok: false, status: 500, json: async () => { throw new Error('nope'); } });
  await assert.rejects(syncloop.postSync({ id: 1 }, { fetchFn: noBody }), /sync HTTP 500/);
});

// ---- 6. latency outliers
test('6: latency outliers (< -1 s or > 10 s) are rejected and counted', () => {
  const mk = lat => ({ recvMs: 100_000 + lat, phoneMs: 100_000 });
  const rep = buildReport({
    samples: [mk(50), mk(60), mk(-1500), mk(10_500), mk(-1000), mk(10_000)],
    presented: 6, decodeFailures: 0, firstFrameMs: 0, lastFrameMs: 1000, offset: null,
  });
  assert.equal(rep.latencyRejected, 2);
  assert.equal(rep.latencyMs.count, 4);
  assert.ok(rep.latencyMs.max <= 10_000 && rep.latencyMs.min >= -1000);
  assert.equal(rep.latencyAtCallbackMs.count, 4);
  assert.match(formatTable({ ...rep }), /rejected 2/);
});

// ---- 7. shutdown
test('7: a hung mDNS stop cannot starve browser/sim close', { timeout: 6000 }, async () => {
  const order = [];
  const sim = {
    stopAdvert: () => new Promise(() => {}),
    emitReport: async () => { order.push('report'); },
    close: async () => { order.push('sim'); },
  };
  const t0 = Date.now();
  await runShutdown({ sim, browser: { close: async () => { order.push('browser'); } }, log: () => {}, stopAdvertMs: 100 });
  assert.ok(order.includes('browser') && order.includes('sim'));
  assert.ok(Date.now() - t0 < 1500);
});

test('7: shutdown does not write a second report for a session already reported', T, async () => {
  const outDir = mkdtempSync(path.join(tmpdir(), 'cast-sim-r3-'));
  const { sim, ws } = await boot({ outDir });
  try {
    assert.ok(await sim.emitReport('session-end'));
    assert.equal(await sim.emitReport('shutdown', { force: true }), null);
    assert.equal(readdirSync(outDir).length, 1);
  } finally { ws.close(); await sim.close(); }
});

// ---- 8. DevTools pipe
test('8: browser launches over a pipe, no remote-debugging-port', async () => {
  let opts;
  await launchBrowser({ executablePath: '/x', userDataDir: '/y', launchFn: async o => { opts = o; return {}; } });
  assert.equal(opts.pipe, true);
  assert.ok(!opts.args.some(a => /remote-debugging-port/.test(a)));
});

// ---- 9. hygiene
test('9: harness-boot drops closed peer connections from __castSimPcs', () => {
  class PC { constructor() { this.connectionState = 'new'; } }
  const win = { RTCPeerConnection: PC, dispatchEvent() {}, addEventListener() {} };
  win.window = win;
  vm.runInNewContext(readFileSync(path.join(HERE, '../page/harness-boot.js'), 'utf8'), { ...win, window: win, CustomEvent: class {}, addEventListener() {} });
  const a = new win.RTCPeerConnection();
  a.connectionState = 'closed';
  const b = new win.RTCPeerConnection();
  assert.equal(win.__castSimPcs.length, 1);
  assert.ok(win.__castSimPcs[0] === b);
});

test('9: page->sender frames over MAX_FRAME are dropped with a log', T, async () => {
  const { sim, ws, logs } = await boot();
  const s1 = await new FakeSender('sender-1').connect('127.0.0.1', sim.castPort);
  try {
    await s1.launch();
    ws.send(JSON.stringify({ kind: 'send', ns: NS.WEBRTC, senderId: null, data: 'x'.repeat(70_000) }));
    await sleep(300);
    assert.ok(!s1.received.some(m => m.namespace === NS.WEBRTC), 'oversize frame not delivered');
    assert.ok(logs.some(l => /dropp.*frame|frame.*dropp/i.test(l)));
  } finally { s1.close(); ws.close(); await sim.close(); }
});
