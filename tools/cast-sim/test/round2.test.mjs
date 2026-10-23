// Fix round 2: connection/launch limits, loopback token, inbound allowlist, sync bounds,
// HTTP hardening, shutdown, CLI validation. See REVIEW-FIXES-TDD.md "Round 2".
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import vm from 'node:vm';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import { chmodSync, mkdtempSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';
import { createSim } from '../lib/sim.mjs';
import { FakeSender } from '../lib/fakesender.mjs';
import { CastReceiver, NS } from '../lib/receiver.mjs';
import { SyncStore } from '../lib/sync.mjs';
import { PageBridge } from '../lib/pagebridge.mjs';
import { formatTable, isPlausible } from '../lib/stats.mjs';
import { startWeb, expectedHosts } from '../lib/web.mjs';
import { parseBindIp, describeListenError } from '../lib/cli.mjs';
import { runShutdown } from '../lib/shutdown.mjs';
import { wsUrl, wsOpts } from './helpers.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');
const receiverDir = path.resolve(HERE, '../../../receiver');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const T = { timeout: 15000 };
const REPORT = { frames: { presented: 3, decoded: 3, fps: 30, decodeFailures: 0, decodeFailurePct: 0 },
  latencyMs: { count: 3, min: 1, p50: 2, p90: 3, p99: 3, max: 3, mean: 2 }, durationS: 1, webrtc: null };

async function boot(extra = {}) {
  const logs = [];
  const sim = await createSim({ bind: '127.0.0.1', castPort: 0, httpPort: 0, receiverDir, mdns: false,
    log: (...a) => logs.push(a.join(' ')), print: () => {}, ...extra });
  const ws = new WebSocket(wsUrl(sim), wsOpts(sim));
  await new Promise((res, rej) => { ws.once('open', res); ws.once('error', rej); });
  ws.on('message', raw => {
    const m = JSON.parse(raw);
    if (m.kind === 'report-request') ws.send(JSON.stringify({ kind: 'report', id: m.id, report: REPORT }));
    if (m.kind === 'frame-request') ws.send(JSON.stringify({ kind: 'frame', id: m.id, dataUrl: null }));
  });
  ws.send(JSON.stringify({ kind: 'ready' }));
  while (!sim.bridge.ready) await sleep(5);
  return { sim, ws, logs };
}
const closed = s => new Promise(r => { if (s.socket.destroyed) r(); else s.once('close', r); });

function rawReq(port, p, { method = 'GET', headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const r = http.request({ host: '127.0.0.1', port, path: p, method, headers: { host: `127.0.0.1:${port}`, ...headers }, agent: false }, res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString() }));
    });
    r.on('error', reject);
    r.end(body);
  });
}

// ---- A. eviction + LAUNCH rate limit
test('A: a new LAUNCH destroys the evicted previous owner connection', T, async () => {
  const { sim, ws } = await boot();
  const s1 = await new FakeSender('sender-1').connect('127.0.0.1', sim.castPort);
  const s2 = await new FakeSender('sender-2').connect('127.0.0.1', sim.castPort);
  try {
    await s1.launch();
    await s2.launch();
    await Promise.race([closed(s1), sleep(2000).then(() => { throw new Error('evicted connection still open'); })]);
  } finally { s1.close(); s2.close(); ws.close(); await sim.close(); }
});

test('A: LAUNCHes per IP are capped (4/min) -> LAUNCH_ERROR', T, async () => {
  const { sim, ws } = await boot();
  const senders = [];
  try {
    const results = [];
    for (let i = 0; i < 5; i++) {
      const s = await new FakeSender(`sender-${i}`).connect('127.0.0.1', sim.castPort);
      senders.push(s);
      s.send(NS.CONNECTION, 'receiver-0', { type: 'CONNECT' });
      results.push((await s.request(NS.RECEIVER, { type: 'LAUNCH', appId: '9098830C' })).json);
      await sleep(50);
    }
    assert.deepEqual(results.slice(0, 4).map(r => r.type), Array(4).fill('RECEIVER_STATUS'));
    assert.equal(results[4].type, 'LAUNCH_ERROR');
  } finally { senders.forEach(s => s.close()); ws.close(); await sim.close(); }
});

