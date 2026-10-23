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

test('a panel gets the same ui.job as a form, and a failing panel does not ask for edits', async ({ page, request }) => {
  // Panels call providers with { job: ui.job } as forms do; an empty name gets a 400.
  await pipelineJob(request, 'panel-job', `
    uiPanel title: 'Job', data: '{}', module: 'export default function mount({ root, ui }) { root.textContent = "job=" + ui.job }'
    uiPanel title: 'Broken', data: '{"a":1}', module: 'export default function mount() { throw new Error("boom") }'`);
  const n = await build(request, 'panel-job');
  await page.goto(`job/panel-job/${n}/`);
  await expect(page.locator('.build-ui-container')).toHaveText('job=panel-job');
  // The panel's textarea is read-only and submits nothing, so the message must not say "edit".
  await expect(page.getByRole('alert')).toContainText('This panel could not load; its data is shown below.');
  await expect(page.getByRole('alert')).toContainText('boom');
});

/** A job with one form whose module (runtime none) is `module`. */
async function moduleJob(request, name, module) {
  await pipelineJob(request, name, `
    pipeline { agent none
      parameters { uiParameter(name: 'CFG', runtime: 'none', module: '''${module}''') }
      stages { stage('s') { steps { echo 'ok' } } } }`);
  await build(request, name);
}

test('the host refuses a runtime ID that is a path, so data-runtime cannot load another script', async ({ page, request }) => {
  await moduleJob(request, 'runtime-id', 'export default function mount({ root }) { root.textContent = "ok" }');
  const fetched = [];
  await page.route('**/evil.js', (route) => {
    fetched.push(route.request().url());
    return route.fulfill({ contentType: 'text/javascript', body: 'window.__evil = true' });
  });
  await page.goto('job/runtime-id/build?delay=0sec');
  await expect(page.locator('.build-ui-root[data-mounted="true"]')).toBeVisible();
  // What a hand-written config.xml can put in data-runtime: dot segments that leave runtimes/
  // and reach a same-origin file a build wrote, such as a workspace or artifact file.
  await page.evaluate(() => {
    const box = document.createElement('div');
    box.innerHTML = '<div class="build-ui-root" data-name="X" data-runtime="../../../../userContent/evil"></div>'
      + '<textarea class="build-ui-value">{}</textarea>';
    document.querySelector('.build-ui').after(box);
  });
  await expect(page.getByRole('alert')).toContainText('unknown runtime');
  expect(fetched).toEqual([]);
  expect(await page.evaluate(() => window.__evil)).toBeUndefined();
});

test('ui.query reports the HTTP status when Jenkins answers with an HTML error page', async ({ page, request }) => {
  await moduleJob(request, 'query-error', `
    export default async function mount({ root, ui }) {
      try { await ui.query('jenkinsRuns', { job: ui.job }) } catch (e) { root.textContent = e.message }
    }`);
  // Jenkins answers a missing permission, a stale session or a provider crash with HTML.
  await page.route('**/build-ui/query/**', (route) =>
    route.fulfill({ status: 403, contentType: 'text/html', body: '<html><body>Access denied</body></html>' }));
  await page.goto('job/query-error/build?delay=0sec');
  await expect(page.locator('.build-ui-container')).toHaveText('query jenkinsRuns failed: HTTP 403');
});

test('a form removed while its module is still loading is never mounted', async ({ page, request }) => {
  // This module stops its interval only through the signal, as the contract allows. If the
  // host mounted it with a signal that had already fired, the interval would never stop.
  await moduleJob(request, 'gone-early', `
    export default function mount({ signal }) {
      window.__mounted = true
      const id = setInterval(() => { window.__ticks = (window.__ticks || 0) + 1 }, 50)
      signal.addEventListener('abort', () => clearInterval(id))
    }`);
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  await page.route('**/build-ui/*.mjs', async (route) => { await gate; await route.continue(); });
  const requested = page.waitForRequest('**/build-ui/*.mjs');
  await page.goto('job/gone-early/build?delay=0sec', { waitUntil: 'domcontentloaded' });
  await requested;
  await page.locator('.build-ui-root').evaluate((el) => el.closest('[name=parameter]').remove());
  const loaded = page.waitForResponse('**/build-ui/*.mjs');
  release();
  await loaded;
  await page.waitForTimeout(500);
  expect(await page.evaluate(() => [window.__mounted, window.__ticks])).toEqual([undefined, undefined]);
});

test('a page kept for the back button keeps its forms; a page that is left unmounts them', async ({ page, request }) => {
  await moduleJob(request, 'bfcache', `
    export default function mount({ root }) {
      root.textContent = 'mounted'
      return () => { window.__cleaned = (window.__cleaned || 0) + 1 }
    }`);
  await page.goto('job/bfcache/build?delay=0sec');
  await expect(page.locator('.build-ui-root[data-mounted="true"]')).toBeVisible();
  // persisted: true means the browser keeps the page and shows it again on Back, as it was.
  await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true })));
  expect(await page.evaluate(() => window.__cleaned)).toBeUndefined();
  await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: false })));
  expect(await page.evaluate(() => window.__cleaned)).toBe(1);
});

test('keys typed in a field stay out of Jenkins shortcuts, but Escape still reaches the module', async ({ page, request }) => {
  // Closing a dropdown on Escape with a document listener is the usual pattern.
  await moduleJob(request, 'keys', `
    export default function mount({ root, signal }) {
      root.innerHTML = '<label>Search <input></label>'
      document.addEventListener('keydown', (e) => { if (e.key === 'Escape') window.__escape = true }, { signal })
    }`);
  await page.goto('job/keys/build?delay=0sec');
  const field = page.getByLabel('Search');
  await field.pressSequentially('feature/x');
  await expect(field).toHaveValue('feature/x');
  await expect(field).toBeFocused();
  await field.press('Escape');
  expect(await page.evaluate(() => window.__escape)).toBe(true);
});
