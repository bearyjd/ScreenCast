// Two HTTP listeners. PUBLIC (LAN) serves only the phone clock page, its assets
// and /time /sync /offset. LOCAL (127.0.0.1 only) additionally serves the
// receiver files, /report, /frame.png, POST /reset and the page WebSocket /ws
// (token-protected). Every request must carry the exact expected Host header.
import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const ROOT = path.resolve(HERE, '..');
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css' };
const MAX_BODY = 4096;
const HEADERS_TIMEOUT_MS = 10_000;
const REQUEST_TIMEOUT_MS = 15_000;
const MAX_PUBLIC_CONNECTIONS = 64;
export const TOKEN_HEADER = 'x-cast-sim-token';
const NOT_FOUND_CODES = new Set(['ENOENT']);
const WS_MAX_PAYLOAD = 4 << 20;
const CLOCK_ASSETS = new Set(['/page/clock.mjs', '/lib/barcode.mjs', '/lib/stats.mjs', '/lib/syncloop.mjs']);
const isJson = req => /^application\/json\s*(;|$)/i.test(req.headers['content-type'] ?? '');

async function serveFile(res, baseDir, rel, log) {
  const full = path.resolve(baseDir, '.' + path.posix.normalize('/' + rel));
  if (full !== baseDir && !full.startsWith(baseDir + path.sep)) { res.writeHead(403).end('forbidden'); return; }
  try {
    const body = await readFile(full);
    res.writeHead(200, { 'content-type': TYPES[path.extname(full)] ?? 'application/octet-stream', 'cache-control': 'no-store' });
    res.end(body);
  } catch (e) {
    if (NOT_FOUND_CODES.has(e.code)) { res.writeHead(404).end('not found'); return; }
    log('static read error', full, e.code ?? e.message);
    res.writeHead(500).end('internal error');
  }
}

function readJson(req) {
  if (!isJson(req)) return Promise.reject(Object.assign(new Error('content-type must be application/json'), { status: 415 }));
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', c => {
      size += c.length;
      if (size > MAX_BODY) reject(Object.assign(new Error('body too large'), { status: 413 })); // rest of the body is discarded
      else if (size <= MAX_BODY) chunks.push(c);
    });
    req.on('end', () => { try { resolve(JSON.parse(Buffer.concat(chunks).toString() || '{}')); } catch (e) { reject(e); } });
    req.on('error', reject);
  });
}

// Hosts the browser sends for `hosts` on `port` (the default HTTP port 80 is omitted from Host).
export const expectedHosts = (hosts, port) => hosts.map(h => (port === 80 ? h : `${h}:${port}`));

function tokenOk(expected, given) {
  const a = Buffer.from(String(given ?? ''));
  const b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
}

const json = (res, code, obj) => res.writeHead(code, { 'content-type': 'application/json', 'cache-control': 'no-store' }).end(JSON.stringify(obj));

// Routes both listeners serve. Returns true when handled.
async function sharedRoute(ctx, req, res, p, scope) {
  if (p === '/favicon.ico') { res.writeHead(204).end(); return true; }
  if (req.method === 'GET' && p === '/time') { json(res, 200, { t: Date.now() }); return true; }
  if (req.method === 'POST' && p === '/sync') { json(res, 200, ctx.sync.ping(await readJson(req))); return true; }
  if (req.method === 'GET' && p === '/offset') { json(res, 200, ctx.sync.best() ?? { offsetMs: null }); return true; }
  if (req.method !== 'GET') return false;
  if (p === '/' || p === '/clock.html') { await serveFile(res, path.join(ROOT, 'page'), 'clock.html', ctx.log); return true; }
  if (scope === 'public' && !CLOCK_ASSETS.has(p)) return false;
  if (p.startsWith('/page/')) { await serveFile(res, path.join(ROOT, 'page'), p.slice(6), ctx.log); return true; }
  if (p.startsWith('/lib/')) { await serveFile(res, path.join(ROOT, 'lib'), p.slice(5), ctx.log); return true; }
  return false;
}

async function frameRoute(ctx, res, url) {
  const q = url.searchParams;
  const dataUrl = await ctx.bridge.requestFrame({ crop: q.get('crop') === '1', overlay: q.get('overlay') !== '0' });
  const m = typeof dataUrl === 'string' && /^data:image\/png;base64,/.exec(dataUrl);
  if (!m) return json(res, 404, { error: 'no frame (no receiver page, or no video yet)' });
  res.writeHead(200, { 'content-type': 'image/png', 'cache-control': 'no-store' }).end(Buffer.from(dataUrl.slice(m[0].length), 'base64'));
}

