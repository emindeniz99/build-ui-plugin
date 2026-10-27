// The checker exists to catch, before Jenkins runs, the mistakes that fail
// silently at runtime (a dropped onclick, an import that needs an import map)
// or late (a schema keyword the controller rejects only when the job runs).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkSource, splitHtml } from '../scripts/check.mjs';

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');
const errors = (problems) => problems.filter((p) => p.level === 'error');
const script = fileURLToPath(new URL('../scripts/check.mjs', import.meta.url));
const jsDir = fileURLToPath(new URL('..', import.meta.url));
const projectDir = fileURLToPath(new URL('../..', import.meta.url));
// Runs the CLI the way `pnpm --dir js check ...` does: cwd is js/, the caller's directory is in INIT_CWD.
const cli = (args, initCwd) => spawnSync(process.execPath, [script, ...args], {
  cwd: jsDir, encoding: 'utf8', env: { ...process.env, INIT_CWD: initCwd },
});

test('every example Jenkinsfile and the README example are clean', async () => {
  for (const f of ['reference', 'parallel-envs', 'release']) {
    assert.deepEqual(await checkSource(read(`../../examples/${f}.Jenkinsfile`)), [], f);
  }
  // The formFrom example is a repository of its own: its folder paths start at examples/form-from.
  const formFromRoot = fileURLToPath(new URL('../../examples/form-from', import.meta.url));
  assert.deepEqual(await checkSource(read('../../examples/form-from/Jenkinsfile'), { root: formFromRoot }), []);
  const readme = read('../../README.md');
  for (const [, block] of readme.matchAll(/```groovy\n([\s\S]*?)```/g)) {
    assert.deepEqual(errors(await checkSource(block)), [], block.slice(0, 80));
  }
});

test('a module syntax error points at the Jenkinsfile line, not the module line', async () => {
  const src = [
    "uiParameter(name: 'C', module: '''",
    'export default function mount({ root }) {',
    '  root.textContent = "hi"',
    '  if (',
    "}''')",
  ].join('\n');
  const [p] = errors(await checkSource(src));
  assert.equal(p.line, 5);
  assert.match(p.message, /^uiParameter 'C': module: /);
});

test('an import statement is an error because the page has no import map', async () => {
  const p = await checkSource("uiParameter(name: 'C', module: 'import { h } from \"preact\"; export default function mount() {}')");
  assert.match(errors(p)[0].message, /imports 'preact'; job code gets its libraries through runtime/);
});

test('a module without a default export cannot be mounted', async () => {
  const p = await checkSource("uiParameter(name: 'C', module: 'export function mount() {}')");
  assert.match(errors(p)[0].message, /no default export/);
});

test('inline handlers and scripts in html are errors because CSP drops them silently', async () => {
  const p = errors(await checkSource(`uiParameter(name: 'C', module: 'export default () => {}',
    html: '<button onclick="go()">Go</button><script>x()</script>')`));
  assert.equal(p.length, 2);
  assert.match(p[0].message, /<script>/);
  assert.match(p[1].message, /inline handler onclick=/);
});

test('schema keywords come from the same contract the controller enforces', async () => {
  const contract = JSON.parse(read('../../src/main/resources/io/jenkins/plugins/buildui/contract.json'));
  assert.ok(contract.schemaValidations.includes('required'));
  const p = errors(await checkSource(`uiParameter(name: 'C', runtime: 'schema-form',
    schema: '{"type":"object","properties":{"a":{"$ref":"#/x"},"b":{"type":"strin"}}}')`));
  assert.deepEqual(p.map((x) => x.message.replace(/; supported.*/, '')), [
    "uiParameter 'C': schema at /properties/a: keyword '$ref' is not supported",
    "uiParameter 'C': schema at /properties/b: unknown type \"strin\"",
  ]);
});

test('an x-git-ref kind the controller does not know is an error', async () => {
  const p = errors(await checkSource(`uiParameter(name: 'C', module: 'export default () => {}',
    schema: '{"type":"object","properties":{"a":{"type":"string","x-git-ref":"tag"},"b":{"type":"string","x-git-ref":"tga"}}}')`));
  assert.deepEqual(p.map((x) => x.message), [
    "uiParameter 'C': schema at /properties/b: x-git-ref must be true, \"branch\", \"tag\" or \"any\"",
  ]);
});

