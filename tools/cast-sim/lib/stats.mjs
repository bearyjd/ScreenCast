// Percentiles, NTP-style clock offset, WebRTC stats digestion and report
// formatting. Isomorphic (browser + node).

const sortedCopy = values => Float64Array.from(values).sort();

export function percentile(values, p) {
  return percentileOfSorted(sortedCopy(values), p);
}

function percentileOfSorted(s, p) {
  if (!s.length) return null;
  if (s.length === 1) return s[0];
  const rank = (Math.min(100, Math.max(0, p)) / 100) * (s.length - 1); // linear interpolation
  const lo = Math.floor(rank);
  const hi = Math.ceil(rank);
  return s[lo] + (s[hi] - s[lo]) * (rank - lo);
}

export function summarize(values) {
  if (!values.length) return { count: 0, min: null, p50: null, p90: null, p99: null, max: null, mean: null };
  let min = Infinity;
  let max = -Infinity;
  let sum = 0;
  for (const v of values) { if (v < min) min = v; if (v > max) max = v; sum += v; }
  const sorted = sortedCopy(values);
  return {
    count: values.length, min, p50: percentileOfSorted(sorted, 50), p90: percentileOfSorted(sorted, 90),
    p99: percentileOfSorted(sorted, 99), max, mean: sum / values.length,
  };
}

// offset = server_time - (page_send + rtt/2): add it to a page clock reading
// to get server time. tSend/tRecv are page Date.now(), tServer is server arrival.
export function offsetSample({ tSend, tRecv, tServer }) {
  const rtt = tRecv - tSend;
  return { rtt, offset: tServer - (tSend + rtt / 2) };
}

// Min-RTT sample over the most recent `window` samples (lowest queueing noise).
export function bestOffset(samples, { window = 60 } = {}) {
  const recent = samples.slice(-window);
  if (!recent.length) return null;
  let best = recent[0];
  for (const s of recent) if (s.rtt < best.rtt) best = s;
  return { offsetMs: best.offset, rttMs: best.rtt, uncertaintyMs: best.rtt / 2, samples: recent.length };
}

const ms = s => (s == null ? null : s * 1000);
const ratio = (num, den, scale = 1) => (den > 0 ? (num / den) * scale : null);

// entries: Array.from(RTCStatsReport.values()).
export function summarizeRtcStats(entries) {
  const byId = new Map(entries.map(e => [e.id, e]));
  const codecOf = e => byId.get(e.codecId)?.mimeType ?? null;
  const out = { video: null, audio: null, rttMs: null };
  for (const e of entries) {
    if (e.type !== 'inbound-rtp' || e.isRemote) continue;
    const kind = e.kind ?? e.mediaType;
    if (kind === 'video') {
      out.video = {
        codec: codecOf(e),
        width: e.frameWidth ?? null, height: e.frameHeight ?? null,
        framesPerSecond: e.framesPerSecond ?? null,
        framesReceived: e.framesReceived ?? null, framesDecoded: e.framesDecoded ?? null,
        framesDropped: e.framesDropped ?? null, freezeCount: e.freezeCount ?? null,
        jitterBufferDelayAvgMs: ratio(e.jitterBufferDelay, e.jitterBufferEmittedCount, 1000),
        decodeTimeAvgMs: ratio(e.totalDecodeTime, e.framesDecoded, 1000),
        jitterMs: ms(e.jitter), packetsLost: e.packetsLost ?? null,
      };
    } else if (kind === 'audio') {
      out.audio = {
        codec: codecOf(e),
        jitterBufferDelayAvgMs: ratio(e.jitterBufferDelay, e.jitterBufferEmittedCount, 1000),
        concealedSamples: e.concealedSamples ?? null, totalSamplesReceived: e.totalSamplesReceived ?? null,
        concealedPct: ratio(e.concealedSamples, e.totalSamplesReceived, 100),
        jitterMs: ms(e.jitter), packetsLost: e.packetsLost ?? null,
      };
    }
  }
  const transport = entries.find(e => e.type === 'transport');
  const pair = (transport && byId.get(transport.selectedCandidatePairId))
    ?? entries.find(e => e.type === 'candidate-pair' && e.nominated && e.state === 'succeeded')
    ?? entries.find(e => e.type === 'candidate-pair' && e.state === 'succeeded');
  if (pair && pair.currentRoundTripTime != null) out.rttMs = pair.currentRoundTripTime * 1000;
  return out;
}

export const LATENCY_CAVEAT = 'Latency = phone-side barcode draw -> capture -> encode -> network -> jitter buffer -> decode -> '
  + 'frame presentation in headless Chromium. It EXCLUDES the phone display latency and any real TV panel/HDMI/'
  + 'Chromecast-hardware delay. Accuracy is bounded by the phone/sim clock-offset uncertainty (see offset.uncertaintyMs).';

const f = (v, d = 1) => (v == null || Number.isNaN(v) ? 'n/a' : Number(v).toFixed(d));

const row = (label, L) => `${label.padEnd(15)} ${String(L.count).padStart(5)} `
  + [L.min, L.p50, L.p90, L.p99, L.max, L.mean].map(v => f(v).padStart(7)).join(' ');

