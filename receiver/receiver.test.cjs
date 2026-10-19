// Fork addition (2026-10-18): behavioral receiver isolation tests.
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const vm = require('node:vm');

const source = readFileSync(`${__dirname}/receiver.js`, 'utf8');

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  let enter;
  const entered = new Promise(yes => { enter = yes; });
  return { promise, resolve, reject, entered, enter };
}

function receiver(peerOptions = []) {
  const peers = [], messages = [], events = new Map();
  const video = { srcObject: null, play: () => Promise.resolve() };
  const status = { textContent: '', classList: { add() {}, remove() {} } };
  let listener;
  const context = {
    setLoggerLevel() {}, start() {},
    addCustomMessageListener(_namespace, handler) { listener = handler; },
    addEventListener(type, handler) { events.set(type, handler); },
    sendCustomMessage(namespace, senderId, data) { messages.push({ namespace, senderId, ...data }); },
  };
  class Peer {
    constructor() {
      this.closed = 0;
      this.candidates = [];
      this.localDescriptions = [];
      Object.assign(this, peerOptions[peers.length]);
      peers.push(this);
    }
    async setRemoteDescription(description) {
      this.remoteDescription = description;
      if (this.remoteGate) {
        this.remoteGate.enter();
        await this.remoteGate.promise;
      }
    }
    async createAnswer() {
      if (this.answerGate) {
        this.answerGate.enter();
        return await this.answerGate.promise;
      }
      return { type: 'answer', sdp: `answer-${peers.indexOf(this)}` };
    }
    async setLocalDescription(description) {
      this.localDescriptions.push(description);
      if (this.localGate) {
        this.localGate.enter();
        await this.localGate.promise;
      }
    }
    async addIceCandidate(candidate) { this.candidates.push(candidate); }
    close() { this.closed++; }
  }
  const cast = { framework: {
    CastReceiverContext: { getInstance: () => context },
    CastReceiverOptions: class {},
    system: { MessageType: { JSON: 'JSON' }, EventType: { SENDER_DISCONNECTED: 'disconnect' } },
  } };
  vm.runInNewContext(source, {
    document: { getElementById: id => id === 'video' ? video : status },
    window: { cast, addEventListener() {} }, cast,
    console: { log() {}, warn() {}, error() {} },
    RTCPeerConnection: Peer,
    MediaStream: class {
      constructor() { this.tracks = []; }
      addTrack(track) { this.tracks.push(track); }
      getTracks() { return this.tracks; }
    },
  }, { filename: 'receiver.js' });
  return {
    peers, messages, video, status,
    signal: (senderId, data) => listener({ senderId, data }),
    disconnect: senderId => events.get('disconnect')?.({ senderId }),
  };
}

const offer = { type: 'OFFER', sdp: 'v=0\r\n' };
const ice = { type: 'ICE', candidate: { candidate: 'candidate:1', sdpMid: '0', sdpMLineIndex: 0 } };

test('another sender cannot stop the active owner', async () => {
  const r = receiver();
  await r.signal('A', offer);
  await r.signal('B', { type: 'BYE' });
  assert.equal(r.peers[0].closed, 0);
  assert.ok(r.video.srcObject);
  await r.signal('A', { type: 'BYE' });
  assert.equal(r.peers[0].closed, 1);
});

test('another sender cannot inject ICE, take over, or retarget owner replies', async () => {
  const r = receiver();
  await r.signal('A', offer);
  await r.signal('B', ice);
  await r.signal('B', offer);
  assert.equal(r.peers.length, 1);
  assert.equal(r.peers[0].candidates.length, 0);
  r.peers[0].onicecandidate({ candidate: ice.candidate });
  assert.equal(r.messages.at(-1).senderId, 'A');
});

test('same-owner replacement invalidates an answer still being created', async () => {
  const gate = deferred();
  const r = receiver([{ answerGate: gate }]);
  const pending = r.signal('A', offer);
  const old = r.peers[0];
  await gate.entered;
  await r.signal('A', offer);
  const currentStream = r.video.srcObject;
  gate.resolve({ type: 'answer', sdp: 'stale-answer' });
  await pending;
  assert.equal(old.closed, 1);
  assert.equal(r.peers[1].closed, 0);
  assert.equal(r.peers[1].localDescriptions.length, 1);
  assert.equal(r.messages.filter(m => m.type === 'ANSWER').length, 1);
  assert.equal(r.messages.find(m => m.type === 'ANSWER').sdp, 'answer-1');
  assert.equal(r.video.srcObject, currentStream);
});

test('late events from a replaced peer cannot send, render, or change status', async () => {
  const r = receiver();
  await r.signal('A', offer);
  const old = r.peers[0];
  await r.signal('A', { type: 'BYE' });
  await r.signal('B', offer);
  const currentStream = r.video.srcObject;
  const currentStatus = r.status.textContent;
  const messageCount = r.messages.length;
  old.onicecandidate({ candidate: ice.candidate });
  old.ontrack({ track: { kind: 'video' } });
  old.connectionState = 'failed';
  old.onconnectionstatechange();
  old.oniceconnectionstatechange();
  assert.equal(r.messages.length, messageCount);
  assert.equal(r.status.textContent, currentStatus);
  assert.equal(r.video.srcObject, currentStream);
  assert.equal(currentStream.getTracks().length, 0);
  assert.equal(r.peers[1].closed, 0);
});

test('only the owner disconnect releases the peer and permits a new sender', async () => {
  const r = receiver();
  await r.signal('A', offer);
  r.disconnect('B');
  assert.equal(r.peers[0].closed, 0);
  r.disconnect('A');
  assert.equal(r.peers[0].closed, 1);
  assert.equal(r.video.srcObject, null);
  r.disconnect('A');
  assert.equal(r.peers[0].closed, 1);
  await r.signal('B', offer);
  assert.equal(r.peers.length, 2);
  assert.equal(r.messages.at(-1).senderId, 'B');
});