test('x-git-repository follows the controller: a short glob, only next to x-git-ref', async () => {
  // Schema.check refuses these when the job is configured, so the checker must not report them clean.
  const p = errors(await checkSource(`uiParameter(name: 'C', module: 'export default () => {}',
    schema: '{"type":"object","properties":{"a":{"type":"string","x-git-ref":"tag","x-git-repository":"*/app.git"},"b":{"type":"string","x-git-repository":"*/app.git"},"c":{"type":"string","x-git-ref":"tag","x-git-repository":""}}}')`));
  assert.deepEqual(p.map((x) => x.message), [
    "uiParameter 'C': schema at /properties/b: x-git-repository needs x-git-ref next to it",
    "uiParameter 'C': schema at /properties/c: x-git-repository must be a glob of a repository URL, 1 to 100 characters",
  ]);
});

test('bad JSON in defaultJson and uiPanel data is reported with the field name', async () => {
  const p = errors(await checkSource(`
    uiParameter(name: 'C', module: 'export default () => {}', defaultJson: '{branch: main}')
    uiPanel title: 'Summary', data: '{"a":', module: 'export default () => {}'`));
  assert.equal(p.length, 2);
  assert.match(p[0].message, /^uiParameter 'C': defaultJson is not valid JSON/);
  assert.match(p[1].message, /^uiPanel 'Summary': data is not valid JSON/);
});

test('a runtime that needs eval is a warning: it breaks the day Jenkins enforces CSP', async () => {
  const contract = JSON.parse(read('../../src/main/resources/io/jenkins/plugins/buildui/contract.json'));
  assert.deepEqual([...contract.evalRuntimes].sort(), ['alpine3', 'vue3']);
  const p = await checkSource(`
    uiParameter(name: 'A', runtime: 'vue3', module: 'export default () => {}')
    uiPanel title: 'B', runtime: 'alpine3', module: 'export default () => {}'
    uiParameter(name: 'C', runtime: 'alpine3-csp', module: 'export default () => {}')`);
  assert.deepEqual(errors(p), []);
  assert.deepEqual(p.map((x) => x.message.split(';')[0]), [
    "uiParameter 'A': runtime 'vue3' needs eval",
    "uiPanel 'B': runtime 'alpine3' needs eval",
  ]);
  assert.match(p[0].message, /stops working when Jenkins enforces its Content Security Policy/);
  assert.match(p[0].message, /alpine3-csp/);
});

test('unknown runtimes, missing modules and non-object schema-form schemas are errors', async () => {
  const p = errors(await checkSource(`
    uiParameter(name: 'A', runtime: 'vue4', module: 'export default () => {}')
    uiParameter(name: 'B')
    uiParameter(name: 'D', runtime: 'schema-form', schema: '{"type":"array"}')`));
  assert.deepEqual(p.map((x) => x.message.split(';')[0]), [
    "uiParameter 'A': unknown runtime 'vue4'",
    "uiParameter 'B': module is required unless runtime is 'schema-form'",
    "uiParameter 'D': runtime 'schema-form' needs a schema whose type is \"object\"",
  ]);
});

test('values the checker cannot read are warnings, not silent passes', async () => {
  const p = await checkSource(`
    def mod = load('form.groovy')
    uiParameter(name: "C", module: mod, schema: "\${schemaText}", css: '''a{}'''.stripIndent())`);
  assert.deepEqual(errors(p), []);
  assert.deepEqual(p.map((x) => x.message.replace(/^uiParameter 'C': /, '').split(' ')[0]), ['module', 'schema', 'css']);
});

test('comments and strings that merely mention uiParameter are ignored', async () => {
  const p = await checkSource(`
    // uiParameter(name: 'X')
    echo 'call uiParameter(name: here)'
    uiParameter(name: 'C', module: 'export default () => {}')`);
  assert.deepEqual(p, []);
});

test('the CLI reads paths relative to the directory pnpm was run from, not js/', () => {
  // The README and CI both run `pnpm --dir <js> check <path>` with a path relative to the caller.
  const r = cli(['examples/reference.Jenkinsfile', 'examples/release.Jenkinsfile'], projectDir);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /^2 file\(s\) checked, no errors$/m);
});

