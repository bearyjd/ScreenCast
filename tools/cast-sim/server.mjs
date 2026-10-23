#!/usr/bin/env node
// cast-sim: fake Chromecast + latency harness. DEV TOOL: advertises a fake Cast
// device on your LAN while running. See README.md.
import path from 'node:path';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { createSim } from './lib/sim.mjs';
import { lanAddresses } from './lib/net.mjs';
import { launchBrowser, openReceiverPage } from './lib/browser.mjs';
import { parseNumArg, parseBindIp, describeListenError } from './lib/cli.mjs';
import { withTimeout } from './lib/timeout.mjs';
import { runShutdown } from './lib/shutdown.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const { values: o } = parseArgs({
  options: {
    ip: { type: 'string', default: process.env.CAST_SIM_IP },
    'cast-port': { type: 'string', default: process.env.CAST_SIM_CAST_PORT ?? '8009' },
    'http-port': { type: 'string', default: process.env.CAST_SIM_HTTP_PORT ?? '8765' },
    'local-port': { type: 'string', default: process.env.CAST_SIM_LOCAL_PORT ?? '8766' },
    name: { type: 'string', default: process.env.CAST_SIM_NAME ?? 'Sim TV (cast-sim)' },
    receiver: { type: 'string', default: process.env.CAST_SIM_RECEIVER_DIR ?? path.resolve(HERE, '../../receiver') },
    out: { type: 'string', default: process.env.CAST_SIM_OUT },
    chrome: { type: 'string', default: process.env.CHROME_PATH ?? '/usr/bin/chromium' },
    'no-mdns': { type: 'boolean', default: false },
    'no-browser': { type: 'boolean', default: false },
    'report-after': { type: 'string' },
    help: { type: 'boolean', short: 'h', default: false },
  },
});
if (o.help) {
  console.log('usage: node server.mjs [--ip LAN_IPV4] [--cast-port 8009] [--http-port 8765] [--local-port 8766] [--name NAME] [--receiver DIR]\n'
    + '       [--out DIR] [--chrome PATH] [--report-after SECONDS] [--no-mdns] [--no-browser]');
  process.exit(0);
}

const PORT = { min: 0, max: 65535, integer: true };
let ports;
let reportAfterS = null;
try {
  ports = {
    cast: parseNumArg('cast-port', o['cast-port'], PORT),
    http: parseNumArg('http-port', o['http-port'], PORT),
    local: parseNumArg('local-port', o['local-port'], PORT),
  };
  if (o.ip !== undefined) o.ip = parseBindIp(o.ip);
  if (o['report-after'] !== undefined) reportAfterS = parseNumArg('report-after', o['report-after'], { min: 0, max: 86400 });
} catch (e) {
  console.error(e.message);
  process.exit(2);
}

const log = (...a) => console.log(new Date().toISOString().slice(11, 23), ...a);
let bind = o.ip;
if (!bind) {
  const cands = lanAddresses();
  if (!cands.length) { console.error('no private LAN IPv4 found; pass --ip'); process.exit(2); }
  bind = cands[0].address;
  if (cands.length > 1) log(`multiple LAN addresses ${cands.map(c => `${c.name}=${c.address}`).join(', ')}; using ${bind} (override with --ip)`);
}

console.log('WARNING: cast-sim advertises a FAKE Chromecast on your LAN while it runs. Dev tool only; stop it when done.');
let sim;
try {
  sim = await createSim({
    bind, castPort: ports.cast, httpPort: ports.http, localPort: ports.local, receiverDir: path.resolve(o.receiver),
    mdns: !o['no-mdns'], friendlyName: o.name, outDir: o.out ? path.resolve(o.out) : null, log,
  });
} catch (e) {
  console.error(describeListenError(e));
  process.exit(2);
}
const base = `http://${bind}:${sim.httpPort}`;
const local = `http://127.0.0.1:${sim.localPort}`;
log(`Cast TLS on ${bind}:${sim.castPort} | clock page ${base}/clock.html`);
log('debug routes (loopback only, need this run\'s token):');
log(`  curl -H 'x-cast-sim-token: ${sim.token}' ${local}/report`);
log(`  curl -H 'x-cast-sim-token: ${sim.token}' -o frame.png '${local}/frame.png?crop=1'`);
log(`  curl -X POST -H 'x-cast-sim-token: ${sim.token}' -H 'content-type: application/json' -d '{}' ${local}/reset`);

let browser = null;
if (!o['no-browser']) {
  const userDataDir = mkdtempSync(path.join(tmpdir(), 'cast-sim-chrome-'));
  browser = await launchBrowser({ executablePath: o.chrome, userDataDir, log });
  await openReceiverPage(browser, { origin: local, token: sim.token, log });
  log('receiver page running (real receiver/index.html + receiver.js, CAF shim)');
}

let stopping = false;
async function shutdown(sig) {
  if (stopping) return;
  stopping = true;
  log(`${sig}: shutting down`);
  try { await withTimeout(runShutdown({ sim, browser, log }), 3000, 'shutdown'); } catch (e) { log(e.message, '- exiting anyway'); }
  process.exit(0);
}
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
if (reportAfterS !== null) setTimeout(() => sim.emitReport('timer').catch(e => log('report failed:', e.message)), reportAfterS * 1000);
