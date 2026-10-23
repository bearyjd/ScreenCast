// One Cast TLS connection: admission, framing, lifetime and app ownership.
import { FrameReader, frame, MAX_FRAME, encodeCastMessage } from './castproto.mjs';
import { CastReceiver } from './receiver.mjs';

export const createRegistry = () => ({ conns: new Set(), owner: null, nextConn: 1 });
const tag = ip => (ip ?? '?').split('.').slice(-1)[0];
const sameIp = (reg, ip) => [...reg.conns].filter(c => c.ip === ip).length;

function makeSend(socket, clog) {
  return msg => {
    const body = encodeCastMessage(msg);
    if (body.length > MAX_FRAME) return clog(`outgoing frame ${body.length}B exceeds the sender's ${MAX_FRAME}B limit: dropped`);
    if (!socket.destroyed) socket.write(frame(msg));
  };
}

function attachSocketHandlers(entry, clog) {
  const { socket, receiver } = entry;
  const reader = new FrameReader();
  socket.on('data', chunk => {
    let msgs;
    try { msgs = reader.push(chunk); } catch (e) { entry.reason = `protocol error: ${e.message}`; socket.destroy(); return; }
    for (const m of msgs) {
      try { receiver.onMessage(m); } catch (e) { clog('handler error:', e.message); }
    }
  });
  socket.on('end', () => { entry.reason ??= 'peer sent FIN'; });
  socket.on('error', e => { entry.reason ??= `socket error: ${e.message}`; });
}

function onClosed({ reg, entry, bridge, clog, lifetime, onPageSend }) {
  if (entry.closed) return;
  entry.closed = true;
  clearTimeout(lifetime);
  bridge.off('send', onPageSend);
  reg.conns.delete(entry);
  if (reg.owner === entry) reg.owner = null;
  clog(`closed: ${entry.reason ?? 'peer closed the connection'}${entry.receiver.app ? ' (was owner)' : ''} (open: ${reg.conns.size})`);
  entry.receiver.onClose();
}

// Any number of simultaneous connections (scanners, the app's two sockets). A
// connection only matters once it LAUNCHes; the last LAUNCH owns the app.
// Connections that never LAUNCH are reaped by a hard lifetime, never by a newer connection.
export function handleCastConnection(socket, { reg, bridge, log, appId, noLaunchMs, maxPerIp, maxConns, allowLaunch }) {
  const ip = socket.remoteAddress;
  if (reg.conns.size >= maxConns || sameIp(reg, ip) >= maxPerIp) {
    log(`refusing connection from .${tag(ip)}: ${reg.conns.size} open, ${sameIp(reg, ip)} from this address`);
    socket.destroy();
    return;
  }
  const id = `c${reg.nextConn++}`;
  const clog = (...a) => log(`[${id}]`, ...a);
  const entry = { id, ip, socket, receiver: null, reason: null, closed: false, launched: false };
  entry.receiver = new CastReceiver({
    send: makeSend(socket, clog), page: bridge, appId, log: clog, canLaunch: () => allowLaunch(ip),
    onLaunch: () => {
      entry.launched = true;
      if (reg.owner && reg.owner !== entry) {
        clog(`taking the app over from ${reg.owner.id}`);
        // Report the old session BEFORE resetStats wipes it (the request is queued ahead of the reset).
        if (reg.owner.receiver.app) bridge.teardown({ force: true });
        reg.owner.receiver.evict();
        reg.owner.destroy('evicted by a newer LAUNCH'); // no lingering connections that could pin slots
      }
      reg.owner = entry;
      bridge.resetStats();
    },
  });
  entry.destroy = why => { entry.reason = why; socket.destroy(); };
  reg.conns.add(entry);
  clog(`connected from .${tag(ip)} (open: ${reg.conns.size})`);
  const onPageSend = (ns, senderId, data) => { if (reg.owner === entry) entry.receiver.fromPage(ns, senderId, data); };
  bridge.on('send', onPageSend);
  // Hard lifetime, not an idle timer: traffic cannot keep a never-LAUNCHed connection alive.
  const lifetime = setTimeout(() => { if (!entry.launched) entry.destroy(`no LAUNCH within ${noLaunchMs / 1000}s`); }, noLaunchMs);
  attachSocketHandlers(entry, clog);
  socket.on('close', () => onClosed({ reg, entry, bridge, clog, lifetime, onPageSend }));
}

// Sliding-window limit on accepted LAUNCHes per remote IP.
export function makeLaunchLimiter({ max, windowMs, now }) {
  const hits = new Map(); // ip -> timestamps
  return ip => {
    const t = now();
    const recent = (hits.get(ip) ?? []).filter(x => t - x < windowMs);
    if (recent.length >= max) { hits.set(ip, recent); return false; }
    recent.push(t);
    hits.set(ip, recent);
    for (const [k, v] of hits) if (v.every(x => t - x >= windowMs)) hits.delete(k);
    return true;
  };
}
