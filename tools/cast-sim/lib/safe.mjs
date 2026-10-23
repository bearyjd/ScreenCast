// Log hygiene: peers control namespaces, ids, paths and error text. Strip control
// characters so they cannot forge log lines or inject terminal escapes.
const CONTROL = /[\x00-\x1f\x7f]/g;

export const clean = s => String(s).replace(CONTROL, '?');

export function safeLog(log) {
  return (...args) => log(...args.map(a => (typeof a === 'string' ? clean(a) : a)));
}