// ---- B. TLS pre-handshake bounds
test('B: pre-handshake sockets are bounded (maxConnections = 2 x MAX_CONNS)', T, async () => {
  const { sim, ws } = await boot();
  const socks = [];
  try {
    for (let i = 0; i < 32; i++) {
      const s = net.connect(sim.castPort, '127.0.0.1');
      s.on('error', () => {});
      socks.push(s);
      await new Promise(r => s.once('connect', r));
    }
    const extra = net.connect(sim.castPort, '127.0.0.1');
    extra.on('error', () => {});
    socks.push(extra);
    await Promise.race([new Promise(r => extra.once('close', r)), sleep(1500).then(() => { throw new Error('33rd raw socket not refused'); })]);
  } finally { socks.forEach(s => s.destroy()); ws.close(); await sim.close(); }
});

test('B: a socket that never completes the TLS handshake is closed (handshakeTimeout)', T, async () => {
  const { sim, ws } = await boot({ handshakeTimeoutMs: 200 });
  const s = net.connect(sim.castPort, '127.0.0.1');
  s.on('error', () => {});
  try {
    await Promise.race([new Promise(r => s.once('close', r)), sleep(2500).then(() => { throw new Error('idle pre-handshake socket survived'); })]);
  } finally { s.destroy(); ws.close(); await sim.close(); }
});

// ---- C. loopback token
test('C: /report, /frame.png and POST /reset require the per-run token (header or ?t=)', T, async () => {
  const { sim, ws } = await boot();
  const p = sim.localPort;
  const json = { 'content-type': 'application/json' };
  try {
    for (const route of ['/report', '/frame.png']) {
      assert.equal((await rawReq(p, route)).status, 401, `${route} no token`);
      assert.equal((await rawReq(p, route, { headers: { 'x-cast-sim-token': 'x'.repeat(sim.token.length) } })).status, 401, `${route} wrong token`);
      assert.equal((await rawReq(p, route, { headers: { 'x-cast-sim-token': 'short' } })).status, 401, `${route} short token`);
    }
    assert.equal((await rawReq(p, '/report', { headers: { 'x-cast-sim-token': sim.token } })).status, 200);
    assert.equal((await rawReq(p, `/report?t=${sim.token}`)).status, 200);
    assert.equal((await rawReq(p, '/frame.png', { headers: { 'x-cast-sim-token': sim.token } })).status, 404, 'authorized, but no frame');
    assert.equal((await rawReq(p, '/reset', { method: 'POST', headers: json, body: '{}' })).status, 401);
    assert.equal((await rawReq(p, '/reset', { method: 'POST', headers: { ...json, 'x-cast-sim-token': sim.token }, body: '{}' })).status, 200);
    assert.equal((await rawReq(p, '/receiver/index.html')).status, 200, 'page assets stay open for the headless browser');
  } finally { ws.close(); await sim.close(); }
});

// ---- D. inbound namespace allowlist
test('D: only the WebRTC namespace is relayed to the page', () => {
  const delivered = [];
  const logs = [];
  const page = { deliver: (ns, sid, d) => delivered.push(ns), senderDisconnected() {}, teardown() {} };
  const r = new CastReceiver({ send() {}, page, log: (...a) => logs.push(a.join(' ')), newId: (() => { let n = 0; return () => `id${++n}`; })() });
  const msg = (ns, dest, obj) => ({ sourceId: 'sender-0', destinationId: dest, namespace: ns, payloadUtf8: JSON.stringify(obj) });
  r.onMessage(msg(NS.CONNECTION, 'receiver-0', { type: 'CONNECT' }));
  r.onMessage(msg(NS.RECEIVER, 'receiver-0', { type: 'LAUNCH', appId: '9098830C', requestId: 1 }));
  const tid = r.app.transportId;
  r.onMessage(msg(NS.CONNECTION, tid, { type: 'CONNECT' }));
  r.onMessage(msg('urn:x-cast:evil', tid, { type: 'X' }));
  r.onMessage(msg(NS.WEBRTC, tid, { type: 'OFFER' }));
  assert.deepEqual(delivered, [NS.WEBRTC]);
  assert.ok(logs.some(l => /dropped/.test(l)));
});