test('a file the CLI cannot read is one error line, not a stack trace', () => {
  const r = cli(['examples/missing.Jenkinsfile'], projectDir);
  assert.equal(r.status, 1);
  assert.deepEqual(r.stderr.trim().split('\n'), ['examples/missing.Jenkinsfile: cannot read: ENOENT']);
});

test('command-style uiPanel keeps reading arguments after a computed value', async () => {
  // Panel data is usually computed, so the module after it must still be checked.
  const p = errors(await checkSource(`
    uiPanel title: 'S', data: JsonOutput.toJson([a: 1]), module: 'export default () => { nope('
    uiPanel title: 'a' + b, module: 'nope('
    uiPanel title: 'M',
      data: readFile('d.json'),
      module: 'nope('
    uiPanel title: 'E', module: 'export default () => {}', data: x
    otherStep module: 'nope('`));
  assert.deepEqual(p.map((x) => x.message.split(':')[0]), ["uiPanel 'S'", 'uiPanel', "uiPanel 'M'"]);
  assert.ok(p.every((x) => /: module: /.test(x.message)), JSON.stringify(p));
});

test('a backslash escape Groovy rejects is an error at its Jenkinsfile line', async () => {
  // Groovy refuses to compile '\\d' in a string; the checker must not report the file clean.
  const p = errors(await checkSource([
    "uiParameter(name: 'C', module: '''",
    "export default () => /\\d+/.test('1')",
    "''', css: 'a\\$b')",
  ].join('\n')));
  assert.deepEqual(p.map((x) => [x.line, x.message]), [[2, '\\d is not a Groovy escape; write \\\\d']]);
});

test('\\uXXXX and octal escapes are decoded the way Groovy decodes them', async () => {
  // '\\u003cscript>' is a <script> tag once Groovy has read the string.
  const p = errors(await checkSource(
    "uiParameter(name: 'C', module: 'export default () => {}', html: '\\u003cscript\\076x()</script>')"));
  assert.equal(p.length, 1);
  assert.match(p[0].message, /<script>/);
});

test('a slashy or dollar-slashy regex does not hide the calls after it', async () => {
  // /\/*$/ strips trailing slashes; its "/*" is not a comment and must not swallow the file.
  const p = errors(await checkSource(`
    def s = 'x/'.replaceAll(/\\/*$/, '')
    def t = $/a/*b/$
    def n = 4 / 2 / 1
    uiParameter(name: 'C', module: 'nope(')`));
  assert.equal(p.length, 1);
  assert.match(p[0].message, /^uiParameter 'C': module: /);
});

test('the CLI fails a file in which it found nothing to check', () => {
  // A file with no calls usually means the scan went wrong; "no errors" would be a false pass.
  const r = cli(['js/package.json'], projectDir);
  assert.equal(r.status, 1, r.stdout);
  assert.doesNotMatch(r.stdout, /no errors/);
  assert.match(r.stdout, /js\/package\.json:1:1: warning: no uiParameter or uiPanel call found/);
});

test('schema shapes the controller rejects when the job is configured are errors here too', async () => {
  const p = await checkSource(`uiParameter(name: 'C', runtime: 'schema-form', schema: '''{
    "type": "object", "required": "a", "maxProperties": "2",
    "properties": {
      "a": {"enum": []},
      "b": {"type": "string", "pattern": "("},
      "c": {"type": "array", "uniqueItems": "yes", "items": {"properties": []}}
    }}''')`);
  assert.deepEqual(errors(p).map((x) => x.message.replace(/^uiParameter 'C': /, '')), [
    'schema: required must be an array of names',
    'schema: maxProperties must be a number',
    'schema at /properties/a: enum must be a non-empty array',
    'schema at /properties/c: uniqueItems must be a boolean',
    'schema at /properties/c/items: properties must be an object',
  ]);
  // Java and JavaScript regex syntax differ, so a pattern JavaScript cannot compile is only a warning.
  assert.deepEqual(p.filter((x) => x.level === 'warning').map((x) => x.message.split(';')[0]),
    ["uiParameter 'C': schema at /properties/b: pattern does not compile as a JavaScript regular expression"]);
});

