// clock.html must keep the whole barcode (with quiet zone) inside the viewport at any width.
import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createSim } from '../lib/sim.mjs';
import { launchBrowser } from '../lib/browser.mjs';

const chrome = process.env.CHROME_PATH ?? ['/usr/bin/chromium', '/usr/bin/google-chrome'].find(p => existsSync(p));
const receiverDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../receiver');

test('clock.html barcode is fully visible and decodable at 320-1000 CSS px', { skip: !chrome && 'no Chromium found' }, async () => {
  const sim = await createSim({ bind: '127.0.0.1', castPort: 0, httpPort: 0, receiverDir, mdns: false, log: () => {}, print: () => {} });
  const browser = await launchBrowser({ executablePath: chrome, userDataDir: mkdtempSync(path.join(tmpdir(), 'cast-sim-clk-')) });
  try {
    for (const [w, h, dsf] of [[320, 568, 2], [411, 891, 2.6], [600, 900, 1], [1000, 700, 1], [1000, 700, 2]]) {
      const page = await browser.newPage();
      await page.setViewport({ width: w, height: h, deviceScaleFactor: dsf });
      await page.goto(`http://127.0.0.1:${sim.httpPort}/clock.html`);
      await new Promise(r => setTimeout(r, 400));
      const res = await page.evaluate(async () => {
        const { locate, makeView } = await import('/lib/barcode.mjs');
        const c = document.getElementById('c');
        const img = c.getContext('2d').getImageData(0, 0, c.width, c.height);
        const r = locate(makeView(img.data, c.width, c.height));
        return { r, W: c.width, H: c.height, sw: document.documentElement.scrollWidth, iw: window.innerWidth };
      });
      const tag = `${w}x${h}@${dsf}`;
      assert.equal(res.r.ok, true, `${tag}: ${res.r.reason}`);
      assert.ok(res.r.box.x >= 3 * res.r.box.cell - 1 && res.r.box.x + res.r.box.w <= res.W - 3 * res.r.box.cell + 1, `${tag}: quiet zone`);
      assert.ok(res.r.box.cell >= 2.5);
      assert.ok(res.sw <= res.iw, `${tag}: horizontal overflow`);
      await page.close();
    }
  } finally { await browser.close(); await sim.close(); }
});