// ---- E. shim clears the token from the URL
test('E: cast-shim clears the token from location.hash via history.replaceState', () => {
  const urls = [];
  const calls = [];
  const sandbox = {
    location: { hash: '#t=sekret', host: '127.0.0.1:1', pathname: '/receiver/index.html', search: '?x=1' },
    history: { replaceState: (...a) => calls.push(a) },
    WebSocket: class { constructor(u) { urls.push(u); } addEventListener() {} },
    URLSearchParams, encodeURIComponent, console, JSON, Set, Map,
  };
  sandbox.window = sandbox;
  vm.runInNewContext(readFileSync(path.join(ROOT, 'page/cast-shim.js'), 'utf8'), sandbox);
  assert.match(urls[0], /\/ws\?t=sekret$/);
  assert.equal(calls.length, 1);
  assert.equal(calls[0][2], '/receiver/index.html?x=1');
});

// ---- F. sync bounds, pinning, client id
test('F: /sync drops out-of-range prevRtt samples (1..10 s) and bounds |t - arrival| (<= 1 h, round 3)', () => {
  const s = new SyncStore({ now: () => 10_000_000 });
  const ok = { client: 'c', id: 1, t: 10_000_000 };
  s.ping(ok);
  for (const bad of [{ t: 10_000_000 + 3_700_000 }, { t: 10_000_000 - 3_700_000 }]) {
    assert.throws(() => s.ping({ ...ok, id: 2, ...bad }), TypeError, JSON.stringify(bad));
  }
  for (const prevRtt of [10_001, -1, 'x']) s.ping({ ...ok, id: 2, prevId: 1, prevRtt });
  assert.equal(s.best(), null);
  s.ping({ ...ok, id: 3, prevId: 2, prevRtt: 10_000, t: 10_000_000 + 59_000 });
});

test('F: best() pins the first client until it is idle > 5 s', () => {
  let now = 0;
  const s = new SyncStore({ now: () => now });
  const sample = (client, id) => { s.ping({ client, id, t: now }); now += 5; s.ping({ client, id: id + 1, t: now, prevId: id, prevRtt: 5 }); };
  sample('a', 1);
  now += 100;
  sample('b', 1);
  assert.equal(s.best().client, 'a', 'newer client does not steal the offset');
  now += 4000; sample('b', 3);
  assert.equal(s.best().client, 'a');
  now += 6000; sample('b', 5);
  assert.equal(s.best().client, 'b', 'a idle > 5 s -> switch');
});

test('F: the printed report names the chosen sync client', () => {
  const r = { ...REPORT, offset: { client: 'phone-abc', offsetMs: 1, uncertaintyMs: 1, samples: 3 }, reason: 'x', clockSynced: true };
  assert.match(formatTable(r), /client phone-abc/);
});

// ---- G. HTTP hardening
test('G: oversized request body -> 413', T, async () => {
  const { sim, ws } = await boot();
  try {
    const r = await rawReq(sim.httpPort, '/sync', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ client: 'c'.repeat(20_000) }) });
    assert.equal(r.status, 413);
  } finally { ws.close(); await sim.close(); }
});

test('G: HTTP server timeouts and public connection cap', T, async () => {
  const web = await startWeb({ bind: '127.0.0.1', port: 0, localPort: 0, receiverDir, sync: new SyncStore(), bridge: new PageBridge(), getReport: async () => null, resetStats() {}, token: 'x'.repeat(64), log() {} });
  try {
    assert.equal(web.servers.public.headersTimeout, 10_000);
    assert.equal(web.servers.public.requestTimeout, 15_000);
    assert.equal(web.servers.public.maxConnections, 64);
    assert.equal(web.servers.local.headersTimeout, 10_000);
    assert.equal(web.servers.local.requestTimeout, 15_000);
  } finally { await web.close(); }
});

// ---- H. docker context
test('H: Dockerfile.dockerignore excludes notes and agent state', () => {
  const lines = readFileSync(path.join(ROOT, 'Dockerfile.dockerignore'), 'utf8').split('\n').map(l => l.trim());
  for (const want of ['tools/cast-sim/.omc', 'tools/cast-sim/REVIEW-FIXES-TDD.md']) assert.ok(lines.includes(want), want);
});

// ---- J. plausibility with sync offset
test('J: isPlausible subtracts the clock offset before the 1 h check', () => {
  const cb = 1_000_000_000_000;
  const hour = 3_600_000;
  assert.equal(isPlausible(cb - 2 * hour, cb, 0), false, 'phone 2 h behind, unsynced');
  assert.equal(isPlausible(cb - 2 * hour, cb, 2 * hour), true, 'phone 2 h behind, offset known');
  assert.equal(isPlausible(cb + 5 * hour, cb, 2 * hour), false, 'still off after correction');
});