export function formatTable(r) {
  const L = r.latencyMs;
  const lines = [
    '== cast-sim latency report ==',
    `window ${f(r.durationS, 1)} s | frames presented ${r.frames.presented} (${f(r.frames.fps, 1)}/s) | `
      + `decode failures ${r.frames.decodeFailures} (${f(r.frames.decodeFailurePct, 1)}%)`,
    `clock offset ${r.offset ? `${f(r.offset.offsetMs)} ms (+/-${f(r.offset.uncertaintyMs)} ms, ${r.offset.samples} samples, client ${r.offset.client ?? '?'})` : 'UNSYNCED (assumed 0: open /clock.html on the phone)'}`,
    '',
    'latency (ms)    count     min     p50     p90     p99     max    mean',
    row('to display', L),
    ...(r.latencyAtCallbackMs ? [row('at callback', r.latencyAtCallbackMs)] : []),
    ...(r.latencyRejected ? [`outliers outside -1..10 s rejected ${r.latencyRejected}`] : []),
    ...(r.clients?.length ? ['sync clients: ' + r.clients.map(c => `${c.client} ${f(c.offsetMs)} ms (rtt ${f(c.rttMs, 0)}, age ${f(c.ageMs / 1000, 0)} s)`).join('; ')] : []),
    '',
  ];
  const d = r.decode;
  if (d) {
    const fr = d.failReasons ?? {};
    lines.push(`barcode: ${d.box ? `located at x=${f(d.box.x, 0)} y=${f(d.box.y, 0)} ${f(d.box.w, 0)}x${f(d.box.h, 0)} px (cell ${f(d.box.cell, 2)}) in ${d.frameWidth ?? '?'}x${d.frameHeight ?? '?'}` : 'NOT LOCATED'}`
      + ` | fails: not-found ${fr.notFound ?? 0}, sync-mismatch ${fr.syncMismatch ?? 0}, checksum ${fr.checksum ?? 0}, implausible ${fr.implausible ?? 0}`);
  }
  const v = r.webrtc?.video;
  const a = r.webrtc?.audio;
  lines.push(v
    ? `video: ${v.codec ?? '?'} ${v.width ?? '?'}x${v.height ?? '?'} | jitterBuffer ${f(v.jitterBufferDelayAvgMs)} ms | decode ${f(v.decodeTimeAvgMs, 2)} ms | `
      + `dropped ${v.framesDropped ?? 'n/a'} | freezes ${v.freezeCount ?? 'n/a'} | lost ${v.packetsLost ?? 'n/a'}`
    : 'video: no inbound-rtp stats');
  lines.push(a
    ? `audio: ${a.codec ?? '?'} | jitterBuffer ${f(a.jitterBufferDelayAvgMs)} ms | concealed ${a.concealedSamples ?? 'n/a'} samples (${f(a.concealedPct, 2)}%)`
    : 'audio: no inbound-rtp stats');
  lines.push(`ice rtt: ${f(r.webrtc?.rttMs)} ms`);
  lines.push('', `NOTE: ${LATENCY_CAVEAT}`);
  return lines.join('\n');
}

const LATENCY_MIN_MS = -1000;
const LATENCY_MAX_MS = 10_000;

// samples: [{recvMs, phoneMs}] (receiver wall clock == sim server clock; phone
// clock converted with `offset`). Pure function; used by the page and tests.
export function buildReport({ samples, presented, decodeFailures, firstFrameMs, lastFrameMs, offset, rtc, decode, nowMs }) {
  const off = offset?.offsetMs ?? 0;
  const lat = [];
  const latCb = [];
  for (const s of samples) {
    const d = s.recvMs - (s.phoneMs + off);
    if (d < LATENCY_MIN_MS || d > LATENCY_MAX_MS) continue; // implausible: bad offset / misdecode
    lat.push(d);
    latCb.push((s.cbMs ?? s.recvMs) - (s.phoneMs + off));
  }
  const spanS = firstFrameMs != null && lastFrameMs > firstFrameMs ? (lastFrameMs - firstFrameMs) / 1000 : 0;
  const attempts = presented;
  return {
    tool: 'cast-sim',
    generatedAt: new Date(nowMs ?? Date.now()).toISOString(),
    caveat: LATENCY_CAVEAT,
    durationS: spanS,
    offset: offset ?? null,
    clockSynced: offset != null,
    latencyRejected: samples.length - lat.length,
    latencyMs: summarize(lat), // to expectedDisplayTime (frame presentation)
    latencyAtCallbackMs: summarize(latCb), // to the rVFC callback (frame handed to the compositor)
    negativeLatencySamples: lat.filter(v => v < 0).length,
    frames: {
      presented: attempts,
      decoded: samples.length,
      fps: spanS > 0 ? (attempts - 1) / spanS : null,
      decodeFailures,
      decodeFailurePct: attempts > 0 ? (decodeFailures / attempts) * 100 : null,
    },
    decode: decode ?? null,
    webrtc: rtc ?? null,
  };
}

// Decoded phone time is plausible if, after converting it to the receiver clock
// with the sync offset (server - phone), it is within `limitMs` of the callback time.
export function isPlausible(phoneMs, cbMs, offsetMs = 0, limitMs = 3600_000) {
  return Math.abs(phoneMs + offsetMs - cbMs) <= limitMs;
}
