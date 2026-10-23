// ScreenCast WebRTC — Cast custom receiver that consumes a WebRTC video
// stream from the Android sender and renders it fullscreen.
// Fork modification (2026-10-18): isolate sender ownership and negotiations.
//
// Namespace must match WEBRTC_NAMESPACE in
// app/src/main/java/io/github/ddagunts/screencast/webrtc/WebRtcConfig.kt.
const NAMESPACE = 'urn:x-cast:io.github.ddagunts.screencast.webrtc';

const video = document.getElementById('video');
const statusEl = document.getElementById('status');

function setStatus(text) {
  if (!statusEl) return;
  statusEl.textContent = text;
}
function hideStatus() {
  if (!statusEl) return;
  statusEl.classList.add('hidden');
}

// Surface any sync JS error and any unhandled promise rejection onto the
// #status element — that's the only feedback channel the TV screen gives us
// without remote debugging. The marker "JS:" distinguishes this from the
// static HTML default text, proving our script actually ran.
window.addEventListener('error', (evt) => {
  setStatus(`JS: ${evt.message} @ ${(evt.filename || '').split('/').pop()}:${evt.lineno}`);
});
window.addEventListener('unhandledrejection', (evt) => {
  const r = evt.reason;
  setStatus(`JS promise: ${r && (r.message || r.toString ? r.toString() : r)}`);
});

// Prove the script loaded at all; CAF init happens right after.
setStatus('receiver.js loaded, starting CAF…');

if (!window.cast || !cast.framework) {
  setStatus('JS: cast.framework missing — CAF SDK did not load');
  throw new Error('cast.framework missing');
}

const context = cast.framework.CastReceiverContext.getInstance();
// Route CAF's internal logs to the browser console. If remote debugging is
// ever enabled on the Chromecast, they'll show up in DevTools.
if (cast.framework.LoggerLevel) {
  context.setLoggerLevel(cast.framework.LoggerLevel.DEBUG);
}
const options = new cast.framework.CastReceiverOptions();
// Two separate timers, both must be tamed for a long-lived WebRTC cast:
//   • disableIdleTimeout — CAF auto-closes the receiver after ~5 min if no
//     media has played through its media pipeline. We bypass CAF media
//     entirely (video/audio go through RTCPeerConnection, not <video> via
//     CAF), so CAF always sees the receiver as idle. Without this flag the
//     cast drops at the 5-minute mark, no matter what maxInactivity is set
//     to. This is the symptom users hit before this line existed.
//   • maxInactivity — the sender heartbeat threshold. CAF will close an
//     idle sender connection after this many seconds. Our Cast V2 channel
//     PINGs every 5 s anyway, so this rarely matters, but a long ceiling
//     keeps us safe if the sender stalls briefly.
options.disableIdleTimeout = true;
options.maxInactivity = 24 * 60 * 60;
// Register our custom namespace. CAF refuses sendCustomMessage calls on a
// namespace it doesn't know about, so this has to come before start().
// Regular `{}` (not Object.create(null)) — some CAF code paths call
// prototype methods on this map.
options.customNamespaces = {};
options.customNamespaces[NAMESPACE] =
  cast.framework.system.MessageType.JSON;

// Object identity is the local negotiation generation. Capture it across
// awaits: an older OFFER must never operate on a replacement peer.
let activeSession = null;

function closeSession(session) {
  if (activeSession !== session) return;
  activeSession = null;
  if (session.peer) {
    try { session.peer.close(); } catch (_) {}
  }
  video.srcObject = null;
}

function createPeer(session) {
  // Empty iceServers list: both peers are on the same LAN, so host candidates
  // are enough; no STUN round-trip, no TURN relays.
  const p = new RTCPeerConnection({ iceServers: [] });
  session.peer = p;

  // Both remote tracks (video, audio) land in a single MediaStream wired to
  // the fullscreen <video> element. Two reasons not to split them:
  //   1. The sender's stream IDs may be distinct (screen-video / screen-audio)
  //      or shared; this code never reads evt.streams, so both work. We
  //      compose the tracks on this side either way.
  //   2. Routing audio via Web Audio's createMediaStreamSource() is a
  //      long-standing Chrome bug for WebRTC remote tracks — the source
  //      node attaches but produces no output. HTMLMediaElement is the
  //      reliable path; the <video> element already plays the video, so
  //      adding the audio track to the same stream gives us audio for
  //      free, no second element needed.
  // We do NOT set video.muted = true. Chromecast's CAF receiver is a
  // trusted context that permits unmuted autoplay, and Chrome's autoplay
  // policy has been observed to commit to a muted state if set on load,
  // silently reverting later video.muted = false. See the receiver
  // index.html comment for the matching note on the HTML side.
  const remoteStream = new MediaStream();
  video.srcObject = remoteStream;

  p.ontrack = (evt) => {
    if (activeSession !== session) return;
    const kind = evt.track && evt.track.kind || '?';
    console.log('ontrack', kind);
    remoteStream.addTrack(evt.track);
    setStatus(`track: ${kind} (tracks: ${remoteStream.getTracks().length})`);
    const vp = video.play();
    if (vp && typeof vp.catch === 'function') {
      vp.catch(err => {
        if (activeSession === session) setStatus(`video.play failed: ${err && err.name || err}`);
      });
    }
  };

  p.onicecandidate = (evt) => {
    if (activeSession !== session) return;
    if (!evt.candidate) return;
    const payload = {
      type: 'ICE',
      candidate: {
        candidate: evt.candidate.candidate,
        sdpMid: evt.candidate.sdpMid,
        sdpMLineIndex: evt.candidate.sdpMLineIndex,
      },
    };
    sendSignal(payload, session.senderId);
  };

  p.onconnectionstatechange = () => {
    if (activeSession !== session) return;
    const s = p.connectionState;
    console.log('pc state', s);
    if (s === 'connected') hideStatus();
    else if (s === 'failed') {
      closeSession(session);
      setStatus('WebRTC connection failed');
    }
    else if (s === 'disconnected') setStatus('Sender disconnected');
    else setStatus(`pc state: ${s}`);
  };
  p.oniceconnectionstatechange = () => {
    if (activeSession !== session) return;
    setStatus(`ice: ${p.iceConnectionState}`);
  };

  return p;
}