test('a failed offer closes its peer, releases ownership, and preserves the error', async () => {
  const gate = deferred();
  const r = receiver([{ remoteGate: gate }]);
  const pending = r.signal('A', offer);
  gate.reject(new Error('bad offer'));
  await pending;
  assert.equal(r.peers[0].closed, 1);
  assert.equal(r.video.srcObject, null);
  assert.match(r.status.textContent, /bad offer/);
  await r.signal('B', offer);
  assert.equal(r.peers.length, 2);
  assert.equal(r.messages.at(-1).senderId, 'B');
});

test('ICE arriving before remote SDP waits and then applies to the same peer', async () => {
  const gate = deferred();
  const r = receiver([{ remoteGate: gate }]);
  const pending = r.signal('A', offer);
  await r.signal('A', ice);
  assert.equal(r.peers[0].candidates.length, 0);
  gate.resolve();
  await pending;
  assert.equal(r.peers[0].candidates.length, 1);
  assert.equal(r.peers[0].candidates[0].candidate, 'candidate:1');
  await r.signal('A', ice);
  assert.equal(r.peers[0].candidates.length, 2);
});

test('malformed or anonymous offers cannot replace the owner session', async () => {
  const r = receiver();
  await r.signal('A', offer);
  const current = r.video.srcObject;
  for (const data of [null, 'OFFER', {}, { type: 'OFFER' }, { type: 'OFFER', sdp: '' }, { type: 'OFFER', sdp: 7 }]) {
    await r.signal('A', data);
  }
  await r.signal(null, offer);
  assert.equal(r.peers.length, 1);
  assert.equal(r.peers[0].closed, 0);
  assert.equal(r.video.srcObject, current);
});

test('failed peer releases ownership while a transient disconnect keeps it', async () => {
  const r = receiver();
  await r.signal('A', offer);
  const peer = r.peers[0];
  peer.connectionState = 'disconnected';
  peer.onconnectionstatechange();
  await r.signal('B', offer);
  assert.equal(r.peers.length, 1);
  assert.equal(peer.closed, 0);
  peer.connectionState = 'failed';
  peer.onconnectionstatechange();
  assert.equal(peer.closed, 1);
  assert.equal(r.video.srcObject, null);
  assert.equal(r.status.textContent, 'WebRTC connection failed');
  await r.signal('B', offer);
  assert.equal(r.peers.length, 2);
});

// Exercise every await boundary against both a late result and a late error.
// The input/output oracle is ownership: only B can emit an answer or remain live.
for (const stage of ['remoteGate', 'answerGate', 'localGate']) {
  for (const fails of [false, true]) {
    test(`stale ${stage} ${fails ? 'failure' : 'success'} cannot affect a new owner`, async () => {
      const gate = deferred();
      const r = receiver([{ [stage]: gate }]);
      const pending = r.signal('A', offer);
      await gate.entered;
      await r.signal('A', { type: 'BYE' });
      await r.signal('B', offer);
      const stream = r.video.srcObject;
      const status = r.status.textContent;
      if (fails) gate.reject(new Error('old negotiation failed'));
      else gate.resolve({ type: 'answer', sdp: 'old answer' });
      await pending;
      assert.equal(r.peers[0].closed, 1);
      assert.equal(r.peers[1].closed, 0);
      assert.equal(r.peers[1].localDescriptions.length, 1);
      assert.equal(r.video.srcObject, stream);
      assert.equal(r.status.textContent, status);
      const answers = r.messages.filter(m => m.type === 'ANSWER');
      assert.equal(answers.length, 1);
      assert.equal(answers[0].senderId, 'B');
      assert.equal(answers[0].sdp, 'answer-1');
    });
  }
}

test('queued ICE from an old offer cannot enter its replacement peer', async () => {
  const gate = deferred();
  const r = receiver([{ remoteGate: gate }]);
  const pending = r.signal('A', offer);
  await r.signal('A', ice);
  await r.signal('A', offer);
  gate.resolve();
  await pending;
  assert.equal(r.peers[0].candidates.length, 0);
  assert.equal(r.peers[1].candidates.length, 0);
});

test('late video play rejection cannot replace the active owner status', async () => {
  const r = receiver();
  const gate = deferred();
  r.video.play = () => gate.promise;
  await r.signal('A', offer);
  r.peers[0].ontrack({ track: { kind: 'video' } });
  assert.equal(r.video.srcObject.getTracks().length, 1);
  await r.signal('A', { type: 'BYE' });
  await r.signal('B', offer);
  const status = r.status.textContent;
  gate.reject(new Error('old play failure'));
  await Promise.resolve();
  assert.equal(r.status.textContent, status);
});

for (const stage of ['answerGate', 'localGate']) {
  test(`current ${stage} failure releases its own peer and permits a new owner`, async () => {
    const gate = deferred();
    const r = receiver([{ [stage]: gate }]);
    const pending = r.signal('A', offer);
    await gate.entered;
    gate.reject(new Error('negotiation rejected'));
    await pending;
    assert.equal(r.peers[0].closed, 1);
    assert.equal(r.video.srcObject, null);
    assert.match(r.status.textContent, /negotiation rejected/);
    assert.equal(r.messages.filter(m => m.type === 'ANSWER').length, 0);
    await r.signal('B', offer);
    assert.equal(r.peers.length, 2);
    assert.equal(r.messages.at(-1).senderId, 'B');
  });
}
