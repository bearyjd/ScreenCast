#!/usr/bin/env node
// Loopback self-test with no phone: starts the sim on 127.0.0.1, runs the real
// receiver page in Chromium, and plays the SENDER with a second Chromium tab
// (clock.html canvas -> RTCPeerConnection) driven through the real Cast TLS
// protocol by FakeSender. Prints the latency report. Needs Chromium/Chrome.
import path from 'node:path';
import { mkdtempSync, existsSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { createSim } from './lib/sim.mjs';
import { FakeSender } from './lib/fakesender.mjs';
import { NS } from './lib/receiver.mjs';
import { launchBrowser, openReceiverPage } from './lib/browser.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEBRTC = 'urn:x-cast:io.github.ddagunts.screencast.webrtc';
const seconds = Number(process.env.SELFTEST_SECONDS ?? 8);
const chrome = process.env.CHROME_PATH
  ?? ['/usr/bin/chromium', '/usr/bin/google-chrome'].find(p => existsSync(p));
const bind = process.env.SELFTEST_BIND ?? '127.0.0.1';
const log = (...a) => console.log('[selftest]', ...a);

const sim = await createSim({ bind, castPort: 0, httpPort: 0, receiverDir: path.resolve(HERE, '../../receiver'), mdns: false, log: process.env.DEBUG ? console.log : () => {} });
const origin = `http://${bind}:${sim.httpPort}`; // LAN listener (clock page)
const local = `http://127.0.0.1:${sim.localPort}`; // loopback listener (receiver, debug routes)
const browser = await launchBrowser({ executablePath: chrome, userDataDir: mkdtempSync(path.join(tmpdir(), 'cast-sim-st-')) });
let failed = null;
let senderBrowser = null;
try {
  await openReceiverPage(browser, { origin: local, token: sim.token, log: m => { if (process.env.DEBUG || /error|fail/i.test(m)) log(m); } });
  // Separate browser for the sender: a hidden tab in the receiver browser would stop presenting frames.
  senderBrowser = await launchBrowser({ executablePath: chrome, userDataDir: mkdtempSync(path.join(tmpdir(), 'cast-sim-st2-')) });
  const senderPage = await senderBrowser.newPage();
  // Phone-like sender: portrait viewport; the frame sent is 1920x1080 with the page pillarboxed under a toolbar band.
  const fullFrame = process.env.SELFTEST_FULLFRAME === '1';
  if (!fullFrame) await senderPage.setViewport({ width: 411, height: 891, deviceScaleFactor: 2.6 });
  await senderPage.goto(`${origin}/clock.html`);
  await new Promise(r => setTimeout(r, 2500)); // let the clock sync converge

  const sender = await new FakeSender().connect(bind, sim.castPort);
  const app = await sender.launch();
  log('launched', app.transportId);

  await senderPage.exposeFunction('__onIce', c => sender.send(WEBRTC, app.transportId, { type: 'ICE', candidate: c }));
  const codec = process.env.SELFTEST_CODEC; // e.g. H264, VP9: prefer this video codec
  const offer = await senderPage.evaluate(async (codec, fullFrame) => {
    const pc = window.__pc = new RTCPeerConnection({ iceServers: [] });
    pc.onicecandidate = e => { if (e.candidate) window.__onIce({ candidate: e.candidate.candidate, sdpMid: e.candidate.sdpMid, sdpMLineIndex: e.candidate.sdpMLineIndex }); };
    const src = document.getElementById('c');
    let vs;
    if (fullFrame) vs = src.captureStream(30);
    else {
      const TB = 114; // Chrome toolbar band, as seen in a real capture
      const comp = document.createElement('canvas');
      comp.width = 1920; comp.height = 1080; comp.style.display = 'none';
      document.body.appendChild(comp);
      const g = comp.getContext('2d', { alpha: false });
      const loop = () => {
        const dw = Math.round(src.width * ((1080 - TB) / src.height));
        const dx = Math.floor((1920 - dw) / 2);
        g.fillStyle = '#000'; g.fillRect(0, 0, 1920, 1080);
        g.fillStyle = '#e8eaed'; g.fillRect(dx, 0, dw, TB);
        g.fillStyle = '#5f6368'; g.fillRect(dx + 20, 30, dw - 120, 54);
        for (let i = 0; i < 6; i++) g.fillRect(dx + 20 + i * 70, 94, 40, 12);
        g.drawImage(src, dx, TB, dw, 1080 - TB);
        requestAnimationFrame(loop);
      };
      requestAnimationFrame(loop);
      vs = comp.captureStream(30);
    }
    vs.getVideoTracks().forEach(t => { t.contentHint = 'detail'; }); // keep resolution like the phone's fixed 1080p preset
    vs.getTracks().forEach(t => pc.addTrack(t, vs));
    if (codec) {
      const tr = pc.getTransceivers().find(t => t.sender.track?.kind === 'video');
      const caps = RTCRtpSender.getCapabilities('video').codecs;
      tr.setCodecPreferences([...caps.filter(c => c.mimeType === `video/${codec}`), ...caps.filter(c => c.mimeType !== `video/${codec}`)]);
    }
    const ac = new AudioContext();
    const osc = ac.createOscillator();
    const dest = ac.createMediaStreamDestination();
    osc.connect(dest); osc.start();
    dest.stream.getTracks().forEach(t => pc.addTrack(t, dest.stream));
    for (const sender of pc.getSenders()) {
      if (sender.track?.kind !== 'video') continue;
      const p = sender.getParameters();
      p.encodings = [{ ...(p.encodings?.[0] ?? {}), maxBitrate: 3_000_000, scaleResolutionDownBy: 1 }];
      p.degradationPreference = 'maintain-resolution';
      await sender.setParameters(p).catch(() => {});
    }
    const o = await pc.createOffer();
    await pc.setLocalDescription(o);
    return o.sdp;
  }, codec, fullFrame);
  sender.send(WEBRTC, app.transportId, { type: 'OFFER', sdp: offer });
  const answer = await sender.waitFor(m => m.namespace === WEBRTC && m.json?.type === 'ANSWER', 10000);
  await senderPage.evaluate(sdp => window.__pc.setRemoteDescription({ type: 'answer', sdp }), answer.json.sdp);
  sender.on('message', m => {
    if (m.namespace === WEBRTC && m.json?.type === 'ICE' && m.json.candidate?.candidate) {
      senderPage.evaluate(c => window.__pc.addIceCandidate(c), m.json.candidate).catch(() => {});
    }
  });
  for (const m of sender.received) {
    if (m.namespace === WEBRTC && m.json?.type === 'ICE' && m.json.candidate?.candidate) {
      await senderPage.evaluate(c => window.__pc.addIceCandidate(c), m.json.candidate);
    }
  }
  log(`casting for ${seconds}s...`);
  await new Promise(r => setTimeout(r, seconds * 1000));
  const report = await sim.emitReport('selftest');
  if (!report) throw new Error('no report (no frames decoded?)');
  if (report.latencyMs.count < 10) throw new Error(`too few latency samples: ${report.latencyMs.count}`);
  if (!report.decode?.box) throw new Error('barcode box not reported');
  const png = await fetch(`${local}/frame.png?crop=1`, { headers: { 'x-cast-sim-token': sim.token } });
  const pngBytes = Buffer.from(await png.arrayBuffer());
  if (png.status !== 200 || pngBytes.subarray(1, 4).toString() !== 'PNG' || pngBytes.length < 1000) throw new Error(`bad /frame.png (${png.status}, ${pngBytes.length} B)`);
  if (process.env.SELFTEST_SAVE_PNG) writeFileSync(process.env.SELFTEST_SAVE_PNG, pngBytes);
  const unauth = await fetch(`${local}/report`);
  if (unauth.status !== 401) throw new Error(`/report without the token returned ${unauth.status}, expected 401`);
  log(`/frame.png ok (${pngBytes.length} B), box ${JSON.stringify(report.decode.box)}`);
  if (!(report.latencyMs.p50 > -50 && report.latencyMs.p50 < 3000)) throw new Error(`implausible p50 ${report.latencyMs.p50}`);
  if (report.frames.decodeFailurePct > 50) throw new Error(`decode failure ${report.frames.decodeFailurePct}%`);
  sender.send(NS.RECEIVER, 'receiver-0', { type: 'STOP', requestId: 99, sessionId: app.sessionId });
  await sender.waitFor(m => m.json?.requestId === 99);
  sender.close();
  log('PASS');
} catch (e) {
  failed = e;
  log('FAIL:', e.message);
} finally {
  await senderBrowser?.close().catch(() => {});
  await browser.close().catch(() => {});
  await sim.close().catch(() => {});
}
process.exit(failed ? 1 : 0);
