import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// Self-signed EC cert generated at start with the openssl CLI; nothing is
// persisted (temp dir removed). The Android sender trusts any certificate.
export function generateCert(cn = 'cast-sim') {
  const dir = mkdtempSync(join(tmpdir(), 'cast-sim-cert-'));
  try {
    execFileSync('openssl', [
      'req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes',
      '-keyout', join(dir, 'k.pem'), '-out', join(dir, 'c.pem'), '-days', '7', '-subj', `/CN=${cn}`,
    ], { stdio: ['ignore', 'ignore', 'pipe'] });
    return { key: readFileSync(join(dir, 'k.pem')), cert: readFileSync(join(dir, 'c.pem')) };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}
