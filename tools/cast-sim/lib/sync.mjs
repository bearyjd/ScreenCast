// Server side of the NTP-style clock sync. The clock page POSTs
// {client, id, t, prevId, prevRtt}: `t` is its Date.now() at send. We stamp the
// arrival time; once the page reports the RTT of that ping (piggybacked on its
// next request) we form offset = arrival - (t + rtt/2). Best = min-RTT sample.
import { offsetSample, bestOffset } from './stats.mjs';

const MIN_PREV_RTT_MS = 1; // a real round trip is never 0 ms: forged 0-RTT samples are dropped
const MAX_PREV_RTT_MS = 10_000;
const MAX_CLOCK_SKEW_MS = 3_600_000; // |client t - server arrival|, same as the plausibility limit
const PIN_IDLE_MS = 5000;

export class SyncStore {
  constructor({ now = Date.now, maxPending = 64, maxSamples = 600, maxClients = 16 } = {}) {
    this.now = now;
    this.maxPending = maxPending;
    this.maxSamples = maxSamples;
    this.maxClients = maxClients;
    this.pinned = null; // client whose offset best() reports until it goes idle
    this.clients = new Map(); // client -> {pending: Map(id -> {t, arrival}), samples: [], last}
  }

  ping({ client, id, t, prevId, prevRtt }) {
    if (typeof client !== 'string' || client.length > 64 || !Number.isFinite(t) || !Number.isFinite(id)) {
      throw new TypeError('bad sync request');
    }
    const arrival = this.now();
    if (Math.abs(t - arrival) > MAX_CLOCK_SKEW_MS) throw new TypeError('bad sync request: t too far from server time');
    // An out-of-range prevRtt only voids that one sample: the ping is still recorded and answered.
    const rttOk = Number.isFinite(prevRtt) && prevRtt >= MIN_PREV_RTT_MS && prevRtt <= MAX_PREV_RTT_MS;
    let c = this.clients.get(client);
    if (!c) { c = { pending: new Map(), samples: [], last: 0 }; this.clients.set(client, c); }
    c.last = arrival;
    if (this.clients.size > this.maxClients) this.#evictLeastRecent(client);
    const prev = c.pending.get(prevId);
    if (prev && rttOk) {
      c.pending.delete(prevId);
      c.samples.push(offsetSample({ tSend: prev.t, tRecv: prev.t + prevRtt, tServer: prev.arrival }));
      if (c.samples.length > this.maxSamples) c.samples.shift();
      this.#repin();
    }
    c.pending.set(id, { t, arrival });
    if (c.pending.size > this.maxPending) c.pending.delete(c.pending.keys().next().value);
    return { id, serverMs: arrival };
  }

  #evictLeastRecent(keep) {
    let lru = null;
    for (const [client, c] of this.clients) {
      if (client !== keep && client !== this.pinned && (!lru || c.last < lru.last)) lru = { client, last: c.last };
    }
    if (lru) this.clients.delete(lru.client);
  }

  // The pinned clock page is the first one with samples; it stays until it has
  // been idle > 5 s, then the most recently active one with samples takes over.
  #repin() {
    const pin = this.clients.get(this.pinned);
    if (pin && pin.samples.length && this.now() - pin.last <= PIN_IDLE_MS) return;
    this.pinned = null;
    for (const [client, c] of this.clients) {
      if (c.samples.length && (this.pinned === null || c.last > this.clients.get(this.pinned).last)) this.pinned = client;
    }
  }

  // Every client with samples: for the report, so the right device can be picked out.
  all() {
    const t = this.now();
    const out = [];
    for (const [client, c] of this.clients) {
      const b = bestOffset(c.samples);
      if (b) out.push({ client, offsetMs: b.offsetMs, rttMs: b.rttMs, ageMs: t - c.last });
    }
    return out;
  }

  // Offset of the pinned clock page, or null if none synced.
  best(opts) {
    this.#repin();
    if (this.pinned === null) return null;
    return { client: this.pinned, ...bestOffset(this.clients.get(this.pinned).samples, opts) };
  }
}
