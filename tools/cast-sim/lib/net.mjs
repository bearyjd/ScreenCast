import os from 'node:os';

const VIRTUAL = /^(docker|br-|veth|virbr|cni|flannel|tailscale|lo$)/;

export function isPrivateIPv4(a) {
  const p = a.split('.').map(Number);
  return p[0] === 10 || (p[0] === 172 && p[1] >= 16 && p[1] <= 31) || (p[0] === 192 && p[1] === 168);
}

// Candidate LAN IPv4 addresses (RFC1918, non-virtual interfaces first).
export function lanAddresses(ifaces = os.networkInterfaces()) {
  const out = [];
  for (const [name, list] of Object.entries(ifaces)) {
    if (VIRTUAL.test(name)) continue;
    for (const i of list ?? []) {
      if (i.family === 'IPv4' && !i.internal && isPrivateIPv4(i.address)) out.push({ name, address: i.address });
    }
  }
  return out;
}