// Debug routes: loopback listener only.
async function localRoute(ctx, req, res, p, url) {
  const needsToken = (req.method === 'GET' && (p === '/report' || p === '/frame.png')) || (req.method === 'POST' && p === '/reset');
  if (needsToken && !tokenOk(ctx.token, req.headers[TOKEN_HEADER] ?? url.searchParams.get('t'))) {
    json(res, 401, { error: `token required (header ${TOKEN_HEADER} or ?t=)` });
    return true;
  }
  if (req.method === 'GET' && p === '/report') { json(res, 200, (await ctx.getReport()) ?? { error: 'no receiver page connected or report timed out' }); return true; }
  if (req.method === 'GET' && p === '/frame.png') { await frameRoute(ctx, res, url); return true; }
  if (req.method === 'POST' && p === '/reset') {
    await readJson(req); // enforces application/json
    ctx.resetStats();
    json(res, 200, { ok: true });
    return true;
  }
  if (req.method === 'GET' && p.startsWith('/receiver/')) { await serveFile(res, ctx.receiverDir, p.slice(10), ctx.log); return true; }
  return false;
}

const makeHandler = (ctx, scope) => async (req, res) => {
  try {
    if (!ctx.allowedHosts(scope).includes(req.headers.host)) return void res.writeHead(421).end('misdirected request');
    const url = new URL(req.url, 'http://x');
    const p = url.pathname;
    if (await sharedRoute(ctx, req, res, p, scope)) return;
    if (scope === 'local' && await localRoute(ctx, req, res, p, url)) return;
    if (req.method !== 'GET' && !(req.method === 'POST' && (p === '/sync' || p === '/reset'))) return void json(res, 405, { error: 'method not allowed' });
    res.writeHead(404).end('not found');
  } catch (e) {
    ctx.log('http error', req.url, e.message);
    if (!res.headersSent) {
      if (e.status === 413) res.setHeader('connection', 'close');
      json(res, e.status ?? 400, { error: e.message });
    }
  }
};

// /ws: loopback listener, exact Host, same-origin (if any) and the per-run token.
function upgradeAllowed(ctx, req) {
  const url = new URL(req.url, 'http://x');
  if (url.pathname !== '/ws') return false;
  const host = req.headers.host;
  if (!ctx.allowedHosts('local').includes(host)) return false;
  const origin = req.headers.origin;
  if (origin !== undefined && origin !== `http://${host}`) return false;
  return tokenOk(ctx.token, url.searchParams.get('t'));
}

const listen = (server, p, host) => new Promise((resolve, reject) => {
  server.once('error', reject);
  server.listen(p, host, () => { server.off('error', reject); resolve(server.address().port); });
});
const closeServer = server => new Promise(r => { server.close(r); server.closeAllConnections(); });

export async function startWeb({ bind, port, localPort = 0, receiverDir, sync, bridge, getReport, resetStats, token, log }) {
  const ports = { public: 0, local: 0 };
  const allowedHosts = scope => (scope === 'public'
    ? expectedHosts([bind], ports.public)
    : expectedHosts(['127.0.0.1', 'localhost'], ports.local));
  const ctx = { allowedHosts, sync, bridge, getReport, resetStats, receiverDir, token, log };
  const wss = new WebSocketServer({ noServer: true, maxPayload: WS_MAX_PAYLOAD });
  const publicServer = http.createServer(makeHandler(ctx, 'public'));
  publicServer.maxConnections = MAX_PUBLIC_CONNECTIONS;
  publicServer.on('upgrade', (req, socket) => socket.destroy());
  const localServer = http.createServer(makeHandler(ctx, 'local'));
  for (const srv of [publicServer, localServer]) { srv.headersTimeout = HEADERS_TIMEOUT_MS; srv.requestTimeout = REQUEST_TIMEOUT_MS; }
  localServer.on('upgrade', (req, socket, head) => {
    let allowed = false;
    try { allowed = upgradeAllowed(ctx, req); } catch (e) { log('upgrade rejected:', e.message); }
    if (!allowed) return socket.destroy();
    wss.handleUpgrade(req, socket, head, ws => bridge.attach(ws));
  });
  ports.public = await listen(publicServer, port, bind);
  try { ports.local = await listen(localServer, localPort, '127.0.0.1'); } catch (e) { await closeServer(publicServer); throw e; }
  return {
    port: ports.public,
    localPort: ports.local,
    servers: { public: publicServer, local: localServer },
    close: async () => { wss.clients.forEach(c => c.terminate()); await Promise.all([closeServer(publicServer), closeServer(localServer)]); },
  };
}
