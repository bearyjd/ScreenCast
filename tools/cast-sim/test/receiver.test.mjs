import test from 'node:test';
import assert from 'node:assert/strict';
import { CastReceiver, NS, RECEIVER_ID, DEFAULT_APP_ID } from '../lib/receiver.mjs';

function setup() {
  const sent = [];
  const events = [];
  const page = {
    deliver: (ns, sid, data) => events.push(['deliver', ns, sid, data]),
    senderDisconnected: sid => events.push(['disc', sid]),
    teardown: () => events.push(['teardown']),
  };
  let n = 0;
  const r = new CastReceiver({ send: m => sent.push({ ...m, json: JSON.parse(m.payloadUtf8) }), page, newId: () => `id${++n}` });
  const msg = (namespace, destinationId, obj, sourceId = 'sender-0') =>
    r.onMessage({ sourceId, destinationId, namespace, payloadUtf8: JSON.stringify(obj) });
  return { r, sent, events, msg };
}

test('PING -> PONG addressed back to the sender', () => {
  const { sent, msg } = setup();
  msg(NS.HEARTBEAT, RECEIVER_ID, { type: 'PING' });
  assert.equal(sent[0].json.type, 'PONG');
  assert.equal(sent[0].destinationId, 'sender-0');
  assert.equal(sent[0].namespace, NS.HEARTBEAT);
});

test('CONNECT receiver-0 pushes unsolicited status; GET_STATUS echoes requestId', () => {
  const { sent, msg } = setup();
  msg(NS.CONNECTION, RECEIVER_ID, { type: 'CONNECT' });
  assert.equal(sent[0].json.type, 'RECEIVER_STATUS');
  assert.equal(sent[0].json.requestId, 0);
  assert.deepEqual(sent[0].json.status.applications, []);
  msg(NS.RECEIVER, RECEIVER_ID, { type: 'GET_STATUS', requestId: 7 });
  assert.equal(sent[1].json.requestId, 7);
  assert.equal(sent[1].json.status.volume.level, 1);
});

test('receiver namespace without CONNECT is dropped', () => {
  const { sent, msg } = setup();
  msg(NS.RECEIVER, RECEIVER_ID, { type: 'GET_STATUS', requestId: 1 });
  assert.equal(sent.length, 0);
});

test('LAUNCH returns an app with the webrtc namespace, transportId and sessionId', () => {
  const { sent, msg, r } = setup();
  msg(NS.CONNECTION, RECEIVER_ID, { type: 'CONNECT' });
  msg(NS.RECEIVER, RECEIVER_ID, { type: 'LAUNCH', requestId: 2, appId: '9098830C' });
  const app = sent.at(-1).json.status.applications[0];
  assert.equal(sent.at(-1).json.requestId, 2);
  assert.equal(app.appId, '9098830C');
  assert.ok(app.transportId.startsWith('web-'));
  assert.ok(app.sessionId);
  assert.ok(app.namespaces.some(n => n.name === NS.WEBRTC));
  assert.equal(r.app.transportId, app.transportId);
  // relaunching the same app keeps the session
  msg(NS.RECEIVER, RECEIVER_ID, { type: 'LAUNCH', requestId: 3, appId: '9098830C' });
  assert.equal(sent.at(-1).json.status.applications[0].sessionId, app.sessionId);
  msg(NS.RECEIVER, RECEIVER_ID, { type: 'LAUNCH', requestId: 4 });
  assert.equal(sent.at(-1).json.type, 'LAUNCH_ERROR');
});

test('custom namespace is relayed to the page only after CONNECT to the transportId', () => {
  const { sent, events, msg } = setup();
  msg(NS.CONNECTION, RECEIVER_ID, { type: 'CONNECT' });
  msg(NS.RECEIVER, RECEIVER_ID, { type: 'LAUNCH', requestId: 1, appId: DEFAULT_APP_ID });
  const tid = sent.at(-1).json.status.applications[0].transportId;
  msg(NS.WEBRTC, tid, { type: 'OFFER', sdp: 'v=0' });
  assert.equal(events.length, 0, 'dropped without CONNECT');
  msg(NS.CONNECTION, tid, { type: 'CONNECT' });
  msg(NS.WEBRTC, tid, { type: 'OFFER', sdp: 'v=0' });
  assert.deepEqual(events.at(-1), ['deliver', NS.WEBRTC, 'sender-0', { type: 'OFFER', sdp: 'v=0' }]);
});

test('CONNECT to an unknown destination is answered with CLOSE', () => {
  const { sent, msg } = setup();
  msg(NS.CONNECTION, 'web-nope', { type: 'CONNECT' });
  assert.equal(sent[0].json.type, 'CLOSE');
});

test('fromPage sends ANSWER from the transportId to the sender; broadcast uses the known sender', () => {
  const { sent, msg, r } = setup();
  msg(NS.CONNECTION, RECEIVER_ID, { type: 'CONNECT' });
  msg(NS.RECEIVER, RECEIVER_ID, { type: 'LAUNCH', requestId: 1, appId: DEFAULT_APP_ID });
  const tid = r.app.transportId;
  r.fromPage(NS.WEBRTC, null, { type: 'READY' });
  assert.equal(sent.at(-1).sourceId, tid);
  assert.equal(sent.at(-1).destinationId, 'sender-0');
  r.fromPage(NS.WEBRTC, 'sender-0', { type: 'ANSWER', sdp: 's' });
  assert.deepEqual(sent.at(-1).json, { type: 'ANSWER', sdp: 's' });
});

