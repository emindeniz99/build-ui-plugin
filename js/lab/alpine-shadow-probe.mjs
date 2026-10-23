// How does Alpine behave when it may run only inside a form's shadow root?
// Serves @alpinejs/csp as it ships (not the build-ui runtime, which disables
// Alpine.start) under Jenkins's CSP on 127.0.0.1:4331 and drives Chromium.
// Run: node js/lab/alpine-shadow-probe.mjs
import http from 'node:http';
import { readFileSync } from 'node:fs';
import { chromium } from '@playwright/test';

const CSP = "base-uri 'none'; default-src 'self'; form-action 'self'; frame-ancestors 'self'; img-src 'self' data:; script-src 'report-sample' 'self' usage.jenkins.io; style-src 'report-sample' 'self' 'unsafe-inline'";
const alpine = readFileSync(new URL('../node_modules/@alpinejs/csp/dist/module.esm.js', import.meta.url), 'utf8');

const probe = `
import Alpine from '/alpine.mjs';
const results = {};
const record = (k, v) => { results[k] = v; };
const tick = () => new Promise((r) => setTimeout(r, 50));
const count = { watch: {}, destroy: {} };

// One form: a shadow root on its own host element, as host.js makes it.
function form(id, component, inner = '') {
  const host = document.createElement('div');
  document.body.append(host);
  const root = document.createElement('div');
  root.innerHTML = '<div x-data="' + component + '"><input x-model="branch"><span class="out" x-text="branch"></span>'
    + '<p class="cond" x-show="deploy">deploy</p>'
    + '<ul><template x-for="s in services" :key="s"><li x-text="s"></li></template></ul>'
    + '<template x-if="deploy"><div class="if">' + inner + '</div></template></div>';
  host.attachShadow({ mode: 'open' }).append(root);
  return { host, root, data: () => Alpine.$data(root.firstElementChild) };
}
function component(id, branch) {
  return () => ({
    branch, deploy: false, services: ['api'],
    init() { this.$watch('branch', () => { count.watch[id] = (count.watch[id] || 0) + 1; }); },
    destroy() { count.destroy[id] = (count.destroy[id] || 0) + 1; },
  });
}
const type = (input, text) => { input.value = text; input.dispatchEvent(new Event('input')); };

// Markup outside any form, standing for the Jenkins page or another plugin.
Alpine.data('pageWidget', () => ({ label: 'initialised' }));
document.getElementById('page').innerHTML = '<span x-data="pageWidget" x-text="label">untouched</span>';

Alpine.data('formA', component('a', 'main-a'));
Alpine.data('formB', component('b', 'main-b'));
const a = form('a', 'formA');
const b = form('b', 'formB');
Alpine.initTree(a.root);
Alpine.initTree(b.root);
await tick();
record('initTree without start: x-text', a.root.querySelector('.out').textContent);
type(a.root.querySelector('input'), 'feature');
await tick();
record('x-model in form A', a.root.querySelector('.out').textContent);
record('form B after typing in A', b.root.querySelector('.out').textContent);
a.data().deploy = true;
a.data().services.push('web');
await tick();
record('x-show', getComputedStyle(a.root.querySelector('.cond')).display);
record('x-for items', a.root.querySelectorAll('li').length);
record('x-if rendered', Boolean(a.root.querySelector('.if')));
record('page markup without start()', document.querySelector('#page span').textContent);

// Cleanup with destroyTree.
const dataA = a.data();
Alpine.destroyTree(a.root);
record('destroy() calls after destroyTree', count.destroy.a || 0);
const watched = count.watch.a;
dataA.branch = 'changed-after-destroy';
await tick();
record('watcher runs after destroyTree', count.watch.a - watched);
record('x-text after destroyTree', a.root.querySelector('.out').textContent);
type(a.root.querySelector('input'), 'typed-after-destroy');
await tick();
record('x-model after destroyTree', dataA.branch);

// Removal alone, without destroyTree.
const dataB = b.data();
b.host.remove();
await tick();
record('destroy() calls after removing a form without destroyTree', count.destroy.b || 0);
const watchedB = count.watch.b || 0;
dataB.branch = 'changed-after-removal';
await tick();
record('watcher runs after removal without destroyTree', (count.watch.b || 0) - watchedB);

// Two forms that register the same component name.
Alpine.data('shared', component('d', 'from-D'));
const d = form('d', 'shared', '<span class="nested" x-data="shared" x-text="branch"></span>');
Alpine.initTree(d.root);
Alpine.data('shared', component('e', 'from-E'));
const e = form('e', 'shared');
Alpine.initTree(e.root);
await tick();
record('same name: form D top level', d.root.querySelector('.out').textContent);
record('same name: form E top level', e.root.querySelector('.out').textContent);
d.data().deploy = true;
await tick();
record('same name: component created later inside form D', d.root.querySelector('.nested')?.textContent);

// What Alpine.start() reaches.
const c = form('c', 'formA');
Alpine.start();
await tick();
record('start(): page markup', document.querySelector('#page span').textContent);
record('start(): uninitialised form in a shadow root', c.root.querySelector('.out').textContent || '(empty)');
const late = document.createElement('span');
late.setAttribute('x-data', 'pageWidget');
late.setAttribute('x-text', 'label');
late.textContent = 'untouched';
document.getElementById('page').append(late);
await tick();
record('start(): page markup added later', late.textContent);
window.results = results;
`;

const FILES = {
  '/page.html': ['text/html', '<!doctype html><div id="page"></div><script type="module" src="/probe.mjs"></script>'],
  '/probe.mjs': ['text/javascript', probe],
  '/alpine.mjs': ['text/javascript', alpine],
};
const server = http.createServer((req, res) => {
  const f = FILES[req.url];
  if (!f) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'content-type': f[0], 'content-security-policy': CSP });
  res.end(f[1]);
});
await new Promise((r) => server.listen(4331, '127.0.0.1', r));

const browser = await chromium.launch();
const page = await browser.newPage();
const messages = [];
page.on('console', (m) => ['error', 'warning'].includes(m.type()) && messages.push(`${m.type()}: ${m.text().slice(0, 160)}`));
page.on('pageerror', (e) => messages.push(`pageerror: ${e.message}`));
await page.addInitScript(() => document.addEventListener('securitypolicyviolation', (e) => console.error(`CSP ${e.violatedDirective}`)));
await page.goto('http://127.0.0.1:4331/page.html');
await page.waitForFunction(() => window.results, null, { timeout: 10_000 });
for (const [k, v] of Object.entries(await page.evaluate(() => window.results))) console.log(`${k}: ${JSON.stringify(v)}`);
console.log(messages.length ? `--- console\n${messages.join('\n')}` : '--- no console errors, warnings or CSP violations');
await browser.close();
server.close();
