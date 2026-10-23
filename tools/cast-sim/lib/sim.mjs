// Wires TLS Cast server + mDNS + web server + page bridge into one sim.
import tls from 'node:tls';
import { randomBytes } from 'node:crypto';
import { PageBridge } from './pagebridge.mjs';
import { SyncStore } from './sync.mjs';
import { startWeb } from './web.mjs';
import { startMdns } from './mdns.mjs';
import { generateCert } from './tls.mjs';
import { formatTable } from './stats.mjs';
import { safeLog } from './safe.mjs';
import { DEFAULT_APP_ID } from './receiver.mjs';
import { createRegistry, handleCastConnection, makeLaunchLimiter } from './castconn.mjs';
import { makeReportWriter } from './reports.mjs';

const MAX_CONNS = 16;
const MAX_CONNS_PER_IP = 4;
const NO_LAUNCH_MS = 30_000; // hard lifetime of a connection that never LAUNCHes
const HANDSHAKE_TIMEOUT_MS = 5000;
const LAUNCHES_PER_IP_PER_MIN = 4;

function makeReporter({ bridge, sync, outDir, log, print, now }) {
  const writeReportFile = outDir ? makeReportWriter({ outDir, log, print, now }) : null;
  const getReport = async () => {
    const report = await bridge.requestReport(sync.best());
    return report ? { ...report, clients: sync.all() } : report;
  };
  let lastSessionEnd = null; // signature of the last session-end report
  const signature = r => JSON.stringify([r.frames.presented, r.frames.decoded, r.durationS]);
  async function emitReport(reason, { force = false } = {}) {
    const report = await getReport();
    if (!report || !report.frames || report.frames.presented === 0) return null;
    if (reason === 'shutdown' && lastSessionEnd === signature(report)) return null; // already reported at session end
    if (reason === 'session-end') lastSessionEnd = signature(report);
    report.reason = reason;
    print(formatTable(report));
    if (writeReportFile) await writeReportFile(report, { force });
    return report;
  }
  return { getReport, emitReport };
}

// TLS Cast listener. Pre-handshake sockets are bounded (timeout + 2x MAX_CONNS).
async function listenCast({ bind, castPort, connOpts, handshakeTimeoutMs, log }) {
  const { key, cert } = generateCert();
  const server = tls.createServer({ key, cert, handshakeTimeout: handshakeTimeoutMs }, socket => handleCastConnection(socket, connOpts));
  server.maxConnections = MAX_CONNS * 2;
  // With a listener installed Node leaves the socket open: destroy it ourselves.
  server.on('tlsClientError', (e, sock) => { log('tls client error:', e.message); sock?.destroy(); });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(castPort, bind, resolve); });
  return server;
}

export async function createSim({
  bind, castPort = 8009, httpPort = 8765, localPort = 0, receiverDir, mdns = true, friendlyName = 'Sim TV (cast-sim)',
  outDir = null, log: rawLog = console.log, print = console.log, appId = DEFAULT_APP_ID,
  noLaunchMs = NO_LAUNCH_MS, maxPerIp = MAX_CONNS_PER_IP, now = Date.now, handshakeTimeoutMs = HANDSHAKE_TIMEOUT_MS,
}) {
  const log = safeLog(rawLog);
  const token = randomBytes(32).toString('hex'); // per-run secret the headless page presents on /ws
  const bridge = new PageBridge(log);
  const sync = new SyncStore();
  const { getReport, emitReport } = makeReporter({ bridge, sync, outDir, log, print, now });
  // Session teardown (STOP / sender disconnect): flush a report once.
  let finalizing = null;
  bridge.on('teardown', opts => {
    finalizing = finalizing ?? emitReport('session-end', { force: opts?.force === true }).catch(e => log('report failed:', e.message)).finally(() => { finalizing = null; });
  });
  bridge.on('live', l => log(`live: ${l}`));

  const web = await startWeb({ bind, port: httpPort, localPort, token, receiverDir, sync, bridge, getReport, resetStats: () => bridge.resetStats(), log });

  const reg = createRegistry();
  const allowLaunch = makeLaunchLimiter({ max: LAUNCHES_PER_IP_PER_MIN, windowMs: 60_000, now });
  const connOpts = { reg, bridge, log, appId, noLaunchMs, maxPerIp, maxConns: MAX_CONNS, allowLaunch };
  const castServer = await listenCast({ bind, castPort, connOpts, handshakeTimeoutMs, log });
  const actualCastPort = castServer.address().port;

  let advert = null;
  if (mdns) {
    advert = startMdns({ address: bind, port: actualCastPort, friendlyName, id: randomBytes(16).toString('hex'), log });
    log(`mDNS: advertising "${friendlyName}" _googlecast._tcp on ${bind}:${actualCastPort}`);
  }
  const stopAdvert = async () => { const a = advert; advert = null; if (a) await a.stop(); };

  return {
    castPort: actualCastPort, httpPort: web.port, localPort: web.localPort, token, bridge, sync, getReport, emitReport, stopAdvert,
    async close() {
      await stopAdvert();
      for (const c of reg.conns) c.destroy('sim shutdown');
      await new Promise(r => { castServer.close(r); });
      await web.close();
    },
  };
}