test('JSON the controller refuses is an error: duplicate keys, nesting and size limits', async () => {
  const deep = '['.repeat(33) + ']'.repeat(33);
  const wide = JSON.stringify(Array.from({ length: 1001 }, () => 0));
  const p = errors(await checkSource(`
    uiParameter(name: 'C', module: 'export default () => {}', defaultJson: '{"a": 1, "b": {"a": 2}, "a": 3}')
    uiPanel(title: 'D', data: '${deep}', module: 'export default () => {}')
    uiPanel(title: 'W', data: '${wide}', module: 'export default () => {}')
    uiPanel(title: 'OK', data: '${'['.repeat(32) + ']'.repeat(32)}', module: 'export default () => {}')`));
  assert.deepEqual(p.map((x) => x.message), [
    "uiParameter 'C': defaultJson has the key \"a\" twice in one object",
    "uiPanel 'D': data is nested deeper than 32 levels",
    "uiPanel 'W': data has an array with 1001 items; the limit is 1000",
  ]);
});

test('sandbox takes a boolean, and is reported where it would sandbox nothing', async () => {
  assert.deepEqual(await checkSource(`
    uiParameter(name: 'A', sandbox: true, module: 'export default () => {}')
    uiPanel title: 'P', sandbox: false, module: 'export default () => {}'`), []);
  // A quoted 'true' is a string; the checker asks for the boolean the field declares.
  const p = await checkSource(`
    uiParameter(name: 'B', sandbox: 'true', module: 'export default () => {}')
    uiParameter(name: 'C', sandbox: true, runtime: 'schema-form', schema: '{"type":"object"}')
    uiParameter(name: 'D', sandbox: params.SANDBOX, module: 'export default () => {}')`);
  assert.deepEqual(p.map((x) => [x.level, x.message]), [
    ['warning', "uiParameter 'B': sandbox is the string 'true'; write true or false without quotes"],
    ['warning', "uiParameter 'C': sandbox: true has no effect: this form runs no code of its own (no module, html or css)"],
    ['warning', "uiParameter 'D': sandbox is not a plain true or false, so it was not checked"],
  ]);
});

// ---- single-file html and formFrom ---------------------------------------
// The controller splits a uiParameter's html into module, css and markup, and
// reads formFrom folders from the branch head; the checker applies the same
// rules before the push, so the mistake shows up here rather than on the page.

test('a single-file html needs no module field, and its module script is checked like one', async () => {
  assert.deepEqual(await checkSource(`uiParameter(name: 'C', html: '''<style>p { color: red }</style>
<p>hi</p>
<script type="module">
export default function mount({ root }) { root.textContent = 'hi' }
</script>''')`), []);
  const p = errors(await checkSource([
    "uiParameter(name: 'C', html: '''<p>hi</p>",
    '<script type="module">',
    'export default function mount({ root }) {',
    '  if (',
    "}</script>''')",
  ].join('\n')));
  assert.equal(p.length, 1);
  assert.equal(p[0].line, 5, 'the error points at the Jenkinsfile line inside the script');
  assert.match(p[0].message, /^uiParameter 'C': module: /);
});

test('single-file html refuses what would not run: classic and src scripts, two modules, handlers, module twice', async () => {
  const messages = async (src) => errors(await checkSource(src)).map((x) => x.message.replace(/^uiParameter '\w': /, '').split(';')[0]);
  assert.deepEqual(await messages(`uiParameter(name: 'A', html: '<script>go()</script>')`),
    ["module is required unless runtime is 'schema-form'", 'html has a <script> without type="module"']);
  assert.deepEqual(await messages(`uiParameter(name: 'B', module: 'export default () => {}', html: '<script type="module" src="/x.mjs"></script>')`),
    ['html has a <script src=...>']);
  assert.deepEqual(await messages(`uiParameter(name: 'C', html: '<script type=module>export default () => {}</script><script type=module>x()</script>')`),
    ['html has more than one <script type="module">']);
  assert.deepEqual(await messages(`uiParameter(name: 'D', html: '<button onclick="go()">Go</button><script type=module>export default () => {}</script>')`),
    ['html uses the inline handler onclick=']);
  assert.deepEqual(await messages(`uiParameter(name: 'E', module: 'export default () => {}', html: '<script type="module">export default () => {}</script>')`),
    ['html has a <script type="module"> and the form has module too']);
});

