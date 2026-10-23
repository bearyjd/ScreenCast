// Ordered, bounded shutdown: stop advertising first, then a short final report, then close.
import { withTimeout } from './timeout.mjs';

export async function runShutdown({ sim, browser, log, reportTimeoutMs = 1500, stopAdvertMs = 1000 }) {
  const advertStopped = withTimeout(sim.stopAdvert(), stopAdvertMs, 'mDNS stop').catch(e => log('mDNS stop failed:', e.message));
  try { await withTimeout(sim.emitReport('shutdown', { force: true }), reportTimeoutMs, 'final report'); } catch (e) { log('final report failed:', e.message); }
  await advertStopped;
  const labels = ['browser', 'sim'];
  const results = await Promise.allSettled([browser?.close(), sim.close()]);
  results.forEach((r, i) => { if (r.status === 'rejected') log(`${labels[i]} close failed:`, r.reason?.message ?? r.reason); });
}
