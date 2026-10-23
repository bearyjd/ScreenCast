// Advertise a fake Chromecast (_googlecast._tcp) with multicast-dns.
import { createHash } from 'node:crypto';
import mdnsFactory from 'multicast-dns';
import { withTimeout } from './timeout.mjs';

const SERVICE = '_googlecast._tcp.local';

export function txtRecords(id, friendlyName) {
  const bs = createHash('md5').update(`bs${id}`).digest('hex').slice(0, 12).toUpperCase();
  return [
    `id=${id}`, `cd=${id.toUpperCase()}`, 'rm=', 've=05', 'md=Chromecast', 'ic=/setup/icon.png',
    `fn=${friendlyName}`, 'ca=4101', 'st=0', `bs=${bs}`, 'nf=1', 'rs=',
  ].map(s => Buffer.from(s, 'utf8'));
}

export function startMdns({ address, port, friendlyName, id, log = () => {}, mdns = mdnsFactory, stopTimeoutMs = 3000 }) {
  const host = `${id.slice(0, 12)}.local`;
  const instance = `CastSim-${id}.${SERVICE}`;
  const ttl = 120;
  const records = {
    ptr: { name: SERVICE, type: 'PTR', ttl, data: instance },
    srv: { name: instance, type: 'SRV', ttl, flush: true, data: { port, weight: 0, priority: 0, target: host } },
    txt: { name: instance, type: 'TXT', ttl, flush: true, data: txtRecords(id, friendlyName) },
    a: { name: host, type: 'A', ttl, flush: true, data: address },
  };
  const m = mdns({ interface: address, ip: '224.0.0.251', port: 5353, reuseAddr: true });
  const all = () => [records.ptr, records.srv, records.txt, records.a];
  m.on('error', e => log('mdns error:', e.message));
  m.on('query', q => {
    const answers = [];
    for (const question of q.questions) {
      const n = question.name.toLowerCase();
      if (n === SERVICE && (question.type === 'PTR' || question.type === 'ANY')) answers.push(...all());
      else if (n === instance.toLowerCase() && ['SRV', 'TXT', 'ANY'].includes(question.type)) answers.push(records.srv, records.txt, records.a);
      else if (n === host && ['A', 'ANY'].includes(question.type)) answers.push(records.a);
      else if (n === '_services._dns-sd._udp.local' && question.type === 'PTR') {
        answers.push({ name: n, type: 'PTR', ttl, data: SERVICE });
      }
    }
    if (answers.length) m.respond({ answers });
  });
  const announce = () => m.respond({ answers: all() });
  m.once('ready', announce);
  const timer = setInterval(announce, 60_000);
  timer.unref();
  return {
    host, instance,
    stop: () => {
      clearInterval(timer);
      // Goodbye packet (TTL 0) so browsers drop the entry immediately.
      const closed = new Promise(resolve => {
        m.respond({ answers: all().map(r => ({ ...r, ttl: 0 })) }, () => m.destroy(resolve));
      });
      return withTimeout(closed, stopTimeoutMs, 'mdns stop').catch(e => log(e.message));
    },
  };
}
