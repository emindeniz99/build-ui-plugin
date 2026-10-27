// The preview in a real browser: the forms mount through the shipped host.js
// under Jenkins's enforced CSP, answer ui.query from the data file, and the
// value panel follows what the user does. Any CSP violation fails the test,
// because the same form would break on Jenkins.
import { test, expect } from '@playwright/test';
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { startPreview } from './server.mjs';

const examples = fileURLToPath(new URL('../../examples/', import.meta.url));

async function open(page, options) {
  const logged = [];
  const preview = await startPreview({ port: 0, log: { log() {}, error: (m) => logged.push(m) }, ...options });
  const problems = [];
  page.on('console', (m) => { if (m.type() === 'error') problems.push(m.text()); });
  page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (e) => console.error(`CSP ${e.violatedDirective} ${e.blockedURI}`));
  });
  await page.goto(preview.url);
  return { preview, problems, logged };
}

const value = async (page, name) => JSON.parse(await page.locator(`.preview-value[data-for="${name}"] .preview-json`).textContent());

test('reference.Jenkinsfile: the form mounts, reads fixtures, and the value and verdict follow the user', async ({ page }) => {
  const { preview, problems, logged } = await open(page, {
    target: path.join(examples, 'reference.Jenkinsfile'), data: path.join(examples, 'preview-data.json'),
  });
  try {
    const panel = page.locator('.preview-value[data-for="BUILD_CONFIG"]');
    await expect(page.locator('input[name="branch"]')).toHaveValue('main');
    await expect(page.locator('ul.runs li')).toHaveText(['#7 running', '#6 SUCCESS']);
    await expect(panel).toHaveAttribute('data-valid', 'true');

    await page.locator('input[name="branch"]').fill('feature/x');
    await expect.poll(() => value(page, 'BUILD_CONFIG')).toEqual({ branch: 'feature/x', services: ['api'], deploy: false });

    // No service fails the schema's minItems; the panel shows the controller's message.
    await page.locator('input[name="api"]').uncheck();
    await expect(panel).toHaveAttribute('data-valid', 'false');
    await expect(panel.locator('.preview-errors li')).toHaveText(['/services: must have at least 1 items']);

    await page.locator('input[name="worker"]').check();
    await page.locator('input[name="deploy"]').check();
    await page.locator('select[name="env"]').selectOption('prod');
    await expect.poll(() => value(page, 'BUILD_CONFIG')).toEqual({ branch: 'feature/x', services: ['worker'], deploy: true, env: 'prod' });
    await expect(panel).toHaveAttribute('data-valid', 'true');
    // The fallback textarea is what Jenkins submits; host.js hides it once the form mounts.
    await expect(page.locator('textarea.build-ui-value')).toBeHidden();
    await expect(page.locator('.preview-csp')).toBeHidden();
    expect(problems, problems.join('\n')).toEqual([]);
    expect(logged, logged.join('\n')).toEqual([]);
  } finally {
    await preview.close();
  }
});

test('a CSP violation shows on the page and in the terminal, so the clean runs above mean something', async ({ page }) => {
  const dir = mkdtempSync(path.join(tmpdir(), 'build-ui-preview-e2e-'));
  // Jenkins's policy has no 'unsafe-eval'; eval runtimes stop at exactly this.
  writeFileSync(path.join(dir, 'Jenkinsfile'), `uiParameter(name: 'EVAL', runtime: 'none', module: '''
export default function mount({ root, ui }) {
  try { new Function('return 1')(); root.textContent = 'eval ran' } catch (e) { root.textContent = 'eval blocked' }
  ui.setValue({ ok: true })
}''')
`);
  const { preview, logged } = await open(page, { target: path.join(dir, 'Jenkinsfile') });
  try {
    await expect(page.locator('.build-ui-container')).toHaveText('eval blocked');
    await expect(page.locator('.preview-csp li')).toHaveText([/^script-src blocked eval/]);
    await expect.poll(() => logged.join('\n')).toMatch(/CSP violation: script-src\S* blocked eval/);
  } finally {
    await preview.close();
    rmSync(dir, { recursive: true });
  }
});

test('formFrom folder: form.html renders split under CSP, and an edit reloads the page', async ({ page }) => {
  const dir = mkdtempSync(path.join(tmpdir(), 'build-ui-preview-e2e-'));
  cpSync(path.join(examples, 'form-from/forms/deploy'), dir, { recursive: true });
  const { preview, problems, logged } = await open(page, { target: dir, param: 'DEPLOY' });
  try {
    await expect(page.locator('.title')).toHaveText('Deploy');
    // The <style> of the single file reached the form's shadow root.
    await expect(page.locator('label.row').first()).toHaveCSS('display', 'flex');
    await page.locator('select[name="env"]').selectOption('staging');
    await page.locator('input[name="dryRun"]').uncheck();
    await expect(page.locator('.summary')).toHaveText('Deploy to staging');
    await expect.poll(() => value(page, 'DEPLOY')).toEqual({ env: 'staging', dryRun: false });
    await expect(page.locator('.preview-value[data-for="DEPLOY"]')).toHaveAttribute('data-valid', 'true');

    const form = path.join(dir, 'form.html');
    writeFileSync(form, readFileSync(form, 'utf8').replace('<strong>Deploy</strong>', '<strong>Deploy, edited</strong>'));
    await expect(page.locator('.title')).toHaveText('Deploy, edited');
    expect(problems, problems.join('\n')).toEqual([]);
    expect(logged, logged.join('\n')).toEqual([]);
  } finally {
    await preview.close();
    rmSync(dir, { recursive: true });
  }
});
