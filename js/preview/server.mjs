#!/usr/bin/env node
// build-ui preview: renders a Jenkinsfile's uiParameter forms, or one formFrom
// folder, the way Jenkins does, without a Jenkins.
//
//   pnpm --dir projects/jenkins-ui/js preview <Jenkinsfile | formFrom folder>
//     [--param NAME] [--data fixtures.json] [--port N] [--root DIR] [--runtime ID]
//
// The page carries the markup of UiParameterDefinition/index.jelly, loads the
// shipped host.js and runtime bundles from src/main/webapp, and is served with
// Jenkins's Content Security Policy. ui.query is answered from the --data file.
// Next to each form the page shows the value and the controller's verdict on it.
// The page reloads when the Jenkinsfile, a formFrom folder or the data file changes.
import http from 'node:http';
import crypto from 'node:crypto';
import { readFileSync, statSync, watch } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import {
  checkSchema, checkSource, contract, findCalls, formFromPathError, parseJson, readFormFolder, repoRoot, splitHtml, tokenize,
} from '../scripts/check.mjs';
import { equal, validate } from './schema.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEBAPP = path.resolve(HERE, '../../src/main/webapp');

/** Jenkins 2.541.1 and 2.580.1 send this header (docs/design.md section 6); report-uri points at the preview. */
export const CSP = "base-uri 'none'; default-src 'self'; form-action 'self'; frame-ancestors 'self'; img-src 'self' data:;"
  + " script-src 'report-sample' 'self' usage.jenkins.io; style-src 'report-sample' 'self' 'unsafe-inline'; report-uri /_preview/csp-report";

// host.js reads these from <head>, as on a Jenkins page.
const RES_URL = '/static/preview';
const PLUGIN_URL = `${RES_URL}/plugin/build-ui/`;
const JOB = 'preview';
const RUNTIME_FILES = contract.runtimes.filter((id) => id !== 'none').map((id) => `runtimes/${id}.js`);
const CONTENT_TYPES = { mjs: 'text/javascript;charset=UTF-8', css: 'text/css;charset=UTF-8', 'html.txt': 'text/plain;charset=UTF-8' };
const FORM_FIELDS = ['module', 'html', 'css', 'schema', 'defaultJson'];

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const blank = (s) => s == null || s.trim() === '';
const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

/** The parts the controller derives from a form's fields (FormCode.of): the html split, CSS joined after css. */
function formCode({ module, html, css, schema, defaultJson }, files) {
  const htmlName = files ? 'form.html' : 'html';
  const errors = [];
  const report = (level, message) => { if (level === 'error') errors.push(message); };
  const parts = blank(html) ? { module: null, html: null, css: null, errors: [] } : splitHtml(html, htmlName);
  errors.push(...parts.errors);
  const m = blank(module) ? null : module;
  if (m !== null && parts.module !== null) {
    errors.push(`${htmlName} has a <script type="module"> and the form has ${files ? 'form.mjs' : 'module'} too; keep one of them`);
  }
  const c = blank(css) ? null : css;
  const schemaText = blank(schema) ? null : schema.trim();
  let parsed = null;
  if (schemaText !== null) {
    parsed = parseJson(files ? 'schema.json' : 'schema', schemaText, report) ?? null;
    if (parsed !== null) checkSchema(parsed, '', report);
  }
  const d = blank(defaultJson) ? null : defaultJson.trim();
  if (d !== null) parseJson(files ? 'default.json' : 'defaultJson', d, report);
  return {
    module: m ?? parts.module,
    html: parts.html,
    css: c === null ? parts.css : parts.css === null ? c : `${c}\n${parts.css}`,
    schemaText,
    schema: parsed,
    defaultJson: d,
    error: errors[0] ?? null,
  };
}

/** A string field of a call, or throws when it is not a plain literal the preview can read. */
function literal(call, key) {
  const token = call.args[key];
  if (token === undefined) return null;
  if (token.nonLiteral || token.type === 'bool' || token.dynamic) {
    throw new Error(`${key} is not a plain string literal, so the preview cannot read it`);
  }
  return token.value;
}

