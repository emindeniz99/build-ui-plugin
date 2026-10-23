// Phase 0 acceptance, in a real browser against a real Jenkins with CSP enforced.
import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { pipelineJob, build, waitForBuild, lastNumber, consoleText, watch } from './jenkins.mjs';

const reference = readFileSync(new URL('../../examples/reference.Jenkinsfile', import.meta.url), 'utf8');

async function submitBuildForm(page, request, job) {
  const next = (await lastNumber(request, job)) + 1;
  await page.getByRole('button', { name: 'Build', exact: true }).click();
  await waitForBuild(request, job, next);
  return consoleText(request, job, next);
}

test('the README example works as written', async ({ page, request }) => {
  const readme = readFileSync(new URL('../../README.md', import.meta.url), 'utf8');
  const example = readme.match(/```groovy\n(pipeline \{[\s\S]*?)```/)[1];
  await pipelineJob(request, 'readme', example);
  await build(request, 'readme');
  await page.goto('job/readme/build?delay=0sec');
  await page.getByLabel('Branch').fill('docs');
  expect(await submitBuildForm(page, request, 'readme')).toContain('config: {"branch":"docs"}');
});

test('the reference form fits the ease test: about 60 Jenkinsfile lines', () => {
  expect(reference.trimEnd().split('\n').length).toBeLessThanOrEqual(60);
});

test('reference form renders under enforced CSP and its value reaches params', async ({ page, request }) => {
  await pipelineJob(request, 'reference', reference);
  await build(request, 'reference'); // the first run registers the parameter
  const problems = await watch(page);

  const res = await page.goto('job/reference/build?delay=0sec');
  const csp = res.headers()['content-security-policy'];
  expect(csp, 'Jenkins must enforce CSP on this page').toContain("script-src");
  expect(csp).not.toContain('unsafe-eval');

  await expect(page.locator('.build-ui-root[data-mounted="true"]')).toBeVisible();
  await expect(page.locator('textarea.build-ui-value')).toBeHidden();
  await expect(page.locator('.runs li').first()).toHaveText('#1 SUCCESS');

  await page.locator('input[name="branch"]').fill('release/1.2');
  await page.locator('input[name="worker"]').check();
  await expect(page.locator('select[name="env"]')).toHaveCount(0);
  await page.locator('input[name="deploy"]').check();
  await page.locator('select[name="env"]').selectOption('staging');

  const log = await submitBuildForm(page, request, 'reference');
  expect(log).toContain('BUILD_CONFIG={"branch":"release/1.2","services":["api","worker"],"deploy":true,"env":"staging"}');
  expect(problems, problems.join('\n')).toEqual([]);
});

test('a module that throws shows the error and the JSON textarea still builds', async ({ page, request }) => {
  await pipelineJob(request, 'broken', `
    pipeline { agent none
      parameters { uiParameter(name: 'CFG', schema: '{"type":"object","required":["x"]}', defaultJson: '{"x":1}',
        module: 'export default function mount() { throw new Error("boom") }') }
      stages { stage('s') { steps { echo "CFG=\${params.CFG}" } } } }`);
  await build(request, 'broken');
  await page.goto('job/broken/build?delay=0sec');

  await expect(page.getByRole('alert')).toContainText('boom');
  const textarea = page.locator('textarea.build-ui-value');
  await expect(textarea).toBeVisible();

  await textarea.fill('{}');
  await page.getByRole('button', { name: 'Build', exact: true }).click();
  await expect(page.locator('body')).toContainText("is missing required property 'x'");

  await page.goto('job/broken/build?delay=0sec');
  await page.locator('textarea.build-ui-value').fill('{"x": 42}');
  expect(await submitBuildForm(page, request, 'broken')).toContain('CFG={"x":42}');
});

test('a form removed while mount is still running gets its signal aborted and its cleanup called', async ({ page, request }) => {
  await pipelineJob(request, 'slow', `
    pipeline { agent none
      parameters { uiParameter(name: 'CFG', runtime: 'none', module: '''
        export default async function mount({ signal }) {
          signal.addEventListener('abort', () => { window.__aborted = true })
          await new Promise((resolve) => setTimeout(resolve, 1500))
          return () => { window.__cleaned = true }
        }''') }
      stages { stage('s') { steps { echo 'ok' } } } }`);
  await build(request, 'slow');
  await page.goto('job/slow/build?delay=0sec');
  await page.locator('.build-ui-root').evaluate((el) => el.closest('[name=parameter]').remove());
  await expect.poll(() => page.evaluate(() => window.__aborted === true)).toBe(true);
  await expect.poll(() => page.evaluate(() => window.__cleaned === true), { timeout: 5000 }).toBe(true);
});

