// What do the eval runtimes do under Jenkins's CSP, enforced and report-only,
// when nothing checks for eval first? Serves the shipped bundles from
// src/main/webapp/runtimes on 127.0.0.1:4332 and drives Chromium.
// Run: node js/lab/eval-runtime-probe.mjs
import http from 'node:http';
import { readFileSync } from 'node:fs';
import { chromium } from '@playwright/test';

const CSP = "base-uri 'none'; default-src 'self'; form-action 'self'; frame-ancestors 'self'; img-src 'self' data:; script-src 'report-sample' 'self' usage.jenkins.io; style-src 'report-sample' 'self' 'unsafe-inline'";
const bundle = (id) => readFileSync(new URL(`../../src/main/webapp/runtimes/${id}.js`, import.meta.url), 'utf8');

const probe = `
const results = {};
const shadowRoot = (html) => {
  const host = document.createElement('div');
  document.body.append(host);
  const root = document.createElement('div');
  root.innerHTML = html;
  host.attachShadow({ mode: 'open' }).append(root);
  return root;
};
const settle = () => new Promise((r) => setTimeout(r, 200));
try { new Function(''); results['new Function'] = 'runs'; } catch (e) { results['new Function'] = e.constructor.name; }

const alpine = await import('/alpine3.js');
const a = shadowRoot('<div x-data="{ n: 41 }"><span x-text="n + 1">not rendered</span></div>');
try { alpine.start(a); results['alpine3 start(root)'] = 'returned'; } catch (e) { results['alpine3 start(root)'] = 'threw ' + e.constructor.name; }
await settle();
results['alpine3 text'] = a.querySelector('span').textContent;

const vue = await import('/vue3.js');
const v = shadowRoot('<p>{{ n + 1 }}</p>');
try { vue.createApp({ data: () => ({ n: 41 }) }).mount(v); results['vue3 mount'] = 'returned'; } catch (e) { results['vue3 mount'] = 'threw ' + e.constructor.name; }
await settle();
results['vue3 text'] = v.textContent;
window.results = results;
`;

const FILES = {
  '/page.html': ['text/html', '<!doctype html><script type="module" src="/probe.mjs"></script>'],
  '/probe.mjs': ['text/javascript', probe],
  '/alpine3.js': ['text/javascript', bundle('alpine3')],
  '/vue3.js': ['text/javascript', bundle('vue3')],
};
let header = 'content-security-policy';
const server = http.createServer((req, res) => {
  const f = FILES[req.url.split('?')[0]];
  if (!f) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'content-type': f[0], [header]: CSP });
  res.end(f[1]);
});
await new Promise((r) => server.listen(4332, '127.0.0.1', r));

const browser = await chromium.launch();
for (const mode of ['content-security-policy', 'content-security-policy-report-only']) {
  header = mode;
  const page = await browser.newPage();
  const messages = [];
  page.on('console', (m) => ['error', 'warning'].includes(m.type()) && messages.push(`${m.type()}: ${m.text().replace(/\s+/g, ' ').slice(0, 150)}`));
  page.on('pageerror', (e) => messages.push(`pageerror: ${e.message.slice(0, 150)}`));
  await page.addInitScript(() => document.addEventListener('securitypolicyviolation',
    (e) => console.error(`violation event: ${e.violatedDirective} ${e.blockedURI} disposition=${e.disposition}`)));
  await page.goto('http://127.0.0.1:4332/page.html');
  await page.waitForFunction(() => window.results, null, { timeout: 10_000 });
  console.log(`=== ${mode}`);
  for (const [k, v] of Object.entries(await page.evaluate(() => window.results))) console.log(`${k}: ${JSON.stringify(v)}`);
  const counts = new Map();
  for (const m of messages) counts.set(m, (counts.get(m) || 0) + 1);
  for (const [m, n] of counts) console.log(`  ${n}x ${m}`);
  await page.close();
}
await browser.close();
server.close();