/** Reads one formFrom folder into fields; returns { fields } or { error }. */
function readFolder(root, rel) {
  const pathError = formFromPathError(rel);
  if (pathError) return { error: pathError };
  const folder = readFormFolder(root, rel);
  if (!folder) return { error: `formFrom folder '${rel}' is not under ${root}; pass --root with the repository root` };
  if (folder.errors.length) return { error: `${rel}/${folder.errors[0].file}: ${folder.errors[0].message}` };
  const f = folder.files;
  return { fields: { module: f['form.mjs'], html: f['form.html'], css: f['form.css'], schema: f['schema.json'], defaultJson: f['default.json'] } };
}

/**
 * Reads the target into parameters as the page renders them:
 * [{ name, description, runtime, sandbox, module, html, css, schemaText, schema, defaultJson, error }],
 * plus the checker's findings and the folders to watch.
 */
export async function loadForms({ target, param, root, runtime: folderRuntime }) {
  const isDir = statSync(target).isDirectory();
  let src;
  let base;
  if (isDir) {
    // A folder on its own: check and render it as `uiParameter(name: <folder>, formFrom: <folder>)` would.
    base = path.dirname(target);
    const name = param ?? path.basename(target);
    src = `uiParameter(name: '${name}', formFrom: '${path.basename(target)}'${folderRuntime ? `, runtime: '${folderRuntime}'` : ''})\n`;
  } else {
    base = root ?? repoRoot(target);
    src = readFileSync(target, 'utf8');
  }
  const problems = (await checkSource(src, { root: base })).map((p) =>
    `${isDir ? target : path.basename(target)}${isDir ? '' : `:${p.line}:${p.column}`}: ${p.level}: ${p.message}`);
  const watched = new Set();
  const params = [];
  for (const call of findCalls(tokenize(src), ['uiParameter'])) {
    const p = { name: call.args.name?.value ?? '', description: null, runtime: 'preact11-htm3', sandbox: false, error: null };
    try {
      p.name = literal(call, 'name') ?? '';
      if (param && p.name !== param) continue;
      if (!p.name) throw new Error('name is required');
      p.description = literal(call, 'description');
      p.runtime = literal(call, 'runtime') ?? 'preact11-htm3';
      if (!contract.runtimes.includes(p.runtime)) throw new Error(`unknown runtime '${p.runtime}'; available: ${contract.runtimes.join(', ')}`);
      const sandbox = call.args.sandbox;
      if (sandbox !== undefined && sandbox.type !== 'bool') throw new Error('sandbox must be true or false');
      p.sandbox = sandbox?.value === true;
      let fields;
      const formFrom = literal(call, 'formFrom');
      if (formFrom !== null) {
        const inline = FORM_FIELDS.filter((key) => key in call.args);
        if (inline.length) throw new Error(`formFrom reads ${FORM_FIELDS.join(', ')} from the folder; remove ${inline.join(', ')}`);
        watched.add(path.join(base, formFrom));
        const read = readFolder(base, formFrom);
        if (read.error) throw new Error(read.error);
        fields = read.fields;
      } else {
        fields = Object.fromEntries(FORM_FIELDS.map((key) => [key, literal(call, key)]));
      }
      const code = formCode(fields, formFrom !== null);
      Object.assign(p, code);
      p.formFrom = formFrom;
    } catch (e) {
      p.error = e.message;
    }
    params.push(p);
  }
  return { params, problems, watched: [...watched] };
}

// ---- page ---------------------------------------------------------------

