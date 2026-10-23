// The checker exists to catch, before Jenkins runs, the mistakes that fail
// silently at runtime (a dropped onclick, an import that needs an import map)
// or late (a schema keyword the controller rejects only when the job runs).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { checkSource } from '../scripts/check.mjs';

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');
const errors = (problems) => problems.filter((p) => p.level === 'error');

test('the reference Jenkinsfile and the README example are clean', async () => {
  assert.deepEqual(await checkSource(read('../../examples/reference.Jenkinsfile')), []);
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

test('bad JSON in defaultJson and uiPanel data is reported with the field name', async () => {
  const p = errors(await checkSource(`
    uiParameter(name: 'C', module: 'export default () => {}', defaultJson: '{branch: main}')
    uiPanel title: 'Summary', data: '{"a":', module: 'export default () => {}'`));
  assert.equal(p.length, 2);
  assert.match(p[0].message, /^uiParameter 'C': defaultJson is not valid JSON/);
  assert.match(p[1].message, /^uiPanel 'Summary': data is not valid JSON/);
});

test('unknown runtimes, missing modules and non-object schema-form schemas are errors', async () => {
  const p = errors(await checkSource(`
    uiParameter(name: 'A', runtime: 'vue3', module: 'export default () => {}')
    uiParameter(name: 'B')
    uiParameter(name: 'D', runtime: 'schema-form', schema: '{"type":"array"}')`));
  assert.deepEqual(p.map((x) => x.message.split(';')[0]), [
    "uiParameter 'A': unknown runtime 'vue3'",
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