function sendSignal(obj, senderId = null) {
  // With a known senderId we respond directly; before that (e.g. READY on
  // startup) we broadcast — the CAF framework rejects broadcasts to non-
  // senders with a warning but delivers them to every attached sender.
  try {
    context.sendCustomMessage(NAMESPACE, senderId, obj);
  } catch (e) {
    console.warn('sendCustomMessage', e);
  }
}

context.addCustomMessageListener(NAMESPACE, async (evt) => {
  const msg = evt.data;
  if (!msg || typeof msg !== 'object') return;
  if (typeof evt.senderId !== 'string' || !evt.senderId) return;
  if (activeSession && evt.senderId !== activeSession.senderId) return;
  if (msg.type === 'OFFER' && (typeof msg.sdp !== 'string' || !msg.sdp.trim())) return;
  let session = activeSession;
  try {
    if (msg.type === 'OFFER') {
      // Tear down any previous peer — a closed or half-configured one can't
      // process a new OFFER. This lets the receiver serve back-to-back casts
      // without requiring a page reload.
      if (session) closeSession(session);
      session = { senderId: evt.senderId, peer: null, remoteReady: false, candidates: [] };
      activeSession = session;
      const pc = createPeer(session);
      setStatus('OFFER received, setting remote…');
      await pc.setRemoteDescription({ type: 'offer', sdp: msg.sdp });
      if (activeSession !== session) return;
      session.remoteReady = true;
      for (const candidate of session.candidates) {
        await pc.addIceCandidate(candidate);
        if (activeSession !== session) return;
      }
      session.candidates = [];
      setStatus('remote set, creating answer…');
      const answer = await pc.createAnswer();
      if (activeSession !== session) return;
      setStatus('answer created, setting local…');
      await pc.setLocalDescription(answer);
      if (activeSession !== session) return;
      setStatus('answer set, sending…');
      sendSignal({ type: 'ANSWER', sdp: answer.sdp }, session.senderId);
      setStatus('ANSWER sent, awaiting media');
    } else if (msg.type === 'ICE' && msg.candidate) {
      // Trickle candidates — empty candidate string signals end-of-gather
      // from the sender. Safe to pass through addIceCandidate; Chrome ignores
      // an end-of-candidate marker.
      if (!session) return;
      const c = msg.candidate;
      if (c.candidate) {
        const candidate = {
          candidate: c.candidate,
          sdpMid: c.sdpMid,
          sdpMLineIndex: c.sdpMLineIndex,
        };
        if (!session.remoteReady) session.candidates.push(candidate);
        else await session.peer.addIceCandidate(candidate);
      }
    } else if (msg.type === 'BYE') {
      if (!session) return;
      console.log('sender sent BYE');
      setStatus('Sender ended the cast');
      closeSession(session);
    }
  } catch (err) {
    if (activeSession !== session) return;
    if (msg.type === 'OFFER') closeSession(session);
    // Surface the failure on-screen. The try/catch used to only log to console,
    // which is invisible on the Chromecast without remote DevTools. When
    // setRemoteDescription or createAnswer throws, the TV now tells us which
    // step failed so we can diagnose from the couch.
    console.error('signal handler', msg && msg.type, err);
    const name = err && (err.name || 'Error');
    const message = err && (err.message || String(err));
    setStatus(`signal ${msg.type} failed: ${name}: ${message}`);
  }
});

context.addEventListener(cast.framework.system.EventType.SENDER_DISCONNECTED, (evt) => {
  if (!activeSession || evt.senderId !== activeSession.senderId) return;
  closeSession(activeSession);
  setStatus('Sender disconnected');
});

setStatus('calling context.start()…');
try {
  context.start(options);
} catch (e) {
  setStatus(`start() threw: ${e && (e.message || e)}`);
  throw e;
}
// "CAF ready" marker distinguishes live JS from the static HTML default.
setStatus('CAF ready — waiting for sender');
// Announce readiness — the sender treats this as a hint but doesn't block
// on it (the OFFER is sent regardless after LAUNCH completes).
sendSignal({ type: 'READY' });