/** The markup of UiParameterDefinition/index.jelly, inside an approximation of Jenkins's f:entry. */
function parameterMarkup(p, store) {
  const initial = p.error ? '{}' : (p.defaultJson ?? '{}');
  let body;
  if (p.error) {
    body = `<p class="build-ui-pending build-ui-form-error">This form could not be read, so enter the value as JSON. ${esc(p.error)}</p>`;
  } else {
    const key = (text, ext) => (text == null ? null : store.put(text, ext));
    const attrs = {
      'data-name': p.name,
      'data-job': JOB,
      'data-runtime': p.runtime,
      'data-eval': String(contract.evalRuntimes.includes(p.runtime)),
      'data-module': key(p.module, 'mjs'),
      'data-css': key(p.css, 'css'),
      'data-html': key(p.html, 'html.txt'),
      'data-schema': p.schemaText,
      // SandboxAction.applies: sandboxed only when there is code to sandbox.
      'data-sandbox': String(p.sandbox && (p.module != null || p.html != null || p.css != null)),
    };
    body = `<div class="build-ui-root"${Object.entries(attrs).filter(([, v]) => v != null).map(([k, v]) => ` ${k}="${esc(v)}"`).join('')}></div>`;
  }
  return `<div class="jenkins-form-item">
  <div class="jenkins-form-label">${esc(p.name)}</div>
  <div class="setting-main">
    <div name="parameter" class="build-ui">
      <input type="hidden" name="name" value="${esc(p.name)}">
      ${body}
      <textarea name="value" class="jenkins-input build-ui-value" rows="6" spellcheck="false">${esc(initial)}</textarea>
      ${p.error ? '' : `<script type="module" src="${PLUGIN_URL}host.js"></script>`}
    </div>
  </div>
  ${p.description ? `<div class="jenkins-form-description">${esc(p.description)}</div>` : ''}
</div>`;
}

function page({ title, params, problems }, store) {
  const rows = params.map((p) => `<div class="preview-row">
  ${parameterMarkup(p, store)}
  <aside class="preview-value" data-for="${esc(p.name)}" aria-label="Value of ${esc(p.name)}">
    <h2>Value of ${esc(p.name)}</h2>
    <pre class="preview-json"></pre>
    <p class="preview-verdict" role="status"></p>
    <ul class="preview-errors"></ul>
  </aside>
</div>`).join('\n');
  const findings = problems.length
    ? `<ul>${problems.map((p) => `<li>${esc(p)}</li>`).join('')}</ul>`
    : '<p>No findings.</p>';
  return `<!DOCTYPE html>
<html lang="en">
<head data-rooturl="" data-resurl="${RES_URL}">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Preview: ${esc(title)}</title>
<link rel="stylesheet" href="/_preview/preview.css">
<script type="module" src="/_preview/preview.js"></script>
</head>
<body>
<header><h1>Preview: ${esc(title)}</h1></header>
<main>
<section class="preview-findings" aria-label="Checker findings"><h2>Checker</h2>${findings}</section>
<section class="preview-csp" aria-label="CSP violations" hidden><h2>CSP violations</h2><ul></ul></section>
${rows || '<p class="preview-empty">No uiParameter to show.</p>'}
</main>
</body>
</html>
`;
}

/** The iframe document of a sandboxed form, as BuildUiAction.frame writes it. */
function framePage(runtime, moduleURL) {
  const moduleAttr = moduleURL ? ` data-module="${esc(moduleURL)}"` : '';
  return `<!DOCTYPE html>\n<html lang="en" data-runtime="${esc(runtime)}"${moduleAttr}><head><meta charset="utf-8"><title>build-ui form</title>`
    + '<script type="module" src="/build-ui-sandbox/boot.js"></script></head><body></body></html>\n';
}

// ---- data providers -----------------------------------------------------

/**
 * Answers ui.query(id, args) from the fixtures: `fixtures[id]` is the answer
 * for any args, or { "$cases": [{ "args": {...}, "answer": ... }] } picks the
 * first case whose args all equal the query's (a case without args matches any).
 */
export function answerQuery(fixtures, id, args, dataFile) {
  const missing = (why) => ({ status: 404, body: { error: `preview: ${why}` } });
  if (!fixtures) return missing(`no data file, so data provider '${id}' has no answer; start the preview with --data <fixtures.json>`);
  if (!Object.hasOwn(fixtures, id)) {
    return missing(`${dataFile} has no entry for data provider '${id}' (args ${JSON.stringify(args)}); add "${id}": <answer>`);
  }
  const entry = fixtures[id];
  if (isObject(entry) && Array.isArray(entry.$cases) && Object.keys(entry).length === 1) {
    const hit = entry.$cases.find((c) => !isObject(c.args) || Object.entries(c.args).every(([k, v]) => equal(args[k], v)));
    if (!hit) return missing(`no case of '${id}' in ${dataFile} matches args ${JSON.stringify(args)}`);
    return { status: 200, body: hit.answer ?? null };
  }
  return { status: 200, body: entry };
}

