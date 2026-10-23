// In-process integration: real TLS server + HTTP/WS + a STUBBED receiver page
// (a ws client speaking the shim protocol) driven by a FakeSender.
import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';
import { createSim } from '../lib/sim.mjs';
import { FakeSender } from '../lib/fakesender.mjs';
import { NS } from '../lib/receiver.mjs';
import { txtRecords } from '../lib/mdns.mjs';
import { wsUrl, wsOpts } from './helpers.mjs';

const WEBRTC = NS.WEBRTC;
const receiverDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../receiver');

const auth = sim => ({ headers: { 'x-cast-sim-token': sim.token } });

async function boot(extra = {}) {
  const lines = [];
  const sim = await createSim({ bind: '127.0.0.1', castPort: 0, httpPort: 0, receiverDir, mdns: false, log: () => {}, print: l => lines.push(l), ...extra });
  const pageMsgs = [];
  const ws = new WebSocket(wsUrl(sim), wsOpts(sim));
  await new Promise(r => ws.once('open', r));
  ws.on('message', raw => {
    const m = JSON.parse(raw);
    pageMsgs.push(m);
    if (m.kind === 'msg' && m.data.type === 'OFFER') {
      ws.send(JSON.stringify({ kind: 'send', ns: m.ns, senderId: m.senderId, data: { type: 'ANSWER', sdp: 'answer-sdp' } }));
    }
    if (m.kind === 'frame-request') {
      const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
      ws.send(JSON.stringify({ kind: 'frame', id: m.id, dataUrl: `data:image/png;base64,${png}`, echo: { crop: m.crop, overlay: m.overlay } }));
    }
    if (m.kind === 'report-request') {
      ws.send(JSON.stringify({ kind: 'report', id: m.id, report: {
        frames: { presented: 3, decoded: 3, fps: 30, decodeFailures: 0, decodeFailurePct: 0 },
        latencyMs: { count: 3, min: 1, p50: 2, p90: 3, p99: 3, max: 3, mean: 2 }, durationS: 1, offset: m.offset, webrtc: null,
      } }));
    }
  });
  ws.send(JSON.stringify({ kind: 'ready' }));
  return { sim, ws, pageMsgs, lines };
}

test('sender drives CONNECT/GET_STATUS/LAUNCH/OFFER and gets the page ANSWER', async () => {
  const { sim, ws, pageMsgs } = await boot();
  const s = await new FakeSender().connect('127.0.0.1', sim.castPort);
  try {
    s.send(NS.CONNECTION, 'receiver-0', { type: 'CONNECT' });
    const st = await s.request(NS.RECEIVER, { type: 'GET_STATUS' });
    assert.equal(st.json.type, 'RECEIVER_STATUS');
    const app = await s.launch('9098830C');
    assert.ok(app.namespaces.some(n => n.name === WEBRTC));
    s.send(WEBRTC, app.transportId, { type: 'OFFER', sdp: 'offer-sdp' });
    const ans = await s.waitFor(m => m.namespace === WEBRTC && m.json?.type === 'ANSWER');
    assert.equal(ans.sourceId, app.transportId);
    assert.equal(ans.json.sdp, 'answer-sdp');
    assert.ok(pageMsgs.some(m => m.kind === 'msg' && m.data.type === 'OFFER' && m.senderId === 'sender-0'));
    // heartbeat
    s.send(NS.HEARTBEAT, 'receiver-0', { type: 'PING' });
    await s.waitFor(m => m.json?.type === 'PONG');
  } finally { s.close(); ws.close(); await sim.close(); }
});

test('STOP tells the page the sender left and flushes a report', async () => {
  const { sim, ws, pageMsgs, lines } = await boot();
  const s = await new FakeSender().connect('127.0.0.1', sim.castPort);
  try {
    const app = await s.launch();
    s.send(NS.RECEIVER, 'receiver-0', { type: 'STOP', requestId: 50, sessionId: app.sessionId });
    const r = await s.waitFor(m => m.json?.requestId === 50);
    assert.deepEqual(r.json.status.applications, []);
    await new Promise(res => setTimeout(res, 300));
    assert.ok(pageMsgs.some(m => m.kind === 'sender-disconnected'));
    assert.ok(lines.some(l => /cast-sim latency report/.test(l)), 'report printed');
  } finally { s.close(); ws.close(); await sim.close(); }
});

