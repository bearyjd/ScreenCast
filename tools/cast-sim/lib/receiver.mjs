// Cast V2 receiver state machine for ONE sender connection. Transport-free:
// the caller supplies send(castMessage) and a page bridge, so it is testable
// with a fake socket. Implements only what the ScreenCast sender uses.

export const NS = {
  CONNECTION: 'urn:x-cast:com.google.cast.tp.connection',
  HEARTBEAT: 'urn:x-cast:com.google.cast.tp.heartbeat',
  RECEIVER: 'urn:x-cast:com.google.cast.receiver',
  // Must match WEBRTC_NAMESPACE in webrtc/WebRtcConfig.kt and receiver.js.
  WEBRTC: 'urn:x-cast:io.github.ddagunts.screencast.webrtc',
};
export const RECEIVER_ID = 'receiver-0';
// The Cast App ID the ScreenCast sender launches; any other LAUNCH is NOT_FOUND.
export const DEFAULT_APP_ID = '9098830C';
export const MAX_PAYLOAD_FRAME = 65536;

export class CastReceiver {
  // opts.send(msg): write a CastMessage to the sender.
  // opts.page: { deliver(ns, senderId, data), senderDisconnected(senderId), teardown() }
  constructor({ send, page, appId = DEFAULT_APP_ID, log = () => {}, onLaunch = () => {}, canLaunch = () => true, newId = () => Math.random().toString(16).slice(2, 10) }) {
    this.send = send;
    this.page = page;
    this.log = log;
    this.onLaunch = onLaunch; // called before this connection takes over the (single) app
    this.canLaunch = canLaunch; // false -> LAUNCH_ERROR (per-IP rate limit)
    this.newId = newId;
    this.expectedAppId = appId;
    this.connections = new Set(); // destination ids with an open virtual connection
    this.senderId = null; // source id of the (single) sender
    this.app = null; // {appId, sessionId, transportId}
    this.volume = { level: 1, muted: false };
  }