function readFixtures(file) {
  if (!file) return null;
  const text = readFileSync(file, 'utf8');
  const value = JSON.parse(text);
  if (!isObject(value)) throw new Error(`${file} must hold one JSON object keyed by data provider id`);
  return value;
}

// ---- server -------------------------------------------------------------

const isLoopback = (host) => host === '::1' || /^127(?:\.\d{1,3}){3}$/.test(host);

function contentStore() {
  const entries = new Map();
  return {
    put(text, ext) {
      const key = `${crypto.createHash('sha256').update(text, 'utf8').digest('hex')}.${ext}`;
      if (!entries.has(key)) entries.set(key, { text, type: CONTENT_TYPES[ext] });
      return key;
    },
    get: (key) => entries.get(key),
  };
}

async function body(req, limit = 1024 * 1024) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) throw new Error('request body too large');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString('utf8');
}

/**
 * Starts the preview. Options: target (Jenkinsfile or folder, absolute or
 * relative to cwd), param, data, port (0 picks one), root, runtime, host
 * (loopback only), log. Resolves to { url, close(), server }.
 */
export async function startPreview({ target, param, data, port = 4380, root, runtime, host = '127.0.0.1', log = console } = {}) {
  if (!isLoopback(host)) throw new Error(`the preview listens on loopback only (127.0.0.1 or ::1); refusing ${host}`);
  if (!target) throw new Error('no target: name a Jenkinsfile or a formFrom folder');
  target = path.resolve(target);
  data = data && path.resolve(data);
  root = root && path.resolve(root);
  if (runtime && !contract.runtimes.includes(runtime)) throw new Error(`unknown runtime '${runtime}'; available: ${contract.runtimes.join(', ')}`);
  const options = { target, param, root, runtime };
  const first = await loadForms(options);
  if (param && first.params.length === 0) throw new Error(`no uiParameter named '${param}' in ${target}`);
  readFixtures(data); // fail at start on a broken data file

  const store = contentStore();
  const tokens = new Map(); // sandbox module tokens, single use as in SandboxTokens
  let current = first;
  const clients = new Set();
  const watchers = new Map();
  let timer = null;
  const reload = (why) => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      log.log(`changed: ${why}; reloading the page`);
      for (const res of clients) res.write('data: reload\n\n');
    }, 100);
  };
  // Watches folders, not files: editors often replace a file, which ends a watch on it.
  const watchDir = (dir, only) => {
    const key = `${dir}\0${only ?? ''}`;
    if (watchers.has(key)) return;
    try {
      watchers.set(key, watch(dir, (event, name) => {
        if (!only || name === only) reload(path.join(dir, name ?? ''));
      }));
    } catch (e) {
      if (e.code !== 'ENOENT') throw e; // a missing formFrom folder; the page shows why
    }
  };
  const watchAll = () => {
    if (statSync(target).isDirectory()) watchDir(target);
    else watchDir(path.dirname(target), path.basename(target));
    for (const dir of current.watched) watchDir(dir);
    if (data) watchDir(path.dirname(data), path.basename(data));
  };
  watchAll();

  const send = (res, status, type, text, headers = {}) => {
    res.writeHead(status, { 'Content-Type': type, ...headers });
    res.end(text);
  };
  const json = (res, status, value) => send(res, status, 'application/json;charset=UTF-8', JSON.stringify(value), { 'Cache-Control': 'no-store' });
  const html = (res, text) => send(res, 200, 'text/html;charset=UTF-8', text, { 'Content-Security-Policy': CSP, 'Cache-Control': 'no-store' });
  const file = (res, full, headers = {}) => send(res, 200, 'text/javascript;charset=UTF-8', readFileSync(full), { 'Cache-Control': 'no-cache', ...headers });

  const server = http.createServer(async (req, res) => {
    try {
      // A loopback bind still answers pages on other sites through DNS rebinding; the Host header gives those away.
      const { port: actual } = server.address();
      const hosts = [`127.0.0.1:${actual}`, `localhost:${actual}`, `[::1]:${actual}`];
      if (!hosts.includes(req.headers.host)) { send(res, 403, 'text/plain', `preview: unexpected Host header ${req.headers.host}\n`); return; }
      const url = new URL(req.url, `http://${req.headers.host}`);
      const p = url.pathname;
      if (req.method === 'GET' && p === '/') {
        try {
          current = await loadForms(options);
          watchAll();
        } catch (e) {
          current = { params: [], problems: [`cannot read ${target}: ${e.message}`], watched: [] };
        }
        html(res, page({ title: path.relative(process.env.INIT_CWD ?? process.cwd(), target) || target, ...current }, store));
      } else if (req.method === 'GET' && p.startsWith('/build-ui/query/')) {
        let args;
        try {
          args = JSON.parse(url.searchParams.get('args') ?? '{}');
        } catch (e) {
          json(res, 400, { error: `args is not valid JSON: ${e.message}` });
          return;
        }
        const id = decodeURIComponent(p.slice('/build-ui/query/'.length));
        let answer;
        try {
          answer = answerQuery(readFixtures(data), id, args, data && path.basename(data));
        } catch (e) {
          answer = { status: 500, body: { error: `preview: cannot read ${data}: ${e.message}` } };
        }
        if (answer.status !== 200) log.error(answer.body.error);
        json(res, answer.status, answer.body);
      } else if (req.method === 'GET' && p === '/build-ui/frame') {
        const rt = url.searchParams.get('runtime');
        const module = url.searchParams.get('module');
        if (!contract.runtimes.includes(rt) || (module !== null && !store.get(module))) { send(res, 404, 'text/plain', 'not found\n'); return; }
        let moduleURL = null;
        if (module !== null) {
          const token = crypto.randomBytes(16).toString('hex');
          tokens.set(token, module);
          moduleURL = `/build-ui-sandbox/module/${token}.mjs`;
        }
        html(res, framePage(rt, moduleURL));
      } else if (req.method === 'GET' && p.startsWith('/build-ui/')) {
        const entry = store.get(p.slice('/build-ui/'.length));
        if (!entry) { send(res, 404, 'text/plain', 'not found\n'); return; }
        send(res, 200, entry.type, entry.text, { 'Cache-Control': 'private, max-age=31536000, immutable' });
      } else if (req.method === 'GET' && p.startsWith('/build-ui-sandbox/')) {
        // The frame has an opaque origin, so its module requests are CORS requests.
        const rest = p.slice('/build-ui-sandbox/'.length);
        const cors = { 'Access-Control-Allow-Origin': '*' };
        const token = /^module\/([0-9a-f]{32})\.mjs$/.exec(rest)?.[1];
        if (token && tokens.has(token)) {
          const entry = store.get(tokens.get(token));
          tokens.delete(token);
          send(res, 200, 'text/javascript;charset=UTF-8', entry.text, { 'Cache-Control': 'no-store', ...cors });
        } else if (rest === 'boot.js') {
          file(res, path.join(WEBAPP, 'sandbox/boot.js'), cors);
        } else if (RUNTIME_FILES.includes(rest)) {
          file(res, path.join(WEBAPP, rest), cors);
        } else {
          send(res, 404, 'text/plain', 'not found\n', cors);
        }
      } else if (req.method === 'GET' && p.startsWith(PLUGIN_URL)) {
        const rest = p.slice(PLUGIN_URL.length);
        if (rest === 'host.js' || RUNTIME_FILES.includes(rest)) file(res, path.join(WEBAPP, rest));
        else send(res, 404, 'text/plain', 'not found\n');
      } else if (req.method === 'GET' && p === '/_preview/preview.js') {
        file(res, path.join(HERE, 'client.js'));
      } else if (req.method === 'GET' && p === '/_preview/preview.css') {
        send(res, 200, 'text/css;charset=UTF-8', readFileSync(path.join(HERE, 'client.css')), { 'Cache-Control': 'no-cache' });
      } else if (req.method === 'POST' && p === '/_preview/validate') {
        const { name, value } = JSON.parse(await body(req));
        const param = current.params.find((x) => x.name === name);
        if (!param) { json(res, 404, { error: `no parameter '${name}'` }); return; }
        json(res, 200, { errors: valueErrors(param, String(value)) });
      } else if (req.method === 'POST' && p === '/_preview/csp-report') {
        const report = JSON.parse(await body(req))['csp-report'] ?? {};
        log.error(`CSP violation: ${report['violated-directive'] ?? report['effective-directive']} blocked ${report['blocked-uri']}`
          + `${report['source-file'] ? ` in ${report['source-file']}:${report['line-number']}` : ''}`);
        send(res, 204, 'text/plain', '');
      } else if (req.method === 'GET' && p === '/_preview/events') {
        res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' });
        res.write(': connected\n\n');
        clients.add(res);
        req.on('close', () => clients.delete(res));
      } else {
        send(res, 404, 'text/plain', 'not found\n');
      }
    } catch (e) {
      log.error(`preview: ${req.method} ${req.url}: ${e.stack ?? e}`);
      if (!res.headersSent) send(res, 500, 'text/plain', `preview: ${e.message}\n`);
      else res.end();
    }
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, resolve);
  });
  const address = server.address();
  const url = `http://${address.family === 'IPv6' ? `[${address.address}]` : address.address}:${address.port}/`;
  return {
    url,
    server,
    problems: [...first.problems, ...first.params.filter((p) => p.error).map((p) => `${p.name}: the page shows the JSON field instead of this form: ${p.error}`)],
    close() {
      clearTimeout(timer);
      for (const w of watchers.values()) w.close();
      for (const res of clients) res.end();
      server.closeAllConnections();
      return new Promise((resolve) => server.close(() => resolve()));
    },
  };
}

