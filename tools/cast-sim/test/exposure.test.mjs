// Fix set 2: what is reachable from the LAN listener vs the loopback listener,
// Host/Origin/token checks on /ws, and content-type checks on POSTs.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';
import { createSim } from '../lib/sim.mjs';

const receiverDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../receiver');

function boot() {
  return createSim({ bind: '127.0.0.1', castPort: 0, httpPort: 0, localPort: 0, receiverDir, mdns: false, log: () => {}, print: () => {} });
}

function req(port, p, { method = 'GET', host = `127.0.0.1:${port}`, headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const r = http.request({ host: '127.0.0.1', port, path: p, method, headers: { host, ...headers }, agent: false }, res => {
      res.resume();
      res.on('end', () => resolve(res.statusCode));
    });
    r.on('error', reject);
    r.end(body);
  });
}

function wsOpen(url, headers = {}) {
  const ws = new WebSocket(url, { headers });
  return new Promise(resolve => {
    ws.once('open', () => { ws.terminate(); resolve(true); });
    ws.once('error', () => resolve(false));
    ws.once('unexpected-response', () => resolve(false));
  });
}

test('LAN listener serves only the clock page, its assets and the sync endpoints', { timeout: 8000 }, async () => {
  const sim = await boot();
  try {
    const p = sim.httpPort;
    for (const ok of ['/', '/clock.html', '/page/clock.mjs', '/lib/barcode.mjs', '/lib/stats.mjs', '/lib/syncloop.mjs', '/time', '/offset']) {
      assert.equal(await req(p, ok), 200, ok);
    }
    for (const no of ['/report', '/frame.png', '/receiver/index.html', '/receiver/receiver.js', '/page/harness.mjs', '/page/cast-shim.js', '/lib/castproto.mjs', '/ws']) {
      assert.equal(await req(p, no), 404, no);
    }
    assert.equal(await req(p, '/reset', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' }), 404);
  } finally { await sim.close(); }
});

test('loopback listener is bound to 127.0.0.1 and serves the debug routes', { timeout: 8000 }, async () => {
  const sim = await boot();
  try {
    const p = sim.localPort;
    assert.notEqual(p, sim.httpPort);
    for (const ok of ['/receiver/index.html', '/page/harness.mjs', '/lib/castproto.mjs', '/clock.html', '/time']) {
      assert.equal(await req(p, ok), 200, ok);
    }
    const tok = { 'x-cast-sim-token': sim.token };
    assert.equal(await req(p, '/report', { headers: tok }), 200);
    assert.equal(await req(p, '/frame.png', { headers: tok }), 404, 'no page connected');
  } finally { await sim.close(); }
});

test('requests with a foreign Host header get 421 (DNS rebinding)', { timeout: 8000 }, async () => {
  const sim = await boot();
  try {
    for (const port of [sim.httpPort, sim.localPort]) {
      assert.equal(await req(port, '/time', { host: 'evil.example' }), 421);
      assert.equal(await req(port, '/time', { host: `evil.example:${port}` }), 421);
      assert.equal(await req(port, '/time', { host: `127.0.0.1:${port + 1}` }), 421);
    }
  } finally { await sim.close(); }
});

test('POST /sync and /reset require application/json', { timeout: 8000 }, async () => {
  const sim = await boot();
  try {
    const tok = { 'x-cast-sim-token': sim.token };
    const body = JSON.stringify({ client: 'c', id: 1, t: Date.now() });
    assert.equal(await req(sim.httpPort, '/sync', { method: 'POST', body }), 415);
    assert.equal(await req(sim.httpPort, '/sync', { method: 'POST', body, headers: { 'content-type': 'text/plain' } }), 415);
    assert.equal(await req(sim.httpPort, '/sync', { method: 'POST', body, headers: { 'content-type': 'application/json' } }), 200);
    assert.equal(await req(sim.localPort, '/reset', { method: 'POST', body: '{}', headers: tok }), 415);
    assert.equal(await req(sim.localPort, '/reset', { method: 'POST', body: '{}', headers: { ...tok, 'content-type': 'application/json; charset=utf-8' } }), 200);
  } finally { await sim.close(); }
});

test('/ws needs the per-run token, an exact Host, a matching Origin and the loopback listener', { timeout: 8000 }, async () => {
  const sim = await boot();
  try {
    assert.match(sim.token, /^[0-9a-f]{32,}$/);
    const lp = sim.localPort;
    const good = `ws://127.0.0.1:${lp}/ws?t=${sim.token}`;
    assert.equal(await wsOpen(good), true, 'valid token');
    assert.equal(await wsOpen(good, { origin: `http://127.0.0.1:${lp}` }), true, 'same-origin ok');
    assert.equal(await wsOpen(`ws://127.0.0.1:${lp}/ws`), false, 'no token');
    assert.equal(await wsOpen(`ws://127.0.0.1:${lp}/ws?t=${'0'.repeat(sim.token.length)}`), false, 'wrong token');
    assert.equal(await wsOpen(`ws://127.0.0.1:${lp}/ws?t=short`), false, 'wrong-length token');
    assert.equal(await wsOpen(good, { host: 'evil.example' }), false, 'foreign Host');
    assert.equal(await wsOpen(good, { origin: 'http://evil.example' }), false, 'foreign Origin');
    assert.equal(await wsOpen(`ws://127.0.0.1:${sim.httpPort}/ws?t=${sim.token}`), false, 'LAN listener never upgrades');
  } finally { await sim.close(); }
});
