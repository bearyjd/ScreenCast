import net from 'node:net';

// Command-line argument validation (fail fast with a message naming the flag).
export function parseNumArg(name, raw, { min = -Infinity, max = Infinity, integer = false } = {}) {
  const text = String(raw ?? '').trim();
  const n = text === '' ? NaN : Number(text);
  const kind = integer ? 'an integer' : 'a number';
  if (!Number.isFinite(n) || (integer && !Number.isInteger(n)) || n < min || n > max) {
    throw new Error(`--${name} must be ${kind} between ${min} and ${max}, got "${raw}"`);
  }
  return n;
}

// --ip must be a concrete IPv4 address: 0.0.0.0 would expose every listener on all interfaces.
export function parseBindIp(raw) {
  const ip = String(raw ?? '').trim();
  if (!net.isIPv4(ip)) throw new Error(`--ip must be an IPv4 address, got "${raw}"`);
  if (ip === '0.0.0.0') throw new Error('--ip 0.0.0.0 is not allowed (binds all interfaces); pass the LAN address of this machine');
  return ip;
}

export function describeListenError(e) {
  if (e.code === 'EADDRINUSE') return `cannot listen on ${e.address ?? '?'}:${e.port ?? '?'}: address already in use (is another cast-sim or a Cast device running? pick another port)`;
  return `cannot start: ${e.message}`;
}
