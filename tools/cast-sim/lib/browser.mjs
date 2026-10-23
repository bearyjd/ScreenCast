// Runs the real receiver/index.html in headless Chromium with the CAF script
// replaced by page/cast-shim.js (request interception; nothing on disk is
// modified) and the latency harness injected.
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import puppeteer from 'puppeteer-core';
import { ROOT } from './web.mjs';

const CAF_URL = 'https://www.gstatic.com/cast/sdk/libs/caf_receiver/v3/cast_receiver_framework.js';

// Only the sim's own origin (parsed, not a string prefix) plus blob:/data: may load.
export function isAllowedUrl(url, origin) {
  let u;
  try { u = new URL(url); } catch { return false; }
  if (u.protocol === 'blob:' || u.protocol === 'data:') return true;
  return u.origin === origin;
}

export const CHROME_ARGS = [
  '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage',
  '--autoplay-policy=no-user-gesture-required',
  '--use-fake-ui-for-media-stream',
  // Gather plain host candidates (a real Chromecast does); mDNS-obfuscated
  // candidates would not resolve on the phone's WebRTC stack.
  '--disable-features=WebRtcHideLocalIpsWithMdns',
  '--force-webrtc-ip-handling-policy=default_public_and_private_interfaces',
  '--no-first-run', '--no-default-browser-check', '--disable-background-timer-throttling',
  '--disable-renderer-backgrounding', '--disable-backgrounding-occluded-windows',
];

// pipe: DevTools over stdio, so no unauthenticated --remote-debugging-port is opened.
export async function launchBrowser({ executablePath, userDataDir, log = () => {}, extraArgs = [], launchFn = puppeteer.launch.bind(puppeteer) }) {
  return launchFn({
    executablePath, headless: true, pipe: true, userDataDir, args: [...CHROME_ARGS, ...extraArgs],
    defaultViewport: { width: 1920, height: 1080 },
  });
}

export async function openReceiverPage(browser, { origin, token, log = () => {} }) {
  const shim = await readFile(path.join(ROOT, 'page', 'cast-shim.js'));
  const boot = await readFile(path.join(ROOT, 'page', 'harness-boot.js'), 'utf8');
  const page = await browser.newPage();
  await page.setRequestInterception(true);
  page.on('request', req => {
    const url = req.url();
    if (url === CAF_URL) return req.respond({ status: 200, contentType: 'text/javascript', body: shim });
    if (isAllowedUrl(url, origin)) return req.continue();
    log(`blocked external request: ${url}`);
    return req.abort();
  });
  page.on('console', m => log(`[page ${m.type()}] ${m.text()}`));
  page.on('pageerror', e => log(`[page error] ${e.message}`));
  await page.evaluateOnNewDocument(boot);
  await page.goto(`${origin}/receiver/index.html#t=${token}`, { waitUntil: 'load' });
  return page;
}
