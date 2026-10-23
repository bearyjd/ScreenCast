// Server <-> receiver page link over a local WebSocket. Implements the `page`
// interface CastReceiver expects. Messages to the page are queued until the
// shim reports `ready` (i.e. receiver.js has registered its listeners).
import { EventEmitter } from 'node:events';

const MAX_QUEUE = 200;
const MAX_REPORT_BYTES = 1 << 20;

export class PageBridge extends EventEmitter {
  constructor(log = () => {}) {
    super();
    this.log = log;
    this.ws = null;
    this.ready = false;
    this.queue = [];
    this.nextReq = 1;
    this.pendingReports = new Map();
  }

  attach(ws) {
    if (this.ws) { try { this.ws.close(); } catch { /* replaced */ } }
    this.ws = ws;
    this.ready = false;
    ws.on('message', raw => {
      let m;
      try { m = JSON.parse(raw.toString()); } catch { return this.log('bad page message'); }
      if (!m || typeof m !== 'object') return this.log('bad page message');
      if (m.kind === 'ready') { this.ready = true; this.#flush(); this.emit('ready'); }
      else if (m.kind === 'send') this.emit('send', m.ns, m.senderId ?? null, m.data);
      else if (m.kind === 'report' || m.kind === 'frame') {
        const p = this.pendingReports.get(m.id);
        if (p) { this.pendingReports.delete(m.id); clearTimeout(p.timer); p.resolve(m.kind === 'report' ? this.#boundedReport(m.report) : m.dataUrl); }
      } else if (m.kind === 'live') this.emit('live', m.live);
    });
    ws.on('error', e => this.log('page ws error:', e.message));
    ws.on('close', () => { if (this.ws === ws) { this.ws = null; this.ready = false; } });
  }

  #boundedReport(report) {
    if (JSON.stringify(report ?? null).length <= MAX_REPORT_BYTES) return report;
    this.log(`report dropped: larger than ${MAX_REPORT_BYTES} bytes`);
    return null;
  }

  #post(obj) {
    if (this.ws && this.ready && this.ws.readyState === 1) this.ws.send(JSON.stringify(obj));
    else if (this.queue.length < MAX_QUEUE) this.queue.push(obj);
    else this.log('page queue full, dropping message');
  }

  #flush() {
    const q = this.queue;
    this.queue = [];
    for (const o of q) this.#post(o);
  }

  deliver(ns, senderId, data) { this.#post({ kind: 'msg', ns, senderId, data }); }
  senderDisconnected(senderId) { this.#post({ kind: 'sender-disconnected', senderId }); }
  teardown(opts) { this.emit('teardown', opts); }
  resetStats() { this.#post({ kind: 'reset' }); }

  // Ask the page for a latency report; resolves null on timeout/no page.
  requestReport(offset, timeoutMs = 5000) { return this.#request({ kind: 'report-request', offset }, timeoutMs); }

  // Latest presented video frame as a PNG data URL (null on timeout).
  requestFrame({ crop = false, overlay = true } = {}, timeoutMs = 5000) {
    return this.#request({ kind: 'frame-request', crop, overlay }, timeoutMs);
  }

  #request(msg, timeoutMs) {
    if (!this.ws || !this.ready || this.ws.readyState !== 1) return Promise.resolve(null);
    const id = this.nextReq++;
    return new Promise(resolve => {
      const timer = setTimeout(() => { this.pendingReports.delete(id); resolve(null); }, timeoutMs);
      this.pendingReports.set(id, { resolve, timer });
      this.ws.send(JSON.stringify({ ...msg, id }));
    });
  }
}