test('HTTP: /time, /sync -> /offset, clock.html, receiver files, traversal blocked', async () => {
  const { sim, ws } = await boot();
  const base = `http://127.0.0.1:${sim.httpPort}`; // LAN listener
  const local = `http://127.0.0.1:${sim.localPort}`; // loopback listener
  try {
    const t = await (await fetch(`${base}/time`)).json();
    assert.ok(Math.abs(t.t - Date.now()) < 2000);
    assert.equal((await fetch(`${base}/clock.html`)).status, 200);
    const idx = await (await fetch(`${local}/receiver/index.html`)).text();
    assert.match(idx, /cast_receiver_framework\.js/);
    assert.equal((await fetch(`${local}/receiver/receiver.js`)).status, 200);
    assert.equal((await fetch(`${local}/lib/..%2f..%2fpackage.json`)).status, 404);
    assert.equal((await fetch(`${local}/receiver/%2e%2e/%2e%2e/package.json`)).status === 200, false);
    const post = body => fetch(`${base}/sync`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) }).then(r => r.json());
    const a = await post({ client: 'c', id: 1, t: Date.now() });
    await post({ client: 'c', id: 2, t: Date.now(), prevId: 1, prevRtt: 3 });
    assert.equal(typeof a.serverMs, 'number');
    const off = await (await fetch(`${base}/offset`)).json();
    assert.equal(off.client, 'c');
    assert.equal((await fetch(`${base}/sync`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{"client":5}' })).status, 400);
    const rep = await (await fetch(`${local}/report`, { headers: { 'x-cast-sim-token': sim.token } })).json();
    assert.equal(rep.frames.presented, 3);
  } finally { ws.close(); await sim.close(); }
});

test('/frame.png returns the page PNG; 404 when no page is connected', async () => {
  const { sim, ws } = await boot();
  const base = `http://127.0.0.1:${sim.localPort}`;
  try {
    const r = await fetch(`${base}/frame.png?crop=1`, auth(sim));
    assert.equal(r.status, 200);
    assert.equal(r.headers.get('content-type'), 'image/png');
    assert.equal(Buffer.from(await r.arrayBuffer()).subarray(1, 4).toString(), 'PNG');
    ws.close();
    await new Promise(res => setTimeout(res, 100));
    assert.equal((await fetch(`${base}/frame.png`, auth(sim))).status, 404);
  } finally { ws.close(); await sim.close(); }
});

test('cross-origin WebSocket upgrade is refused', async () => {
  const { sim, ws } = await boot();
  try {
    const evil = new WebSocket(wsUrl(sim), { headers: { origin: 'http://evil.example' } });
    await assert.rejects(new Promise((res, rej) => { evil.once('open', res); evil.once('error', rej); }));
  } finally { ws.close(); await sim.close(); }
});

test('mDNS TXT records carry the Cast keys the app reads', () => {
  const txt = txtRecords('abcd'.repeat(8), 'Sim TV (cast-sim)').map(b => b.toString());
  for (const k of ['id=', 'cd=', 've=05', 'md=', 'ic=', 'fn=Sim TV (cast-sim)', 'ca=4101', 'st=0', 'bs=', 'nf=1', 'rs=', 'rm=']) {
    assert.ok(txt.some(t => t.startsWith(k)), `missing ${k}`);
  }
});

const sleep = ms => new Promise(r => setTimeout(r, ms));

test('scanner connections connecting and hanging up never disturb a real session', async () => {
  const { sim, ws, pageMsgs } = await boot();
  const app = new FakeSender();
  try {
    await app.connect('127.0.0.1', sim.castPort);
    const launched = await app.launch();
    for (let i = 0; i < 5; i++) {
      const scanner = await new FakeSender().connect('127.0.0.1', sim.castPort);
      if (i % 2) scanner.send(NS.CONNECTION, 'receiver-0', { type: 'CONNECT' });
      scanner.close();
    }
    await sleep(200);
    assert.ok(!pageMsgs.some(m => m.kind === 'sender-disconnected'), 'page must not be told the sender left');
    app.send(WEBRTC, launched.transportId, { type: 'OFFER', sdp: 'o' });
    const ans = await app.waitFor(m => m.json?.type === 'ANSWER');
    assert.equal(ans.json.sdp, 'answer-sdp');
  } finally { app.close(); ws.close(); await sim.close(); }
});

test('app pattern: two TLS connections within ms from one IP, LAUNCH on either one', async () => {
  for (const launchOn of [0, 1]) {
    const { sim, ws } = await boot();
    const a = new FakeSender(); const b = new FakeSender();
    try {
      await Promise.all([a.connect('127.0.0.1', sim.castPort), b.connect('127.0.0.1', sim.castPort)]);
      a.send(NS.CONNECTION, 'receiver-0', { type: 'CONNECT' });
      b.send(NS.CONNECTION, 'receiver-0', { type: 'CONNECT' });
      await sleep(50);
      const l = [a, b][launchOn];
      const app = await l.launch();
      l.send(WEBRTC, app.transportId, { type: 'OFFER', sdp: 'o' });
      await l.waitFor(m => m.json?.type === 'ANSWER');
      // the other, idle connection must still be open and usable
      const other = [a, b][1 - launchOn];
      other.send(NS.HEARTBEAT, 'receiver-0', { type: 'PING' });
      await other.waitFor(m => m.json?.type === 'PONG');
      assert.ok(!l.socket.destroyed);
    } finally { a.close(); b.close(); ws.close(); await sim.close(); }
  }
});

test('a later LAUNCH on another connection takes the app over; old owner closing is harmless', async () => {
  const { sim, ws, pageMsgs } = await boot();
  const a = await new FakeSender().connect('127.0.0.1', sim.castPort);
  const b = await new FakeSender().connect('127.0.0.1', sim.castPort);
  try {
    await a.launch();
    const appB = await b.launch();
    a.close();
    await sleep(150);
    b.send(WEBRTC, appB.transportId, { type: 'OFFER', sdp: 'o' });
    await b.waitFor(m => m.json?.type === 'ANSWER');
    const disc = pageMsgs.filter(m => m.kind === 'sender-disconnected').length;
    assert.equal(disc, 1, 'only the eviction notified the page');
  } finally { a.close(); b.close(); ws.close(); await sim.close(); }
});
