import test from 'node:test';
import assert from 'node:assert/strict';
import {
  percentile, summarize, offsetSample, bestOffset, summarizeRtcStats, buildReport, formatTable,
} from '../lib/stats.mjs';
import { SyncStore } from '../lib/sync.mjs';

test('percentile: linear interpolation, edges, empty', () => {
  const v = [5, 1, 3, 2, 4];
  assert.equal(percentile(v, 0), 1);
  assert.equal(percentile(v, 50), 3);
  assert.equal(percentile(v, 100), 5);
  assert.equal(percentile(v, 90), 4.6);
  assert.equal(percentile([7], 99), 7);
  assert.equal(percentile([], 50), null);
  const s = summarize([10, 20, 30, 40]);
  assert.deepEqual([s.count, s.min, s.max, s.mean, s.p50], [4, 10, 40, 25, 25]);
  assert.equal(summarize([]).count, 0);
});

test('offsetSample: offset = server - (send + rtt/2)', () => {
  // page clock is 1000 ms behind the server; one-way delay 10 ms each way.
  const s = offsetSample({ tSend: 5000, tRecv: 5020, tServer: 5000 + 10 + 1000 });
  assert.equal(s.rtt, 20);
  assert.equal(s.offset, 1000);
});

test('bestOffset picks the min-RTT sample within the window', () => {
  const samples = [{ rtt: 50, offset: 900 }, { rtt: 4, offset: 1001 }, { rtt: 30, offset: 1100 }];
  const b = bestOffset(samples);
  assert.deepEqual(b, { offsetMs: 1001, rttMs: 4, uncertaintyMs: 2, samples: 3 });
  assert.equal(bestOffset(samples, { window: 1 }).offsetMs, 1100);
  assert.equal(bestOffset([]), null);
});

test('SyncStore turns piggybacked RTTs into samples and returns best', () => {
  let now = 10_000;
  const store = new SyncStore({ now: () => now });
  // phone clock = server - 500 ms; one-way delay 5 ms.
  now = 10_005; store.ping({ client: 'p', id: 1, t: 9_500, prevId: null, prevRtt: null });
  now = 11_005; store.ping({ client: 'p', id: 2, t: 10_500, prevId: 1, prevRtt: 10 });
  const b = store.best();
  assert.equal(b.client, 'p');
  assert.equal(b.offsetMs, 500);
  assert.equal(b.rttMs, 10);
  assert.throws(() => store.ping({ client: 1, id: 1, t: 1 }));
});

test('summarizeRtcStats digests inbound video/audio and the selected pair', () => {
  const r = summarizeRtcStats([
    { id: 'C1', type: 'codec', mimeType: 'video/H264' },
    { id: 'C2', type: 'codec', mimeType: 'audio/opus' },
    { id: 'V', type: 'inbound-rtp', kind: 'video', codecId: 'C1', frameWidth: 1280, frameHeight: 720, jitterBufferDelay: 2, jitterBufferEmittedCount: 100,
      totalDecodeTime: 1, framesDecoded: 200, framesDropped: 3, freezeCount: 1, jitter: 0.004 },
    { id: 'A', type: 'inbound-rtp', kind: 'audio', codecId: 'C2', jitterBufferDelay: 5, jitterBufferEmittedCount: 100, concealedSamples: 50, totalSamplesReceived: 1000 },
    { id: 'T', type: 'transport', selectedCandidatePairId: 'P' },
    { id: 'P', type: 'candidate-pair', currentRoundTripTime: 0.012 },
  ]);
  assert.equal(r.video.codec, 'video/H264');
  assert.equal(r.video.jitterBufferDelayAvgMs, 20);
  assert.equal(r.video.decodeTimeAvgMs, 5);
  assert.equal(r.video.framesDropped, 3);
  assert.equal(r.audio.jitterBufferDelayAvgMs, 50);
  assert.equal(r.audio.concealedPct, 5);
  assert.equal(r.rttMs, 12);
});

test('buildReport applies offset and counts failures; table has caveat', () => {
  const samples = [0, 1, 2, 3].map(i => ({ phoneMs: 1000 + i * 100, recvMs: 1000 + i * 100 + 500 + 40, cbMs: 1000 + i * 100 + 500 + 20 }));
  const rep = buildReport({
    samples, presented: 5, decodeFailures: 1, firstFrameMs: 1000, lastFrameMs: 3000,
    offset: { offsetMs: 500, rttMs: 4, uncertaintyMs: 2, samples: 9 }, rtc: null, nowMs: 0,
  });
  assert.equal(rep.latencyMs.p50, 40);
  assert.equal(rep.latencyAtCallbackMs.p50, 20);
  assert.equal(rep.frames.decodeFailurePct, 20);
  assert.equal(rep.frames.fps, 2);
  const withDecode = buildReport({ samples, presented: 5, decodeFailures: 1, firstFrameMs: 1000, lastFrameMs: 3000, offset: null,
    decode: { box: { x: 10, y: 20, w: 400, h: 200, cell: 8.3 }, failReasons: { notFound: 1, syncMismatch: 0, checksum: 0, implausible: 0 }, frameWidth: 1920, frameHeight: 1080 } });
  assert.equal(withDecode.decode.box.cell, 8.3);
  assert.match(formatTable(withDecode), /barcode: located at x=10 y=20 400x200 px \(cell 8.30\) in 1920x1080 \| fails: not-found 1/);
  assert.match(formatTable(rep), /EXCLUDES the phone display latency/);
  assert.equal(buildReport({ samples: [], presented: 0, decodeFailures: 0, firstFrameMs: null, lastFrameMs: null, offset: null }).clockSynced, false);
});
