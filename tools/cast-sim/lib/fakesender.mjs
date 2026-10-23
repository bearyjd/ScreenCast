// Minimal Cast V2 sender (TLS client, trusts any cert like the Android app) used
// by tests and selftest.mjs.
import tls from 'node:tls';
import { EventEmitter } from 'node:events';
import { FrameReader, frame } from './castproto.mjs';
import { NS, RECEIVER_ID } from './receiver.mjs';

export class FakeSender extends EventEmitter {
  constructor(sourceId = 'sender-0') {
    super();
    this.sourceId = sourceId;
    this.received = [];
    this.reqId = 1;
  }

  connect(host, port) {
    return new Promise((resolve, reject) => {
      const reader = new FrameReader();
      this.socket = tls.connect({ host, port, rejectUnauthorized: false }, () => resolve(this));
      this.socket.on('data', c => {
        for (const m of reader.push(c)) {
          const parsed = { ...m, json: safeJson(m.payloadUtf8) };
          this.received.push(parsed);
          this.emit('message', parsed);
        }
      });
      this.socket.once('error', reject);
      this.socket.on('close', () => this.emit('close'));
    });
  }

  send(namespace, destinationId, obj) {
    this.socket.write(frame({
      sourceId: this.sourceId, destinationId, namespace, payloadUtf8: typeof obj === 'string' ? obj : JSON.stringify(obj),
    }));
  }

  request(namespace, obj) {
    const requestId = this.reqId++;
    this.send(namespace, RECEIVER_ID, { ...obj, requestId });
    return this.waitFor(m => m.json?.requestId === requestId);
  }

  waitFor(pred, timeoutMs = 5000) {
    const hit = this.received.find(pred);
    if (hit) return Promise.resolve(hit);
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { this.off('message', on); reject(new Error('timeout waiting for message')); }, timeoutMs);
      const on = m => { if (pred(m)) { clearTimeout(t); this.off('message', on); resolve(m); } };
      this.on('message', on);
    });
  }

  // CONNECT + LAUNCH + CONNECT(app), like WebRtcCastSession.start().
  async launch(appId = '9098830C') {
    this.send(NS.CONNECTION, RECEIVER_ID, { type: 'CONNECT' });
    const status = await this.request(NS.RECEIVER, { type: 'LAUNCH', appId });
    const app = status.json.status.applications[0];
    this.send(NS.CONNECTION, app.transportId, { type: 'CONNECT' });
    return app;
  }

  close() { this.socket?.destroy(); }
}

function safeJson(s) { try { return JSON.parse(s); } catch { return null; } }
