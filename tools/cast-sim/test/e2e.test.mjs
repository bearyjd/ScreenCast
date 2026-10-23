// Full loopback (real Chromium receiver + Chromium "sender"); skipped without a browser.
import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const chrome = process.env.CHROME_PATH ?? ['/usr/bin/chromium', '/usr/bin/google-chrome'].find(p => existsSync(p));
test('selftest: unmodified receiver.js receives a WebRTC cast and reports latency', { skip: !chrome && 'no Chromium found' }, () => {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const r = spawnSync('node', ['selftest.mjs'], { cwd: root, env: { ...process.env, CHROME_PATH: chrome, SELFTEST_SECONDS: '5' }, encoding: 'utf8', timeout: 120000 });
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /cast-sim latency report/);
});