// ---- K. shutdown + forced report
test('K: forced report bypasses the 10 s gap', T, async () => {
  const outDir = mkdtempSync(path.join(tmpdir(), 'cast-sim-r2-'));
  const { sim, ws } = await boot({ outDir });
  try {
    await sim.emitReport('a');
    await sim.emitReport('b');
    assert.equal(readdirSync(outDir).length, 1, 'unforced second report is rate-limited');
    await sim.emitReport('shutdown', { force: true });
    assert.equal(readdirSync(outDir).length, 2);
  } finally { ws.close(); await sim.close(); }
});

test('K: runShutdown stops mDNS first, bounds the report, logs rejections', T, async () => {
  const order = [];
  const logs = [];
  const sim = {
    stopAdvert: async () => { order.push('advert'); },
    emitReport: (reason, opts) => { order.push(`report:${reason}:${opts?.force}`); return new Promise(() => {}); },
    close: async () => { order.push('close'); throw new Error('close boom'); },
  };
  const browser = { close: async () => { order.push('browser'); } };
  const t0 = Date.now();
  await runShutdown({ sim, browser, log: (...a) => logs.push(a.join(' ')), reportTimeoutMs: 100 });
  assert.equal(order[0], 'advert');
  assert.ok(order.includes('report:shutdown:true'));
  assert.ok(order.includes('close') && order.includes('browser'), 'closing continues after the report timed out');
  assert.ok(Date.now() - t0 < 1500);
  assert.ok(logs.some(l => /close boom/.test(l)), 'rejected close is logged');
  assert.ok(logs.some(l => /report/.test(l)), 'report timeout is logged');
});

// ---- L. static serving errors, bridge on closed ws
test('L: static read errors other than ENOENT log and return 500; ENOENT stays 404', { timeout: 8000, skip: process.getuid?.() === 0 && 'root ignores file modes' }, async () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'cast-sim-static-'));
  const secret = path.join(dir, 'secret.txt');
  writeFileSync(secret, 'x');
  chmodSync(secret, 0o000);
  const { sim, ws, logs } = await boot({ receiverDir: dir });
  try {
    assert.equal((await rawReq(sim.localPort, '/receiver/secret.txt')).status, 500);
    assert.ok(logs.some(l => /EACCES/.test(l)), 'error logged');
    assert.equal((await rawReq(sim.localPort, '/receiver/missing.txt')).status, 404);
  } finally { ws.close(); await sim.close(); chmodSync(secret, 0o600); }
});

test('L: requests on a bridge whose ws is not OPEN resolve null immediately', async () => {
  const ws = Object.assign(new EventEmitter(), { readyState: 3, send() { throw new Error('send on closed ws'); }, close() {} });
  const bridge = new PageBridge(() => {});
  bridge.attach(ws);
  ws.emit('message', JSON.stringify({ kind: 'ready' }));
  const t0 = Date.now();
  assert.equal(await bridge.requestReport(null, 5000), null);
  assert.equal(await bridge.requestFrame({}, 5000), null);
  assert.ok(Date.now() - t0 < 500);
});

// ---- M. CLI validation, port-80 Host
test('M: parseBindIp accepts IPv4 only and rejects 0.0.0.0', () => {
  assert.equal(parseBindIp('192.168.1.5'), '192.168.1.5');
  assert.throws(() => parseBindIp('0.0.0.0'), /0\.0\.0\.0/);
  assert.throws(() => parseBindIp('::1'), /IPv4/);
  assert.throws(() => parseBindIp('nonsense'), /IPv4/);
});

test('M: describeListenError explains EADDRINUSE', () => {
  const msg = describeListenError(Object.assign(new Error('listen EADDRINUSE'), { code: 'EADDRINUSE', port: 8009, address: '10.0.0.2' }));
  assert.match(msg, /10\.0\.0\.2:8009/);
  assert.match(msg, /in use/);
  assert.match(describeListenError(new Error('other')), /other/);
});

test('M: expected Host omits :80', () => {
  assert.deepEqual(expectedHosts(['10.0.0.2'], 80), ['10.0.0.2']);
  assert.deepEqual(expectedHosts(['127.0.0.1', 'localhost'], 8766), ['127.0.0.1:8766', 'localhost:8766']);
});
