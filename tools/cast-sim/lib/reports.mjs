// Report file writer: rate-limited (>= 10 s apart) and bounded (<= 500 files in outDir).
import { mkdir, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

export const REPORT_MIN_GAP_MS = 10_000;
export const MAX_REPORT_FILES = 500;
const REPORT_PREFIX = 'cast-sim-report-';

export function makeReportWriter({ outDir, log, print, now = Date.now }) {
  let lastWriteAt = -Infinity;
  // force: the final shutdown report bypasses the 10 s gap (the 500-file cap still applies).
  return async function writeReportFile(report, { force = false } = {}) {
    const t = now();
    if (!force && t - lastWriteAt < REPORT_MIN_GAP_MS) return log('report file skipped: written less than 10 s ago');
    lastWriteAt = t;
    await mkdir(outDir, { recursive: true });
    const existing = (await readdir(outDir)).filter(f => f.startsWith(REPORT_PREFIX)).length;
    if (existing >= MAX_REPORT_FILES) return log(`report file skipped: ${existing} files already in ${outDir}`);
    const file = path.join(outDir, `${REPORT_PREFIX}${new Date(t).toISOString().replace(/[:.]/g, '-')}.json`);
    await writeFile(file, JSON.stringify(report, null, 2));
    print(`report written: ${file}`);
  };
}
