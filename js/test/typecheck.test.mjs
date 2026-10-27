// The types are useful only while they match what the plugin hands a module:
// the runtime ids in contract.json, the exports of each runtime bundle, and
// the forms in examples/. A drift in either direction fails here.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { contract, findCalls, splitHtml, tokenize } from '../scripts/check.mjs';
import { typecheck } from '../scripts/typecheck.mjs';

const at = (p) => fileURLToPath(new URL(p, import.meta.url));
const types = at('../types/build-ui').replaceAll('\\', '/');
const messages = (problems) => problems.map((p) => `${p.line}: ${p.message.split('\n')[0]}`);

function checkText(files) {
  const dir = mkdtempSync(path.join(tmpdir(), 'build-ui-types-'));
  try {
    const paths = Object.entries(files).map(([name, text]) => {
      writeFileSync(path.join(dir, name), text);
      return path.join(dir, name);
    });
    return typecheck(paths).map((p) => ({ ...p, file: p.file && path.basename(p.file) }));
  } finally {
    rmSync(dir, { recursive: true });
  }
}

test('a module typed with build-ui.d.ts checks clean, and the usual mistakes are caught with their line', () => {
  assert.deepEqual(typecheck([at('fixtures/typed-form.mjs')]), []);
  assert.deepEqual(messages(typecheck([at('fixtures/typed-form-mistakes.mjs')])), [
    "5: Property 'useStat' does not exist on type 'Preact11Htm3'.",
    "6: Type 'number' is not assignable to type 'string'.",
    '7: No overload matches this call.',
    "8: Property 'jobName' does not exist on type 'Ui<{ branch: string; }>'.",
  ]);
});

test('the types know every runtime id in contract.json, and no other', () => {
  const ids = Object.fromEntries(contract.runtimes.map((id) => [id, true]));
  // An object literal assigned to a Record must have every key and no extra one.
  const problems = checkText({ 'ids.mjs': `// @ts-check
/** @type {Record<import('${types}').RuntimeId, true>} */
export const ids = ${JSON.stringify(ids)}
` });
  assert.deepEqual(problems, []);
});

test('Preact11Htm3 lists exactly what the preact11-htm3 runtime exports', async () => {
  const exported = Object.keys(await import('../runtimes/preact11-htm3.mjs')).sort();
  const problems = checkText({ 'exports.mjs': `// @ts-check
/** @type {Record<keyof import('${types}').Preact11Htm3, true>} */
export const all = ${JSON.stringify(Object.fromEntries(exported.map((name) => [name, true])))}
` });
  assert.deepEqual(problems, [], `runtime exports: ${exported.join(', ')}`);
});

test('the forms in examples/ check clean once their mount is annotated', () => {
  const modules = {};
  for (const name of ['reference', 'parallel-envs', 'release']) {
    const src = readFileSync(at(`../../examples/${name}.Jenkinsfile`), 'utf8');
    for (const call of findCalls(tokenize(src), ['uiParameter'])) {
      modules[`${name}.mjs`] = { runtime: call.args.runtime?.value ?? 'preact11-htm3', text: call.args.module.value };
    }
  }
  const formHtml = readFileSync(at('../../examples/form-from/forms/deploy/form.html'), 'utf8');
  modules['form-from.mjs'] = { runtime: 'preact11-htm3', text: splitHtml(formHtml, 'form.html').module };
  const files = Object.fromEntries(Object.entries(modules).map(([file, { runtime, text }]) => {
    assert.match(text, /export default function mount/, file);
    const typed = text.replace('export default function mount', `/** @type {import('${types}').MountFor<'${runtime}'>} */\nexport default function mount`);
    return [file, `// @ts-check\n${typed}`];
  }));
  assert.deepEqual(checkText(files).map((p) => `${p.file}:${p.line}: ${p.message}`), []);
});