test('splitHtml matches the controller: styles join in order, comments keep their scripts', () => {
  const parts = splitHtml('<!-- <script>old()</script> --><STYLE>a{}</STYLE><p>x</p><style>b{}</style><SCRIPT type="Module">m()</SCRIPT>', 'html');
  assert.deepEqual({ ...parts, moduleStart: undefined }, {
    module: 'm()', moduleStart: undefined, html: '<!-- <script>old()</script> --><p>x</p>', css: 'a{}\nb{}', errors: [],
  });
});

// The same inputs as SingleFileHtmlTest: an onerror the browser runs must be found however the markup around it is written.
const RUNS = '<img src=x onerror=alert(1)>';
const handlerRefused = (html) => assert.deepEqual(splitHtml(html, 'html').errors.map((e) => e.split(';')[0]),
  ['html uses the inline handler onerror='], html);

test('splitHtml ends a comment where the browser ends it, so no handler hides behind it', () => {
  handlerRefused(`<!-->${RUNS}-->`);
  handlerRefused(`<!--->${RUNS}-->`);
  handlerRefused(`<!-- old --!>${RUNS}-->`);
  assert.deepEqual(splitHtml(`<!-- ${RUNS} --><!----><p>x</p>`, 'html').errors, []);
});

test('splitHtml separates and quotes attributes as the browser reads them', () => {
  handlerRefused('<img/onerror=alert(1) src=x>');
  handlerRefused('<img src="x"onerror=alert(1)>');
  handlerRefused('<img alt=">" src=x onerror=alert(1)>');
  handlerRefused(`<p title='>'></p>${RUNS}`);
  handlerRefused(`<3 ${RUNS}`);
  handlerRefused(`<a\u000btitle="x>${RUNS}">`);
  assert.deepEqual(splitHtml(`<p title="onclick=go()" data-x='a onclick=b'>x</p>`, 'html').errors, []);
});

test('splitHtml refuses markup that hides the end of a text element or joins text into a tag', () => {
  const first = (html) => splitHtml(html, 'html').errors[0] ?? '';
  assert.match(first(`<textarea><p title="</textarea>${RUNS}"></textarea>`), /^html has <\/textarea inside a tag or comment/);
  assert.match(first(`<noscript><!-- </noscript>${RUNS} --></noscript>`), /^html has <\/noscript inside a tag or comment/);
  assert.match(first(`<textarea><style></textarea></style><p title="</textarea>${RUNS}">`), /^html has <\/textarea inside/);
  handlerRefused(`<svg><title>${RUNS}</title></svg>`);
  assert.deepEqual(splitHtml('<svg><title>Icon</title></svg><textarea>a < b</textarea>', 'html').errors, []);
  assert.match(first('<<script type=module>export default () => {}</script>img src=x onerror=alert(1)>'), /^html has a '<' right before <script>/);
});

test('splitHtml stays linear on text made to make a scanner backtrack or rescan', () => {
  // The same inputs as SingleFileHtmlTest. Reading the growing html string once per element made 40 000 styles take 3.5 s.
  const start = performance.now();
  for (const html of ['<a  '.repeat(130_000), '<style>p{}</style><b x=1>'.repeat(40_000), '<textarea></textareax>'.repeat(20_000)]) {
    assert.deepEqual(splitHtml(html, 'html').errors, []);
  }
  const millis = performance.now() - start;
  assert.ok(millis < 2000, `${Math.round(millis)} ms`);
});

test('a panel html is checked for handlers with the same reading', async () => {
  const p = errors(await checkSource(`uiPanel(title: 'P', module: 'export default () => {}', html: '<!--><img/onerror=alert(1) src=x>-->')`));
  assert.equal(p.length, 1, JSON.stringify(p));
  assert.match(p[0].message, /html uses the inline handler onerror=;/);
});

