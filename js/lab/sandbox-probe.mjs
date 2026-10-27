// What runs inside <iframe sandbox="allow-scripts"> when every response carries
// Jenkins's CSP header? Starts its own server on 127.0.0.1:4330 and drives one
// browser engine. Run: node js/lab/sandbox-probe.mjs [chromium|firefox|webkit]
//
// Three frames, created as host.js creates its frame: "jenkins" loads a
// document served with Jenkins's policy as it is; "origin" one whose
// script-src names the origin instead of 'self', to tell a 'self' mismatch
// apart from other blocks; "srcdoc" has the same markup in srcdoc, so it
// inherits the parent page's policy instead of getting its own.
import http from 'node:http';
import * as playwright from '@playwright/test';

const engine = process.argv[2] || 'chromium';
const CSP = "base-uri 'none'; default-src 'self'; form-action 'self'; frame-ancestors 'self'; img-src 'self' data:; script-src 'report-sample' 'self' usage.jenkins.io; style-src 'report-sample' 'self' 'unsafe-inline'";
const ORIGIN = 'http://127.0.0.1:4330';
const CSP_ORIGIN = CSP.replace("script-src 'report-sample' 'self'", `script-src 'report-sample' ${ORIGIN}`);
const seen = [];

// The frame's name (the iframe's name attribute) says which frame reports.
const post = (probe) => `parent.postMessage({ probe: ${JSON.stringify(probe)}, frame: self.name, ok: true }, '*');`;
const FRAME = `<!doctype html>
<script src="/classic.js"></script>
<script type="module" src="/module-plain.mjs"></script>
<script type="module" src="/module-cors.mjs"></script>
<script>parent.postMessage({ probe: 'inline-script', frame: self.name, ok: true }, '*')</script>
<div id="d">frame</div>`;
const FILES = {
  '/parent.html': ['text/html', '<!doctype html><script type="module" src="/parent.mjs"></script>'],
  '/parent.mjs': ['text/javascript', `
    window.results = {};
    addEventListener('message', (e) => {
      if (e.data?.probe) window.results[e.data.frame + ' ' + e.data.probe] = { ok: e.data.ok, detail: e.data.detail, origin: e.origin };
    });
    for (const [name, attr, value] of [['jenkins', 'src', '/frame.html?jenkins'], ['origin', 'src', '/frame.html?origin'], ['srcdoc', 'srcdoc', ${JSON.stringify(FRAME)}]]) {
      const f = document.createElement('iframe');
      f.name = name;
      f.setAttribute('sandbox', 'allow-scripts');
      f.setAttribute(attr, value);
      // What host.js does: on load, send one message that transfers a MessagePort.
      f.addEventListener('load', () => {
        const { port1, port2 } = new MessageChannel();
        port1.onmessage = (e) => { window.results[name + ' message-port'] = { ok: e.data === 'pong' }; };
        f.contentWindow.postMessage('ping', '*', [port2]);
      }, { once: true });
      document.body.append(f);
    }`],
  '/frame.html': ['text/html', FRAME],
  '/classic.js': ['text/javascript', `${post('classic-same-origin')}
    const frame = self.name;
    const report = (probe, ok, detail) => parent.postMessage({ probe, frame, ok, detail }, '*');
    addEventListener('message', (e) => { if (e.ports[0]) e.ports[0].postMessage('pong'); });
    try { new Function('return 1')(); report('eval', true); } catch (e) { report('eval', false, String(e).slice(0, 80)); }
    report('frame-origin', true, String(self.origin));
    try { parent.document.title; report('parent-dom', true); } catch (e) { report('parent-dom', false, String(e).slice(0, 60)); }
    try { document.cookie; report('document-cookie', true); } catch (e) { report('document-cookie', false, String(e).slice(0, 60)); }
    for (const [name, url] of [['fetch-plain', '/data-plain'], ['fetch-cors', '/data-cors']]) {
      fetch(url, { credentials: 'include' }).then(r => r.text())
        .then(t => report(name, true, t), e => report(name, false, String(e).slice(0, 60)));
    }
    const img = new Image(); img.onload = () => report('img-same-origin', true);
    img.onerror = () => report('img-same-origin', false); img.src = '/pixel.gif';
    import('/dynamic-cors.mjs').then(() => {}, e => report('dynamic-import-cors', false, String(e).slice(0, 60)));
    const blob = URL.createObjectURL(new Blob([${JSON.stringify(post('blob-import'))}], { type: 'text/javascript' }));
    import(blob).then(() => {}, e => report('blob-import', false, String(e).slice(0, 60)));`],
  '/module-plain.mjs': ['text/javascript', post('module-no-cors-header')],
  '/module-cors.mjs': ['text/javascript', post('module-cors-header')],
  '/dynamic-cors.mjs': ['text/javascript', post('dynamic-import-cors')],
  '/data-plain': ['text/plain', 'plain'],
  '/data-cors': ['text/plain', 'cors'],
  '/pixel.gif': ['image/gif', Buffer.from('R0lGODlhAQABAAAAACw=', 'base64')],
};
const PROBES = ['inline-script', 'classic-same-origin', 'module-no-cors-header', 'module-cors-header', 'dynamic-import-cors',
  'blob-import', 'eval', 'frame-origin', 'parent-dom', 'document-cookie', 'fetch-plain', 'fetch-cors', 'img-same-origin', 'message-port'];

const server = http.createServer((req, res) => {
  const [path, query] = req.url.split('?');
  const f = FILES[path];
  seen.push(`${req.url} cookie=${req.headers.cookie ? 'yes' : 'no'} origin=${req.headers.origin || '-'}`);
  if (!f) { res.writeHead(404); res.end(); return; }
  const headers = { 'content-type': f[0], 'content-security-policy': query === 'origin' ? CSP_ORIGIN : CSP, 'x-frame-options': 'sameorigin' };
  if (path.includes('cors')) headers['access-control-allow-origin'] = '*';
  if (path === '/parent.html') headers['set-cookie'] = 'JSESSIONID=abc; Path=/; HttpOnly';
  res.writeHead(200, headers);
  res.end(f[1]);
});
await new Promise((r) => server.listen(4330, '127.0.0.1', r));

const browser = await playwright[engine].launch();
console.log(`=== sandbox probe: ${engine} ${browser.version()}`);
const page = await browser.newPage();
const errors = [];
page.on('console', (m) => ['error', 'warning'].includes(m.type()) && errors.push(`${m.type()}: ${m.text().replace(/\s+/g, ' ').slice(0, 200)}`));
await page.goto(`${ORIGIN}/parent.html`);
const firstOfSecondLoad = seen.length;
await page.reload(); // second load carries the cookie set by the first
await page.waitForTimeout(1500);
const results = await page.evaluate(() => window.results);
const FRAMES = { jenkins: "own document, script-src 'self'", origin: `own document, script-src ${ORIGIN}`, srcdoc: "srcdoc, the page's policy (script-src 'self')" };
for (const [frame, what] of Object.entries(FRAMES)) {
  console.log(`--- frame "${frame}": ${what}`);
  for (const probe of PROBES) {
    const v = results[`${frame} ${probe}`];
    console.log(v ? `${v.ok ? 'PASS' : 'FAIL'} ${probe}${v.detail ? ` (${v.detail})` : ''}` : `NONE ${probe} (no result: the probe did not run)`);
  }
}
console.log('--- requests (second load)');
for (const s of seen.slice(firstOfSecondLoad)) console.log(s);
if (errors.length) console.log('--- console errors and warnings (both loads)\n' + [...new Set(errors)].join('\n'));
await browser.close();
server.close();