test('the declarative schema-form shows a field only while its condition holds', async ({ page, request }) => {
  await pipelineJob(request, 'declarative', `
    pipeline { agent none
      parameters { uiParameter(name: 'CFG', runtime: 'schema-form', defaultJson: '{"deploy":false}', schema: '''{
        "type": "object", "properties": {
          "deploy": { "type": "boolean", "title": "Deploy" },
          "env": { "enum": ["dev", "prod"], "title": "Environment", "x-show-if": { "deploy": true } } } }''') }
      stages { stage('s') { steps { echo "CFG=\${params.CFG}" } } } }`);
  await build(request, 'declarative');
  const problems = await watch(page);
  await page.goto('job/declarative/build?delay=0sec');

  await expect(page.getByLabel('Environment')).toHaveCount(0);
  await page.getByLabel('Deploy').check();
  await page.getByLabel('Environment').selectOption('prod');
  expect(await submitBuildForm(page, request, 'declarative')).toContain('CFG={"deploy":true,"env":"prod"}');
  expect(problems, problems.join('\n')).toEqual([]);
});

test('schema-form submits what it shows: an untouched checkbox is false and a hidden default is dropped', async ({ page, request }) => {
  // No default for "deploy", so its box starts unchecked: the build must get false, the
  // field gated on false must show, and the default's "env" (gated on true) must not
  // reach the build although nobody touched the form.
  await pipelineJob(request, 'schema-shown', `
    pipeline { agent none
      parameters { uiParameter(name: 'CFG', runtime: 'schema-form', defaultJson: '{"env":"prod"}', schema: '''{
        "type": "object", "required": ["deploy"], "properties": {
          "deploy": { "type": "boolean", "title": "Deploy" },
          "reason": { "type": "string", "title": "Why not", "x-show-if": { "deploy": false } },
          "env": { "enum": ["dev", "prod"], "title": "Environment", "x-show-if": { "deploy": true } } } }''') }
      stages { stage('s') { steps { echo "CFG=\${params.CFG}" } } } }`);
  await build(request, 'schema-shown');
  await page.goto('job/schema-shown/build?delay=0sec');

  await expect(page.getByLabel('Why not')).toBeVisible();
  await expect(page.getByLabel('Environment')).toHaveCount(0);
  expect(await submitBuildForm(page, request, 'schema-shown')).toContain('CFG={"deploy":false}');
});

test('schema-form number fields keep what the user types, so 0.05 stays 0.05', async ({ page, request }) => {
  await pipelineJob(request, 'schema-number', `
    pipeline { agent none
      parameters { uiParameter(name: 'CFG', runtime: 'schema-form', schema: '''{
        "type": "object", "properties": { "ratio": { "type": "number", "title": "Ratio" } } }''') }
      stages { stage('s') { steps { echo "CFG=\${params.CFG}" } } } }`);
  await build(request, 'schema-number');
  await page.goto('job/schema-number/build?delay=0sec');

  // Key by key, as a person types: "0.0" on the way to "0.05" must not collapse to "0".
  await page.getByLabel('Ratio').pressSequentially('0.05');
  await expect(page.getByLabel('Ratio')).toHaveValue('0.05');
  expect(await submitBuildForm(page, request, 'schema-number')).toContain('CFG={"ratio":0.05}');
});

test('uiParameter works inside an input step', async ({ page, request }) => {
  await pipelineJob(request, 'input-step', `
    def cfg = input message: 'Configure the release', parameters: [uiParameter(name: 'CFG', runtime: 'schema-form',
      defaultJson: '{"approve":false}', schema: '{"type":"object","properties":{"approve":{"type":"boolean","title":"Approve"}}}')]
    echo "INPUT=\${cfg}"`);
  const n = await build(request, 'input-step', { finished: false });
  await expect.poll(async () => (await consoleText(request, 'input-step', n)).includes('Input requested'),
    { timeout: 60_000 }).toBe(true);

  await page.goto(`job/input-step/${n}/input/`);
  await page.getByLabel('Approve').check();
  await page.getByRole('button', { name: 'Proceed' }).click();
  await waitForBuild(request, 'input-step', n);
  expect(await consoleText(request, 'input-step', n)).toContain('INPUT={"approve":true}');
});

test('uiPanel draws a panel on the build page from the run data', async ({ page, request }) => {
  await pipelineJob(request, 'panel', `
    uiPanel title: 'Deploy summary', data: '{"env":"staging","services":["api","web"]}',
      module: 'export default function mount({ root, ui, runtime: { html, render } }) { render(html\`<p class="summary">\${ui.value.env}: \${ui.value.services.join(", ")}</p>\`, root) }'`);
  const n = await build(request, 'panel');
  const problems = await watch(page);
  await page.goto(`job/panel/${n}/`);
  await expect(page.locator('p.summary')).toHaveText('staging: api, web');
  expect(problems, problems.join('\n')).toEqual([]);
});