test('formFrom paths are checked like the controller checks them, and inline fields next to it are errors', async () => {
  const p = errors(await checkSource(`
    uiParameter(name: 'A', formFrom: '../secrets')
    uiParameter(name: 'B', formFrom: '/etc/forms')
    uiParameter(name: 'C', formFrom: 'forms/c', schema: '{"type":"object"}', module: 'export default () => {}')
    uiParameter(name: 'D', formFrom: 'forms/d')`));
  assert.deepEqual(p.map((x) => x.message.split(';')[0]), [
    "uiParameter 'A': formFrom '../secrets' must not contain '..'",
    "uiParameter 'B': formFrom '/etc/forms' must be a folder relative to the repository root: letters, digits, '.', '_' and '-' separated by '/', with no leading or trailing '/' and no '\\'",
    "uiParameter 'C': formFrom reads module, html, css, schema and defaultJson from 'forms/c', so the Jenkinsfile must not set them too",
  ]);
  assert.match(p[2].message, /remove module, schema$/);
});

test('a formFrom folder on disk is checked like inline fields; a missing one is a warning', async () => {
  const root = mkdtempSync(path.join(tmpdir(), 'build-ui-check-'));
  mkdirSync(path.join(root, 'forms/bad'), { recursive: true });
  writeFileSync(path.join(root, 'forms/bad/form.html'), '<p onclick="x()">hi</p>\n<script type="module">\nexport default function mount() {\n  nope(\n}\n</script>');
  writeFileSync(path.join(root, 'forms/bad/form.mjs'), 'export default () => {}');
  writeFileSync(path.join(root, 'forms/bad/schema.json'), '{"type":"object","properties":{"a":{"$ref":"#"}}}');
  writeFileSync(path.join(root, 'forms/bad/default.json'), '{"a":1,"a":2}');
  const p = await checkSource(`
    uiParameter(name: 'C', formFrom: 'forms/bad')
    uiParameter(name: 'M', formFrom: 'forms/missing')`, { root });
  assert.deepEqual(p.map((x) => [x.level, x.message.split(';')[0]]), [
    ['error', "uiParameter 'C': forms/bad/form.html uses the inline handler onclick="],
    ['error', "uiParameter 'C': forms/bad/form.html has a <script type=\"module\"> and the form has form.mjs too"],
    ['error', "uiParameter 'C': forms/bad/schema.json: schema at /properties/a: keyword '$ref' is not supported"],
    ['error', "uiParameter 'C': forms/bad/default.json: default.json has the key \"a\" twice in one object"],
    ['warning', `uiParameter 'M': formFrom folder 'forms/missing' is not under ${root}, so its files were not checked`],
  ]);

  // With only form.html, its module script is checked, at its line in that file.
  rmSync(path.join(root, 'forms/bad/form.mjs'));
  writeFileSync(path.join(root, 'forms/bad/form.html'), '<p>hi</p>\n<script type="module">\nexport default function mount() {\n  nope(\n}\n</script>');
  const q = errors(await checkSource(`uiParameter(name: 'C', formFrom: 'forms/bad')`, { root }))
    .filter((x) => x.message.includes('form.html'));
  assert.equal(q.length, 1, JSON.stringify(q));
  assert.match(q[0].message, /^uiParameter 'C': forms\/bad\/form\.html:5:\d+: module: /);
});

test('the CLI takes formFrom paths from the Git top level of the Jenkinsfile', () => {
  const repo = mkdtempSync(path.join(tmpdir(), 'build-ui-check-repo-'));
  assert.equal(spawnSync('git', ['-C', repo, 'init', '-q']).status, 0);
  mkdirSync(path.join(repo, 'ci'));
  mkdirSync(path.join(repo, 'forms/deploy'), { recursive: true });
  writeFileSync(path.join(repo, 'ci/Jenkinsfile'), "uiParameter(name: 'DEPLOY', formFrom: 'forms/deploy')\n");
  writeFileSync(path.join(repo, 'forms/deploy/form.html'), '<script>classic()</script>');
  const r = cli(['ci/Jenkinsfile'], repo);
  assert.equal(r.status, 1, r.stdout);
  assert.match(r.stdout, /ci\/Jenkinsfile:1:\d+: error: uiParameter 'DEPLOY': forms\/deploy\/form\.html has a <script> without type="module"/);
});
