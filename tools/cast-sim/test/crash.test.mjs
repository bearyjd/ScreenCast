// Fix set 1: inputs from the network must never crash the sim process.
import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';
import { createSim } from '../lib/sim.mjs';
import { wsUrl, wsOpts } from './helpers.mjs';

const receiverDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../receiver');
const sleep = ms => new Promise(r => setTimeout(r, ms));

test('WS upgrade with "Origin: null" is rejected without crashing the sim', { timeout: 8000 }, async () => {
  const sim = await createSim({ bind: '127.0.0.1', castPort: 0, httpPort: 0, receiverDir, mdns: false, log: () => {}, print: () => {} });
  try {
    const bad = new WebSocket(wsUrl(sim), { ...wsOpts(sim), headers: { ...wsOpts(sim).headers, origin: 'null' } });
    await assert.rejects(new Promise((res, rej) => { bad.once('open', res); bad.once('error', rej); }));
    const r = await fetch(`http://127.0.0.1:${sim.httpPort}/time`);
    assert.equal(r.status, 200, 'sim still serving');
  } finally { await sim.close(); }
});

test('a malformed WebSocket frame from the page is logged, not fatal', { timeout: 8000 }, async () => {
  const logs = [];
  const sim = await createSim({ bind: '127.0.0.1', castPort: 0, httpPort: 0, receiverDir, mdns: false, log: (...a) => logs.push(a.join(' ')), print: () => {} });
  const ws = new WebSocket(wsUrl(sim), wsOpts(sim));
  try {
    await new Promise((res, rej) => { ws.once('open', res); ws.once('error', rej); });
    ws._socket.write(Buffer.from([0xff, 0x00])); // RSV bits set + invalid opcode
    await sleep(200);
    const r = await fetch(`http://127.0.0.1:${sim.httpPort}/time`);
    assert.equal(r.status, 200, 'sim still serving');
    assert.ok(logs.some(l => /page ws error/.test(l)), 'error logged');
  } finally { ws.terminate(); await sim.close(); }
});

test('valid-JSON-but-not-an-object page messages (null, 5, "x") are ignored', { timeout: 8000 }, async () => {
  const sim = await createSim({ bind: '127.0.0.1', castPort: 0, httpPort: 0, receiverDir, mdns: false, log: () => {}, print: () => {} });
  const ws = new WebSocket(wsUrl(sim), wsOpts(sim));
  try {
    await new Promise((res, rej) => { ws.once('open', res); ws.once('error', rej); });
    for (const raw of ['null', '5', '"x"']) ws.send(raw);
    await sleep(200);
    assert.equal((await fetch(`http://127.0.0.1:${sim.httpPort}/time`)).status, 200);
  } finally { ws.terminate(); await sim.close(); }
});