test('SET_VOLUME clamps and echoes; mute only changes muted', () => {
  const { sent, msg } = setup();
  msg(NS.CONNECTION, RECEIVER_ID, { type: 'CONNECT' });
  msg(NS.RECEIVER, RECEIVER_ID, { type: 'SET_VOLUME', requestId: 5, volume: { level: 3 } });
  assert.equal(sent.at(-1).json.status.volume.level, 1);
  msg(NS.RECEIVER, RECEIVER_ID, { type: 'SET_VOLUME', requestId: 6, volume: { level: 0.25 } });
  msg(NS.RECEIVER, RECEIVER_ID, { type: 'SET_VOLUME', requestId: 7, volume: { muted: true } });
  assert.deepEqual([sent.at(-1).json.status.volume.level, sent.at(-1).json.status.volume.muted], [0.25, true]);
});

test('STOP clears apps, tears the page down, echoes requestId', () => {
  const { sent, events, msg, r } = setup();
  msg(NS.CONNECTION, RECEIVER_ID, { type: 'CONNECT' });
  msg(NS.RECEIVER, RECEIVER_ID, { type: 'LAUNCH', requestId: 1, appId: DEFAULT_APP_ID });
  const sid = r.app.sessionId;
  msg(NS.RECEIVER, RECEIVER_ID, { type: 'STOP', requestId: 9, sessionId: sid });
  assert.equal(sent.at(-1).json.requestId, 9);
  assert.deepEqual(sent.at(-1).json.status.applications, []);
  assert.deepEqual(events.map(e => e[0]), ['disc', 'teardown']);
  assert.equal(r.app, null);
});

test('sender disconnect tears the page down once', () => {
  const { events, msg, r } = setup();
  msg(NS.CONNECTION, RECEIVER_ID, { type: 'CONNECT' });
  msg(NS.RECEIVER, RECEIVER_ID, { type: 'LAUNCH', requestId: 1, appId: DEFAULT_APP_ID });
  r.onClose();
  r.onClose();
  assert.equal(events.filter(e => e[0] === 'teardown').length, 1);
  assert.equal(events.filter(e => e[0] === 'disc').length, 1);
});

test('closing a connection that never launched does not touch the page', () => {
  const { events, msg, r } = setup();
  msg(NS.CONNECTION, RECEIVER_ID, { type: 'CONNECT' });
  r.onClose();
  assert.deepEqual(events, []);
});

test('garbage payloads are ignored', () => {
  const { sent, r } = setup();
  r.onMessage({ sourceId: 's', destinationId: RECEIVER_ID, namespace: NS.RECEIVER, payloadUtf8: 'not json' });
  r.onMessage({ sourceId: 's', destinationId: RECEIVER_ID, namespace: NS.RECEIVER, payloadUtf8: 'null' });
  assert.equal(sent.length, 0);
});

test('fromPage relays only the WebRTC namespace to the sender (allowlist)', () => {
  const { sent, msg, r } = setup();
  msg(NS.CONNECTION, RECEIVER_ID, { type: 'CONNECT' });
  msg(NS.RECEIVER, RECEIVER_ID, { type: 'LAUNCH', requestId: 1, appId: DEFAULT_APP_ID });
  const before = sent.length;
  r.fromPage(NS.RECEIVER, 'sender-0', { type: 'RECEIVER_STATUS' });
  r.fromPage(NS.CONNECTION, 'sender-0', { type: 'CLOSE' });
  r.fromPage('urn:x-cast:evil', 'sender-0', { type: 'X' });
  assert.equal(sent.length, before, 'nothing relayed for non-WebRTC namespaces');
  r.fromPage(NS.WEBRTC, 'sender-0', { type: 'ANSWER' });
  assert.equal(sent.length, before + 1);
});

test('LAUNCH accepts only the expected appId; others get LAUNCH_ERROR NOT_FOUND', () => {
  const { sent, msg, r } = setup();
  msg(NS.CONNECTION, RECEIVER_ID, { type: 'CONNECT' });
  msg(NS.RECEIVER, RECEIVER_ID, { type: 'LAUNCH', requestId: 1, appId: 'DEADBEEF' });
  assert.deepEqual([sent.at(-1).json.type, sent.at(-1).json.reason, sent.at(-1).json.requestId], ['LAUNCH_ERROR', 'NOT_FOUND', 1]);
  assert.equal(r.app, null);
  msg(NS.RECEIVER, RECEIVER_ID, { type: 'LAUNCH', requestId: 2, appId: DEFAULT_APP_ID });
  assert.equal(r.app.appId, '9098830C');
});

test('the expected appId is configurable', () => {
  const sent = [];
  const page = { deliver() {}, senderDisconnected() {}, teardown() {} };
  const r = new CastReceiver({ send: m => sent.push(JSON.parse(m.payloadUtf8)), page, appId: 'CUSTOM01' });
  const msg = obj => r.onMessage({ sourceId: 's', destinationId: RECEIVER_ID, namespace: NS.RECEIVER, payloadUtf8: JSON.stringify(obj) });
  r.onMessage({ sourceId: 's', destinationId: RECEIVER_ID, namespace: NS.CONNECTION, payloadUtf8: '{"type":"CONNECT"}' });
  msg({ type: 'LAUNCH', requestId: 1, appId: '9098830C' });
  assert.equal(sent.at(-1).reason, 'NOT_FOUND');
  msg({ type: 'LAUNCH', requestId: 2, appId: 'CUSTOM01' });
  assert.equal(r.app.appId, 'CUSTOM01');
});
