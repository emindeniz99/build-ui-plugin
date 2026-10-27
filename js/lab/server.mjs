// Strict-CSP lab server: serves node_modules + test pages, builds an import map
// for every bare specifier reachable from the entry modules.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

// ROOT is the js/ project (it owns node_modules); pages live in lab/pages.
const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const PAGES = path.join(ROOT, 'lab', 'pages');
const NM = path.join(ROOT, 'node_modules');

function pkgOf(spec) {
  const parts = spec.split('/');
  const name = spec.startsWith('@') ? parts.slice(0, 2).join('/') : parts[0];
  return { name, sub: '.' + spec.slice(name.length) };
}
function pick(t) {
  if (typeof t === 'string') return t;
  if (Array.isArray(t)) return pick(t[0]);
  if (t && typeof t === 'object') {
    for (const k of ['browser', 'import', 'module', 'default']) if (k in t) return pick(t[k]);
  }
  return null;
}
function resolveBare(spec) {
  const { name, sub } = pkgOf(spec);
  const dir = path.join(NM, name);
  const pj = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8'));
  let rel = null;
  if (pj.exports) {
    const ex = typeof pj.exports === 'string' || pj.exports['.'] === undefined && !Object.keys(pj.exports).some(k => k.startsWith('.'))
      ? { '.': pj.exports } : pj.exports;
    if (ex[sub] !== undefined) rel = pick(ex[sub]);
    else for (const [k, v] of Object.entries(ex)) {
      if (k.includes('*')) {
        const [a, b] = k.split('*');
        if (sub.startsWith(a) && sub.endsWith(b)) { rel = pick(v)?.replace('*', sub.slice(a.length, sub.length - b.length)); break; }
      }
    }
  }
  if (!rel) rel = sub === '.' ? (pj.module || pj.main || 'index.js') : sub;
  return path.join(dir, rel);
}
const IMPORT_RE = /(?:import|export)\s*(?:[^'"]*?from\s*)?['"]([^'"]+)['"]|import\(\s*['"]([^'"]+)['"]\s*\)/g;
function crawl(entries) {
  const map = {}; const seen = new Set(); const stack = [...entries];
  while (stack.length) {
    const file = stack.pop();
    if (seen.has(file) || !fs.existsSync(file)) continue;
    seen.add(file);
    const src = fs.readFileSync(file, 'utf8');
    for (const m of src.matchAll(IMPORT_RE)) {
      const spec = m[1] || m[2];
      if (spec.startsWith('.') || spec.startsWith('/')) { stack.push(path.resolve(path.dirname(file), spec)); continue; }
      if (spec === 'react' || spec.startsWith('@lit/react')) continue;
      if (!map[spec]) {
        const abs = resolveBare(spec);
        map[spec] = '/' + path.relative(ROOT, abs);
        stack.push(abs);
      }
    }
  }
  return map;
}

const WA = 'node_modules/@awesome.me/webawesome/dist/components';
const waEntries = ['input/input.js', 'select/select.js', 'option/option.js', 'checkbox/checkbox.js']
  .map(f => path.join(ROOT, WA, f));
const pageEntries = fs.readdirSync(PAGES).filter(f => f.endsWith('.mjs')).map(f => path.join(PAGES, f));
const imports = crawl([...waEntries, ...pageEntries]);
// Generic fallback: every installed package gets "name" and "name/" entries.
for (const top of fs.readdirSync(NM)) {
  const names = top.startsWith('@') ? fs.readdirSync(path.join(NM, top)).map(n => `${top}/${n}`) : [top];
  for (const n of names) {
    if (n.startsWith('.') || !fs.existsSync(path.join(NM, n, 'package.json'))) continue;
    try { imports[n] ||= '/' + path.relative(ROOT, resolveBare(n)); } catch {}
    imports[n + '/'] ||= `/node_modules/${n}/`;
  }
}
// Entries the pages import by bare name.
Object.assign(imports, {
  'preact': '/node_modules/preact/dist/preact.mjs',
  'preact/hooks': '/node_modules/preact/hooks/dist/hooks.mjs',
  'htm': '/node_modules/htm/dist/htm.mjs',
  'lit-html': imports['lit-html'] || '/node_modules/lit-html/lit-html.js',
  'vue': '/node_modules/vue/dist/vue.esm-browser.prod.js',
});
const importMap = JSON.stringify({ imports }, null, 1);
const hash = crypto.createHash('sha256').update(importMap).digest('base64');
// LAB_CSP replays a real policy (for example the header Jenkins sends); the lab
// adds the import map hash to script-src because the lab page needs one.
const OWN_CSP = `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'`;
const CSP = (process.env.LAB_CSP || OWN_CSP).replace(/script-src ([^;]*)/, `script-src $1 'sha256-${hash}'`);

const PORT = Number(process.env.PORT || 4321);
// Loopback only, and a Host check: a page on another site could otherwise read these files through DNS rebinding.
const HOSTS = [`127.0.0.1:${PORT}`, `localhost:${PORT}`];
const TYPES = { '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.html': 'text/html', '.svg': 'image/svg+xml', '.woff2': 'font/woff2' };
http.createServer((req, res) => {
  if (!HOSTS.includes(req.headers.host)) { res.writeHead(403); res.end(); return; }
  const url = new URL(req.url, 'http://x');
  if (url.pathname.startsWith('/page/')) {
    const name = url.pathname.slice(6);
    const headers = { 'content-type': 'text/html' };
    const mode = url.searchParams.get('csp');
    if (mode === 'report') headers['content-security-policy-report-only'] = CSP;
    else if (mode !== 'off') headers['content-security-policy'] = CSP;
    res.writeHead(200, headers);
    res.end(`<!doctype html><html><head><meta charset="utf-8">
<script type="importmap">${importMap}</script>
${name.startsWith('ex-') ? '' : '<link rel="stylesheet" href="/node_modules/@awesome.me/webawesome/dist/styles/webawesome.css">'}
<link rel="stylesheet" href="/pages/app.css">
</head><body><div id="app"></div><pre id="out"></pre>
<script type="module" src="/pages/${name}.mjs"></script></body></html>`);
    return;
  }
  const rel = decodeURIComponent(url.pathname);
  const file = rel.startsWith('/pages/') ? path.join(PAGES, rel.slice(7)) : path.join(ROOT, rel);
  if (!file.startsWith(ROOT + path.sep) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'content-type': TYPES[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
}).listen(PORT, '127.0.0.1', () => console.log('lab on', `http://127.0.0.1:${PORT}`, 'mapped', Object.keys(imports).length));
