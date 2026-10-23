// What runs inside <iframe sandbox="allow-scripts"> when every response carries
// Jenkins's CSP header? Starts its own server on 127.0.0.1:4330 and drives
// Chromium. Run: node js/lab/sandbox-probe.mjs
import http from 'node:http';
import { chromium } from '@playwright/test';

const CSP = "base-uri 'none'; default-src 'self'; form-action 'self'; frame-ancestors 'self'; img-src 'self' data:; script-src 'report-sample' 'self' usage.jenkins.io; style-src 'report-sample' 'self' 'unsafe-inline'";
const ORIGIN = 'http://127.0.0.1:4330';
const seen = [];

const post = (probe) => `parent.postMessage({ probe: ${JSON.stringify(probe)}, ok: true }, '*');`;
const FILES = {
  '/parent.html': ['text/html', `<!doctype html><script type="module" src="/parent.mjs"></script>
<iframe id="f" sandbox="allow-scripts" src="/frame.html"></iframe>`],
  '/parent.mjs': ['text/javascript', `
    window.results = {};
    addEventListener('message', (e) => { window.results[e.data.probe] = { ok: e.data.ok, detail: e.data.detail, origin: e.origin }; });`],
  '/frame.html': ['text/html', `<!doctype html>
<script src="/classic.js"></script>
<script type="module" src="/module-plain.mjs"></script>
<script type="module" src="/module-cors.mjs"></script>
<script>parent.postMessage({ probe: 'inline-script', ok: true }, '*')</script>
<div id="d">frame</div>`],
  '/classic.js': ['text/javascript', `${post('classic-same-origin')}
    try { new Function('return 1')(); parent.postMessage({ probe: 'eval', ok: true }, '*'); }
    catch (e) { parent.postMessage({ probe: 'eval', ok: false, detail: String(e).slice(0, 80) }, '*'); }
    parent.postMessage({ probe: 'frame-origin', ok: true, detail: String(self.origin) }, '*');
    try { parent.document.title; parent.postMessage({ probe: 'parent-dom', ok: true }, '*'); }
    catch (e) { parent.postMessage({ probe: 'parent-dom', ok: false, detail: String(e).slice(0, 60) }, '*'); }
    try { document.cookie; parent.postMessage({ probe: 'document-cookie', ok: true }, '*'); }
    catch (e) { parent.postMessage({ probe: 'document-cookie', ok: false, detail: String(e).slice(0, 60) }, '*'); }
    for (const [name, url] of [['fetch-plain', '/data-plain'], ['fetch-cors', '/data-cors']]) {
      fetch(url, { credentials: 'include' }).then(r => r.text())
        .then(t => parent.postMessage({ probe: name, ok: true, detail: t }, '*'),
              e => parent.postMessage({ probe: name, ok: false, detail: String(e).slice(0, 60) }, '*'));
    }
    const img = new Image(); img.onload = () => parent.postMessage({ probe: 'img-same-origin', ok: true }, '*');
    img.onerror = () => parent.postMessage({ probe: 'img-same-origin', ok: false }, '*'); img.src = '/pixel.gif';
    import('/dynamic-cors.mjs').then(() => {}, e => parent.postMessage({ probe: 'dynamic-import-cors', ok: false, detail: String(e).slice(0, 60) }, '*'));
    const blob = URL.createObjectURL(new Blob([${JSON.stringify(post('blob-import'))}], { type: 'text/javascript' }));
    import(blob).then(() => {}, e => parent.postMessage({ probe: 'blob-import', ok: false, detail: String(e).slice(0, 60) }, '*'));`],
  '/module-plain.mjs': ['text/javascript', post('module-no-cors-header')],
  '/module-cors.mjs': ['text/javascript', post('module-cors-header')],
  '/dynamic-cors.mjs': ['text/javascript', post('dynamic-import-cors')],
  '/data-plain': ['text/plain', 'plain'],
  '/data-cors': ['text/plain', 'cors'],
  '/pixel.gif': ['image/gif', Buffer.from('R0lGODlhAQABAAAAACw=', 'base64')],
};

const server = http.createServer((req, res) => {
  const f = FILES[req.url];
  seen.push(`${req.url} cookie=${req.headers.cookie ? 'yes' : 'no'} origin=${req.headers.origin || '-'}`);
  if (!f) { res.writeHead(404); res.end(); return; }
  const headers = { 'content-type': f[0], 'content-security-policy': CSP, 'x-frame-options': 'sameorigin' };
  if (req.url.includes('cors')) headers['access-control-allow-origin'] = '*';
  if (req.url === '/parent.html') headers['set-cookie'] = 'JSESSIONID=abc; Path=/; HttpOnly';
  res.writeHead(200, headers);
  res.end(f[1]);
});
await new Promise((r) => server.listen(4330, '127.0.0.1', r));

const browser = await chromium.launch();
const page = await browser.newPage();
const errors = [];
page.on('console', (m) => m.type() === 'error' && errors.push(m.text().slice(0, 160)));
await page.goto(`${ORIGIN}/parent.html`);
await page.reload(); // second load carries the cookie set by the first
await page.waitForTimeout(1500);
const results = await page.evaluate(() => window.results);
for (const [k, v] of Object.entries(results).sort()) console.log(`${v.ok ? 'PASS' : 'FAIL'} ${k}${v.detail ? ` (${v.detail})` : ''}`);
console.log('--- requests (second load)');
for (const s of seen.slice(seen.lastIndexOf(seen.find((x, i) => i > 0 && x.startsWith('/parent.html'))))) console.log(s);
if (errors.length) console.log('--- console errors\n' + errors.join('\n'));
await browser.close();
server.close();
