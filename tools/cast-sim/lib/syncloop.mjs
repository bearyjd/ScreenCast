// Clock-sync client plumbing for page/clock.mjs (isomorphic, unit-tested in node).
import { offsetSample, bestOffset } from './stats.mjs';

const SYNC_TIMEOUT_MS = 2000;
const MAX_LOCAL_SAMPLES = 600;

// POST one sync ping; aborts after timeoutMs so a stalled server cannot pile up requests.
export async function postSync(body, { fetchFn = fetch, timeoutMs = SYNC_TIMEOUT_MS } = {}) {
  const r = await fetchFn('/sync', {
    method: 'POST', cache: 'no-store', headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body), signal: AbortSignal.timeout(timeoutMs),
  });
  if (!r.ok) {
    const detail = await r.json().then(j => j?.error, () => null);
    throw new Error(`sync HTTP ${r.status}${typeof detail === 'string' ? `: ${detail}` : ''}`);
  }
  return r.json();
}

export function syncStatus(client, b, n) {
  return `client ${client} off ${b.offsetMs.toFixed(1)}\u00b1${b.uncertaintyMs.toFixed(1)} ms rtt ${b.rttMs} n=${n}`;
}

// One sync client: piggybacks the previous ping's RTT on the next. Any failure
// (or an unusable RTT) resets `prev`, so a rejected sample is never resent forever.
export function createSyncClient({ client, post = postSync, now = Date.now }) {
  const local = [];
  let seq = 0;
  let prev = { id: null, rtt: null };
  return {
    async once() {
      const id = ++seq;
      const tSend = now();
      try {
        const { serverMs } = await post({ client, id, t: tSend, prevId: prev.id, prevRtt: prev.rtt });
        const tRecv = now();
        if (tRecv < tSend) { prev = { id: null, rtt: null }; return null; }
        prev = { id, rtt: tRecv - tSend };
        local.push(offsetSample({ tSend, tRecv, tServer: serverMs }));
        if (local.length > MAX_LOCAL_SAMPLES) local.shift();
        return { best: bestOffset(local), n: local.length };
      } catch (e) {
        prev = { id: null, rtt: null };
        throw e;
      }
    },
  };
}

// Self-rescheduling loop: the next run is scheduled only after the previous one
// settled (so runs never overlap); a fast burst first, then a steady interval.
export function startSyncLoop({ once, schedule = setTimeout, burst = 20, burstMs = 100, intervalMs = 500, onError = console.error }) {
  let runs = 0;
  const tick = async () => {
    try { await once(); } catch (e) { onError(e); }
    runs++;
    schedule(tick, runs <= burst ? burstMs : intervalMs);
  };
  tick();
}
