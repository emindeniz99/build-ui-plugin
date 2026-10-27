// The Alpine and Vue runtimes against a real Jenkins. alpine3-csp needs no eval
// and works under enforced CSP. alpine3 and vue3 need eval: an admin must allow
// them, and they work only while Jenkins sends CSP in report-only mode, so the
// tests switch both and put them back afterwards.
import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import {
  pipelineJob, build, waitForBuild, lastNumber, consoleText, watch, allowEvalRuntimes, cspHeader,
} from './jenkins.mjs';

const readme = readFileSync(new URL('../../README.md', import.meta.url), 'utf8');
/** The README's alpine3-csp example, wrapped in a pipeline that echoes the value. */
const alpineExample = `pipeline { agent none
  parameters { ${readme.match(/```groovy\n(uiParameter\((?:(?!```)[\s\S])*?alpine3-csp[\s\S]*?)```/)[1]} }
  stages { stage('s') { steps { echo "DEPLOY=\${params.DEPLOY}" } } } }`;

async function submit(page, request, job) {
  const next = (await lastNumber(request, job)) + 1;
  await page.getByRole('button', { name: 'Build', exact: true }).click();
  await waitForBuild(request, job, next);
  return consoleText(request, job, next);
}

/** Records the runtime bundles a page requests. */
function runtimeRequests(page) {
  const urls = [];
  page.on('request', (r) => { if (r.url().includes('/plugin/build-ui/runtimes/')) urls.push(r.url().replace(/.*\/runtimes\//, '')); });
  return urls;
}

let allowedBefore;
test.beforeAll(async ({ request }) => { allowedBefore = await allowEvalRuntimes(request, false); });
test.afterEach(async ({ request }) => {
  await allowEvalRuntimes(request, allowedBefore);
  await cspHeader(request, null);
});

test('alpine3-csp: the README form works under enforced CSP and its value reaches params', async ({ page, request }) => {
  await pipelineJob(request, 'alpine-csp', alpineExample);
  await build(request, 'alpine-csp');
  const problems = await watch(page);
  const res = await page.goto('job/alpine-csp/build?delay=0sec');
  expect(res.headers()['content-security-policy'], 'Jenkins must enforce CSP on this page').toContain('script-src');
  expect(res.headers()['content-security-policy']).not.toContain('unsafe-eval');

  await expect(page.locator('.build-ui-root[data-mounted="true"]')).toBeVisible();
  await expect(page.locator('textarea.build-ui-value')).toBeHidden();
  await expect(page.locator('select[name="env"]')).toBeHidden();
  await page.locator('input[name="branch"]').fill('release/2');
  await page.locator('input[name="deploy"]').check();
  await page.locator('select[name="env"]').selectOption('prod');

  expect(await submit(page, request, 'alpine-csp')).toContain('DEPLOY={"branch":"release/2","deploy":true,"env":"prod"}');
  expect(problems, problems.join('\n')).toEqual([]);
});

test('alpine3-csp: two forms on one page stay apart, and a removed form stops its own Alpine only', async ({ page, request }) => {
  const form = (name) => `uiParameter(name: '${name}', runtime: 'alpine3-csp', defaultJson: '{"branch":"main"}',
      html: '<div x-data="form${name}"><input name="branch-${name}" x-model="branch"><output x-text="branch"></output></div>',
      module: '''export default function mount({ root, ui, runtime: { Alpine, start } }) {
        Alpine.data('form${name}', () => ({
          ...ui.value,
          init() { this.$watch('branch', () => ui.setValue({ branch: this.branch })) },
          destroy() { (window.destroyed ||= []).push('${name}') },
        }))
        return start(root)
      }''')`;
  await pipelineJob(request, 'alpine-two', `pipeline { agent none
    parameters { ${form('A')}
      ${form('B')} }
    stages { stage('s') { steps { echo "A=\${params.A} B=\${params.B}" } } } }`);
  await build(request, 'alpine-two');
  const problems = await watch(page);
  await page.goto('job/alpine-two/build?delay=0sec');
  await expect(page.locator('.build-ui-root[data-mounted="true"]')).toHaveCount(2);

  await page.locator('input[name="branch-A"]').fill('only-a');
  await expect(page.locator('.build-ui-root[data-name="A"] output')).toHaveText('only-a');
  await expect(page.locator('.build-ui-root[data-name="B"] output')).toHaveText('main');

  // Alpine.start() would walk the whole Jenkins page, so the runtime refuses it.
  const startError = await page.evaluate(async (url) => {
    try { (await import(url)).Alpine.start(); return null; } catch (e) { return e.message; }
  }, `${new URL(page.url()).pathname.replace(/job\/.*/, '')}plugin/build-ui/runtimes/alpine3-csp.js`);
  expect(startError).toContain('would run Alpine on the whole Jenkins page');

  await page.locator('.build-ui-root[data-name="A"]').evaluate((el) => el.closest('[name=parameter]').remove());
  await expect.poll(() => page.evaluate(() => window.destroyed)).toEqual(['A']);
  await page.locator('input[name="branch-B"]').fill('still-b');
  expect(await submit(page, request, 'alpine-two')).toContain('A={"branch":"main"} B={"branch":"still-b"}');
  expect(problems, problems.join('\n')).toEqual([]);
});

test('an eval runtime is blocked while the admin setting is off: a notice, no runtime file, and the JSON still builds', async ({ page, request }) => {
  await pipelineJob(request, 'eval-off', `pipeline { agent none
    parameters { uiParameter(name: 'CFG', runtime: 'vue3', defaultJson: '{"branch":"main"}',
      html: '<input v-model="branch">', module: 'export default ({ root, runtime }) => runtime.createApp({}).mount(root)') }
    stages { stage('s') { steps { echo "CFG=\${params.CFG}" } } } }`);
  await build(request, 'eval-off');
  const requested = runtimeRequests(page);
  await page.goto('job/eval-off/build?delay=0sec');

  await expect(page.locator('.build-ui-pending')).toContainText('needs eval, which an administrator has not allowed');
  await expect(page.locator('.build-ui-root')).toHaveCount(0);
  await page.locator('textarea.build-ui-value').fill('{"branch":"typed"}');
  expect(await submit(page, request, 'eval-off')).toContain('CFG={"branch":"typed"}');
  expect(requested).toEqual([]);
});

test('an allowed eval runtime under enforced CSP shows a clear error, and the JSON field still builds', async ({ page, request }) => {
  await allowEvalRuntimes(request, true);
  await pipelineJob(request, 'eval-enforced', `pipeline { agent none
    parameters { uiParameter(name: 'CFG', runtime: 'vue3', defaultJson: '{"branch":"main"}',
      html: '<input v-model="branch">', module: 'export default ({ root, runtime }) => runtime.createApp({}).mount(root)') }
    stages { stage('s') { steps { echo "CFG=\${params.CFG}" } } } }`);
  await build(request, 'eval-enforced');
  const requested = runtimeRequests(page);
  const res = await page.goto('job/eval-enforced/build?delay=0sec');
  expect(res.headers()['content-security-policy']).toContain('script-src');

  await expect(page.getByRole('alert')).toContainText(
    "This form's runtime vue3 needs eval, which this Jenkins's Content Security Policy blocks.");
  await page.locator('textarea.build-ui-value').fill('{"branch":"fallback"}');
  expect(await submit(page, request, 'eval-enforced')).toContain('CFG={"branch":"fallback"}');
  expect(requested, 'the runtime file is useless under enforced CSP').toEqual([]);
});

test('with CSP in report-only mode, vue3 and alpine3 forms work', async ({ page, request }) => {
  await allowEvalRuntimes(request, true);
  await cspHeader(request, 'Content-Security-Policy-Report-Only');
  await pipelineJob(request, 'eval-report-only', `pipeline { agent none
    parameters {
      uiParameter(name: 'VUE', runtime: 'vue3', defaultJson: '{"branch":"main"}',
        html: '<label>Vue branch <input name="vue-branch" v-model="branch"></label><output>{{ branch.toUpperCase() }}</output>',
        module: '''export default function mount({ root, ui, runtime: { createApp } }) {
          const app = createApp({ data: () => ({ ...ui.value }), watch: { branch(branch) { ui.setValue({ branch }) } } })
          app.mount(root)
          return () => app.unmount()
        }''')
      uiParameter(name: 'ALP', runtime: 'alpine3', defaultJson: '{"branch":"main"}',
        html: '''<div x-data="{ branch: 'main' }" x-effect="$dispatch('value', { branch })"><label>Alpine branch <input name="alp-branch" x-model="branch"></label><output x-text="branch.toUpperCase()"></output></div>''',
        module: '''export default function mount({ root, ui, runtime: { start } }) {
          root.addEventListener('value', (e) => ui.setValue(e.detail))
          return start(root)
        }''') }
    stages { stage('s') { steps { echo "VUE=\${params.VUE} ALP=\${params.ALP}" } } } }`);
  await build(request, 'eval-report-only');
  const problems = await watch(page);
  const res = await page.goto('job/eval-report-only/build?delay=0sec');
  expect(res.headers()['content-security-policy']).toBeUndefined();
  expect(res.headers()['content-security-policy-report-only']).toContain('script-src');

  await expect(page.locator('.build-ui-root[data-mounted="true"]')).toHaveCount(2);
  await page.locator('input[name="vue-branch"]').fill('vue/x');
  await expect(page.locator('.build-ui-root[data-name="VUE"] output')).toHaveText('VUE/X');
  await page.locator('input[name="alp-branch"]').fill('alp/y');
  await expect(page.locator('.build-ui-root[data-name="ALP"] output')).toHaveText('ALP/Y');
  expect(await submit(page, request, 'eval-report-only')).toContain('VUE={"branch":"vue/x"} ALP={"branch":"alp/y"}');
  // Report-only mode still reports each eval; nothing else may go wrong.
  expect(problems.filter((p) => p !== 'CSP script-src eval'), problems.join('\n')).toEqual([]);
  expect(problems).toContain('CSP script-src eval');
});

test('vue3: a removed form unmounts its app', async ({ page, request }) => {
  await allowEvalRuntimes(request, true);
  await cspHeader(request, 'Content-Security-Policy-Report-Only');
  await pipelineJob(request, 'vue-unmount', `pipeline { agent none
    parameters { uiParameter(name: 'VUE', runtime: 'vue3', defaultJson: '{"n":0}', html: '<p>{{ n }}</p>',
      module: '''export default function mount({ root, ui, runtime: { createApp } }) {
        const app = createApp({ data: () => ({ ...ui.value }), unmounted() { window.unmounted = true } })
        app.mount(root)
        return () => app.unmount()
      }''') }
    stages { stage('s') { steps { echo 'ok' } } } }`);
  await build(request, 'vue-unmount');
  await page.goto('job/vue-unmount/build?delay=0sec');
  await expect(page.locator('.build-ui-root[data-mounted="true"] p')).toHaveText('0');
  await page.locator('.build-ui-root').evaluate((el) => {
    window.container = el.shadowRoot.querySelector('.build-ui-container');
    el.closest('[name=parameter]').remove();
  });
  await expect.poll(() => page.evaluate(() => window.unmounted)).toBe(true);
  expect(await page.evaluate(() => window.container.childElementCount)).toBe(0);
});
