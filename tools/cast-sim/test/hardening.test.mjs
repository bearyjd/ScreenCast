// Fix sets 3(c,d,f,g) and 4: shutdown bounds, LRU eviction, sync loop, URL
// allowlist, single-sort percentiles, CLI validation.
import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { withTimeout } from '../lib/timeout.mjs';
import { startMdns } from '../lib/mdns.mjs';
import { SyncStore } from '../lib/sync.mjs';
import { postSync, startSyncLoop } from '../lib/syncloop.mjs';
import { isAllowedUrl } from '../lib/browser.mjs';
import { summarize } from '../lib/stats.mjs';
import { parseNumArg } from '../lib/cli.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

test('withTimeout resolves with the value, or rejects when the promise hangs', async () => {
  assert.equal(await withTimeout(Promise.resolve(7), 1000, 'x'), 7);
  await assert.rejects(withTimeout(new Promise(() => {}), 50, 'slow step'), /slow step timed out after 50ms/);
});

test('mdns stop() gives up on a hung socket instead of blocking shutdown', { timeout: 5000 }, async () => {
  const fake = Object.assign(new EventEmitter(), { respond() { /* never calls back */ }, destroy() { /* never calls back */ } });
  const logs = [];
  const adv = startMdns({ address: '127.0.0.1', port: 1, friendlyName: 'n', id: 'ab'.repeat(16), log: (...a) => logs.push(a.join(' ')), mdns: () => fake, stopTimeoutMs: 100 });
  const t0 = Date.now();
  await adv.stop();
  assert.ok(Date.now() - t0 < 2000);
  assert.ok(logs.some(l => /mdns stop/.test(l)), 'timeout logged');
});

test('SyncStore evicts the least-recently-used client, not the oldest inserted', () => {
  let t = 1000;
  const s = new SyncStore({ now: () => t, maxClients: 3 });
  const ping = c => { t += 10; s.ping({ client: c, id: 1, t: 0 }); };
  ping('a'); ping('b'); ping('c');
  ping('a'); // a is now the most recently used; b is the LRU
  ping('d');
  assert.deepEqual([...s.clients.keys()].sort(), ['a', 'c', 'd']);
});

test('postSync passes an AbortSignal that fires after the timeout', { timeout: 5000 }, async () => {
  let seen;
  const fetchFn = (url, opts) => new Promise((_, rej) => { seen = opts; opts.signal.addEventListener('abort', () => rej(opts.signal.reason)); });
  const keepAlive = setTimeout(() => {}, 3000); // AbortSignal.timeout timers are unref'd in node
  try { await assert.rejects(postSync({ id: 1 }, { fetchFn, timeoutMs: 50 })); } finally { clearTimeout(keepAlive); }
  assert.equal(seen.method, 'POST');
  assert.ok(seen.signal instanceof AbortSignal);
});

test('startSyncLoop never overlaps runs, reschedules itself, and survives errors', async () => {
  const timers = [];
  const schedule = (fn, ms) => { timers.push({ fn, ms }); return timers.length; };
  const errors = [];
  let calls = 0;
  let release;
  const once = () => { calls++; return new Promise((res, rej) => { release = calls === 2 ? () => rej(new Error('boom')) : res; }); };
  startSyncLoop({ once, schedule, burst: 2, burstMs: 5, intervalMs: 50, onError: e => errors.push(e.message) });
  assert.equal(calls, 1);
  assert.equal(timers.length, 0, 'nothing scheduled while a run is in flight');
  release(); await new Promise(r => setImmediate(r));
  assert.deepEqual(timers.map(t => t.ms), [5]);
  timers[0].fn(); // second run (rejects)
  assert.equal(calls, 2);
  release(); await new Promise(r => setImmediate(r));
  assert.deepEqual(errors, ['boom']);
  assert.deepEqual(timers.map(t => t.ms), [5, 5], 'still in burst after run 2');
  timers[1].fn(); release(); await new Promise(r => setImmediate(r));
  assert.deepEqual(timers.map(t => t.ms), [5, 5, 50], 'burst over: steady interval');
});

test('isAllowedUrl compares parsed origins, not string prefixes', () => {
  const o = 'http://127.0.0.1:8766';
  assert.equal(isAllowedUrl('http://127.0.0.1:8766/receiver/index.html', o), true);
  assert.equal(isAllowedUrl('http://127.0.0.1:8766', o), true);
  assert.equal(isAllowedUrl('http://127.0.0.1:87660/x', o), false);
  assert.equal(isAllowedUrl('http://127.0.0.1:8766.evil.example/x', o), false);
  assert.equal(isAllowedUrl('http://127.0.0.1:8766@evil.example/x', o), false);
  assert.equal(isAllowedUrl('blob:http://127.0.0.1:8766/uuid', o), true);
  assert.equal(isAllowedUrl('data:text/plain,hi', o), true);
  assert.equal(isAllowedUrl('not a url', o), false);
  assert.equal(isAllowedUrl('https://www.gstatic.com/x.js', o), false);
});

test('summarize sorts the data once', () => {
  const TA = Object.getPrototypeOf(Float64Array.prototype);
  const orig = TA.sort;
  let calls = 0;
  TA.sort = function (...a) { calls++; return orig.apply(this, a); };
  let s;
  try { s = summarize([5, 1, 9, 3, 7]); } finally { TA.sort = orig; }
  assert.equal(calls, 1);
  assert.deepEqual([s.min, s.p50, s.max], [1, 5, 9]);
});

test('parseNumArg accepts in-range numbers and rejects NaN / out of range', () => {
  assert.equal(parseNumArg('cast-port', '8009', { min: 0, max: 65535, integer: true }), 8009);
  assert.equal(parseNumArg('report-after', '2.5', { min: 0 }), 2.5);
  for (const bad of ['abc', '', '12x', '70000', '-1', 'Infinity']) {
    assert.throws(() => parseNumArg('cast-port', bad, { min: 0, max: 65535, integer: true }), /--cast-port/, bad);
  }
  assert.throws(() => parseNumArg('cast-port', '1.5', { min: 0, max: 65535, integer: true }), /integer/);
});

test('server.mjs exits 2 with a clear message on a non-numeric port', { timeout: 20000 }, () => {
  const r = spawnSync('node', ['server.mjs', '--ip', '127.0.0.1', '--no-mdns', '--no-browser', '--cast-port', 'abc'], { cwd: ROOT, encoding: 'utf8', timeout: 8000 });
  assert.equal(r.status, 2, r.stdout + r.stderr);
  assert.match(r.stderr, /--cast-port/);
});