  #reply(destination, ns, obj, source = RECEIVER_ID) {
    this.send({ sourceId: source, destinationId: destination, namespace: ns, payloadUtf8: JSON.stringify(obj) });
  }

  #status() {
    const applications = this.app
      ? [{
        appId: this.app.appId,
        displayName: 'ScreenCast Sim Receiver',
        isIdleScreen: false,
        launchedFromCloud: false,
        namespaces: [{ name: NS.WEBRTC }, { name: NS.CONNECTION }, { name: NS.HEARTBEAT }],
        sessionId: this.app.sessionId,
        statusText: 'cast-sim',
        transportId: this.app.transportId,
        universalAppId: this.app.appId,
      }]
      : [];
    return {
      applications,
      isActiveInput: true,
      volume: { controlType: 'attenuation', level: this.volume.level, muted: this.volume.muted, stepInterval: 0.05 },
    };
  }

  #sendStatus(to, requestId) {
    this.#reply(to, NS.RECEIVER, { type: 'RECEIVER_STATUS', requestId, status: this.#status() });
  }

  onMessage(msg) {
    const { sourceId, destinationId, namespace } = msg;
    if (msg.payloadBinary != null && !msg.payloadUtf8) return this.log('ignoring BINARY payload on', namespace);
    let obj;
    try { obj = JSON.parse(msg.payloadUtf8); } catch { return this.log('non-JSON payload on', namespace); }
    if (!obj || typeof obj !== 'object') return;
    if (!this.senderId) this.senderId = sourceId;
    const type = obj.type;
    const requestId = obj.requestId ?? 0;

    if (namespace === NS.HEARTBEAT) {
      if (type === 'PING') this.#reply(sourceId, NS.HEARTBEAT, { type: 'PONG' }, destinationId || RECEIVER_ID);
      return;
    }
    if (namespace === NS.CONNECTION) return this.#onConnection(msg, type);

    if (namespace === NS.RECEIVER) {
      if (destinationId !== RECEIVER_ID || !this.connections.has(RECEIVER_ID)) {
        return this.log('receiver ns without virtual connection -> dropped');
      }
      if (type === 'GET_STATUS') return this.#sendStatus(sourceId, requestId);
      if (type === 'LAUNCH') return this.#launch(sourceId, obj);
      if (type === 'STOP') return this.#stop(sourceId, obj);
      if (type === 'SET_VOLUME') return this.#setVolume(sourceId, obj);
      return this.log('unhandled receiver message', type);
    }

    if (this.app && destinationId === this.app.transportId) {
      if (!this.connections.has(this.app.transportId)) {
        this.log(`message to ${destinationId} without CONNECT -> dropped (real devices drop it too)`);
        return;
      }
      // Only the WebRTC namespace is relayed (verbatim) to the receiver page.
      if (namespace !== NS.WEBRTC) return this.log(`inbound message on ${namespace} -> dropped (not the WebRTC namespace)`);
      this.page.deliver(namespace, sourceId, obj);
      return;
    }
    this.log('unrouted message', namespace, '->', destinationId);
  }

  #onConnection(msg, type) {
    const { sourceId, destinationId } = msg;
    if (type === 'CONNECT') {
      const known = destinationId === RECEIVER_ID || (this.app && destinationId === this.app.transportId);
      if (!known) {
        this.#reply(sourceId, NS.CONNECTION, { type: 'CLOSE' }, destinationId);
        return;
      }
      this.connections.add(destinationId);
      this.log(`CONNECT ${sourceId} -> ${destinationId}`);
      // Real devices push an unsolicited status (requestId 0) after CONNECT.
      if (destinationId === RECEIVER_ID) this.#sendStatus(sourceId, 0);
    } else if (type === 'CLOSE') {
      this.connections.delete(destinationId);
      this.log(`CLOSE ${sourceId} -> ${destinationId}`);
    }
  }

  #launch(sender, obj) {
    const appId = String(obj.appId ?? '');
    if (!appId) {
      this.#reply(sender, NS.RECEIVER, { type: 'LAUNCH_ERROR', requestId: obj.requestId ?? 0, reason: 'BAD_PARAMETER' });
      return;
    }
    if (appId !== this.expectedAppId) {
      this.#reply(sender, NS.RECEIVER, { type: 'LAUNCH_ERROR', requestId: obj.requestId ?? 0, reason: 'NOT_FOUND' });
      return;
    }
    if (!this.app || this.app.appId !== appId) {
      if (!this.canLaunch()) {
        this.log('LAUNCH rate limit reached -> LAUNCH_ERROR');
        this.#reply(sender, NS.RECEIVER, { type: 'LAUNCH_ERROR', requestId: obj.requestId ?? 0, reason: 'RATE_LIMITED' });
        return;
      }
      if (this.app) this.page.teardown();
      this.onLaunch();
      this.app = { appId, sessionId: `${this.newId()}-${this.newId()}`, transportId: `web-${this.newId()}` };
      this.log(`LAUNCH ${appId} -> transportId ${this.app.transportId}`);
    }
    this.#sendStatus(sender, obj.requestId ?? 0);
  }

  #stop(sender, obj) {
    if (this.app && (!obj.sessionId || obj.sessionId === this.app.sessionId)) {
      this.log(`STOP session ${this.app.sessionId}: app ${this.app.appId} cleared`);
      this.connections.delete(this.app.transportId);
      this.app = null;
      this.page.senderDisconnected(sender);
      this.page.teardown();
    }
    this.#sendStatus(sender, obj.requestId ?? 0);
  }

  #setVolume(sender, obj) {
    const v = obj.volume ?? {};
    const level = typeof v.level === 'number' ? Math.min(1, Math.max(0, v.level)) : this.volume.level;
    const muted = typeof v.muted === 'boolean' ? v.muted : this.volume.muted;
    this.volume = { level, muted };
    this.#sendStatus(sender, obj.requestId ?? 0);
  }

  // Page -> sender custom-namespace message. senderId null = broadcast.
  fromPage(ns, senderId, data) {
    if (ns !== NS.WEBRTC) return this.log('page message on non-WebRTC namespace -> dropped');
    if (!this.app) return this.log('page message with no running app -> dropped');
    const dest = senderId || this.senderId;
    if (!dest) return this.log('page message but no sender yet -> dropped');
    const payloadUtf8 = typeof data === 'string' ? data : JSON.stringify(data);
    this.send({ sourceId: this.app.transportId, destinationId: dest, namespace: ns, payloadUtf8 });
  }

  // This connection lost the app to another connection's LAUNCH.
  evict() {
    if (!this.app) return;
    this.log(`app ${this.app.appId} evicted by another connection's LAUNCH`);
    this.app = null;
    this.connections.clear();
    this.page.senderDisconnected(this.senderId);
  }

  // Socket closed/errored. Only a connection that owns the app may touch the
  // page: scanners and idle connections must never disturb a real session.
  onClose() {
    const hadApp = this.app != null;
    if (hadApp) this.log(`owner gone: session ${this.app.sessionId} ended`);
    this.app = null;
    this.connections.clear();
    if (hadApp) {
      if (this.senderId) this.page.senderDisconnected(this.senderId);
      this.page.teardown();
    }
  }
}