/** What the controller answers for a submitted value: Json.parse's limits, then the schema. */
export function valueErrors(param, text) {
  if (param.error) return [param.error];
  const errors = [];
  const what = `value of ${param.name}`;
  if (text.trim() === '') return [`${what} is empty`];
  const value = parseJson(what, text, (level, message) => { if (level === 'error') errors.push(message); });
  if (errors.length || value === undefined) return errors;
  return param.schema === null ? [] : validate(param.schema, value);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const usage = 'usage: preview <Jenkinsfile | formFrom folder> [--param NAME] [--data fixtures.json] [--port N] [--root DIR] [--runtime ID]';
  let parsed;
  try {
    parsed = parseArgs({
      allowPositionals: true,
      options: { param: { type: 'string' }, data: { type: 'string' }, port: { type: 'string' }, root: { type: 'string' }, runtime: { type: 'string' } },
    });
  } catch (e) {
    console.error(`${e.message}\n${usage}`);
    process.exit(2);
  }
  const { values, positionals } = parsed;
  if (positionals.length !== 1) {
    console.error(usage);
    process.exit(2);
  }
  // `pnpm --dir js preview <file>` runs this in js/; pnpm puts the caller's directory in INIT_CWD.
  const base = process.env.INIT_CWD ?? process.cwd();
  const resolve = (p) => p && path.resolve(base, p);
  const port = values.port === undefined ? 4380 : Number(values.port);
  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    console.error(`--port must be a number from 0 to 65535, not ${values.port}`);
    process.exit(2);
  }
  try {
    const preview = await startPreview({
      target: resolve(positionals[0]), param: values.param, data: resolve(values.data), port, root: resolve(values.root), runtime: values.runtime,
    });
    for (const p of preview.problems) console.log(p);
    console.log(`preview of ${positionals[0]} at ${preview.url} (Ctrl+C stops it)`);
  } catch (e) {
    console.error(`preview: ${e.message}`);
    process.exit(1);
  }
}
