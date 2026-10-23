// Minimal CAF receiver shim: stands in for cast_receiver_framework.js so the
// UNMODIFIED receiver/receiver.js runs in a plain Chromium. Implements exactly
// the surface receiver.js uses; custom-namespace traffic goes to the sim server
// over a local WebSocket (/ws). Not a general CAF implementation.
(() => {
  'use strict';
  const EventType = { SENDER_CONNECTED: 'senderconnected', SENDER_DISCONNECTED: 'senderdisconnected', READY: 'ready', SHUTDOWN: 'shutdown' };
  const MessageType = { JSON: 'JSON', STRING: 'STRING' };
  const LoggerLevel = { DEBUG: 0, VERBOSE: 1, INFO: 2, WARNING: 3, ERROR: 4, NONE: 5 };

  // Per-run token handed over in the URL fragment (never sent to a server).
  const token = new URLSearchParams(location.hash.slice(1)).get('t') || '';
  // Drop the token from the address bar/history entry once read.
  history.replaceState(null, '', location.pathname + location.search);
  const ws = new WebSocket(`ws://${location.host}/ws?t=${encodeURIComponent(token)}`);
  const outbox = [];
  const extraListeners = []; // harness hooks: fn(message)
  const wsSend = obj => {
    const s = JSON.stringify(obj);
    if (ws.readyState === 1) ws.send(s); else outbox.push(s);
  };
  ws.addEventListener('open', () => { for (const s of outbox.splice(0)) ws.send(s); });

  const customListeners = new Map(); // ns -> [cb]
  const eventListeners = new Map(); // type -> [cb]
  let registered = new Set();
  let started = false;
  let ctx = null;

  function fire(type, evt) {
    for (const cb of eventListeners.get(type) || []) {
      try { cb(evt); } catch (e) { console.error('shim event listener', e); }
    }
  }

  ws.addEventListener('message', ev => {
    let m;
    try { m = JSON.parse(ev.data); } catch { return; }
    if (m.kind === 'msg') {
      for (const cb of customListeners.get(m.ns) || []) {
        try { cb({ type: 'message', data: m.data, senderId: m.senderId }); } catch (e) { console.error('shim listener', e); }
      }
    } else if (m.kind === 'sender-disconnected') {
      fire(EventType.SENDER_DISCONNECTED, { type: EventType.SENDER_DISCONNECTED, senderId: m.senderId });
    }
    for (const fn of extraListeners) fn(m);
  });

  class CastReceiverOptions {}

  class CastReceiverContext {
    static getInstance() { return ctx || (ctx = new CastReceiverContext()); }
    setLoggerLevel() {}
    addEventListener(type, cb) {
      if (!eventListeners.has(type)) eventListeners.set(type, []);
      eventListeners.get(type).push(cb);
    }
    addCustomMessageListener(ns, cb) {
      if (!customListeners.has(ns)) customListeners.set(ns, []);
      customListeners.get(ns).push(cb);
    }
    sendCustomMessage(ns, senderId, msg) {
      // Real CAF throws for namespaces not declared in options.customNamespaces.
      if (!started || !registered.has(ns)) throw new Error(`namespace ${ns} not registered via start(options.customNamespaces)`);
      wsSend({ kind: 'send', ns, senderId: senderId || null, data: msg });
    }
    start(options) {
      started = true;
      registered = new Set(Object.keys((options && options.customNamespaces) || {}));
      wsSend({ kind: 'ready' });
    }
  }

  window.cast = {
    framework: {
      CastReceiverContext, CastReceiverOptions, LoggerLevel,
      system: { EventType, MessageType },
    },
  };
  // Side channel for the latency harness.
  window.__castSim = { send: wsSend, onMessage: fn => extraListeners.push(fn) };
})();
