// Accessibility, keyboard use and theme colors of the forms, in a real Jenkins.
// axe checks only the form's own markup (.build-ui-root, shadow root included),
// not the Jenkins page around it.
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { readFileSync } from 'node:fs';
import { pipelineJob, build, lastNumber, waitForBuild, consoleText } from './jenkins.mjs';

const example = (name) => readFileSync(new URL(`../../examples/${name}`, import.meta.url), 'utf8');

async function axe(page) {
  const { violations } = await new AxeBuilder({ page }).include('.build-ui-root').analyze();
  return violations.map((v) => `${v.id}: ${v.help} (${v.nodes.map((n) => n.target.join(' ')).join(', ')})`);
}

test.describe('accessibility', () => {
  test('the reference form has no axe violations, conditional field shown', async ({ page, request }) => {
    await pipelineJob(request, 'a11y-reference', example('reference.Jenkinsfile'));
    await build(request, 'a11y-reference');
    await page.goto('job/a11y-reference/build?delay=0sec');
    await page.locator('input[name="deploy"]').check();
    await expect(page.locator('select[name="env"]')).toBeVisible();
    expect(await axe(page)).toEqual([]);
  });

  test('the parallel-envs form has no axe violations', async ({ page, request }) => {
    await pipelineJob(request, 'a11y-parallel', example('parallel-envs.Jenkinsfile'));
    await build(request, 'a11y-parallel');
    await page.goto('job/a11y-parallel/build?delay=0sec');
    await expect(page.locator('[data-env="env-1"]')).toBeVisible();
    expect(await axe(page)).toEqual([]);
  });

  test('the schema-form runtime produces an accessible form', async ({ page, request }) => {
    await pipelineJob(request, 'a11y-schema', `
      pipeline { agent none
        parameters { uiParameter(name: 'CFG', runtime: 'schema-form', defaultJson: '{"deploy":true}', schema: '''{
          "type": "object", "properties": {
            "branch": { "type": "string", "title": "Branch", "description": "Git branch to build" },
            "replicas": { "type": "integer", "title": "Replicas" },
            "deploy": { "type": "boolean", "title": "Deploy" },
            "env": { "enum": ["dev", "prod"], "title": "Environment", "x-show-if": { "deploy": true } },
            "services": { "type": "array", "title": "Services", "items": { "enum": ["api", "web"] } },
            "frontend": { "type": "object", "title": "Frontend", "properties": { "version": { "type": "string", "title": "Version" } } },
            "backend": { "type": "object", "title": "Backend", "properties": { "version": { "type": "string", "title": "Version" } } } } }''') }
        stages { stage('s') { steps { echo 'ok' } } } }`);
    await build(request, 'a11y-schema');
    await page.goto('job/a11y-schema/build?delay=0sec');
    await expect(page.getByLabel('Environment')).toBeVisible();
    // Two nested objects share the key "version": each label must name its own field.
    await expect(page.getByLabel('Version')).toHaveCount(2);
    expect(await axe(page)).toEqual([]);
  });

  test('the error fallback is announced and labelled', async ({ page, request }) => {
    await pipelineJob(request, 'a11y-broken', `
      pipeline { agent none
        parameters { uiParameter(name: 'CFG', module: 'export default () => { throw new Error("boom") }') }
        stages { stage('s') { steps { echo 'ok' } } } }`);
    await build(request, 'a11y-broken');
    await page.goto('job/a11y-broken/build?delay=0sec');
    await expect(page.getByRole('alert')).toContainText('boom');
    expect(await axe(page)).toEqual([]);
  });
});

test('the reference form can be filled and submitted with the keyboard only', async ({ page, request }) => {
  await pipelineJob(request, 'keyboard', example('reference.Jenkinsfile'));
  await build(request, 'keyboard');
  await page.goto('job/keyboard/build?delay=0sec');
  await page.locator('input[name="branch"]').focus();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type('kb/branch');
  await page.keyboard.press('Tab'); // api
  await page.keyboard.press('Tab'); // worker
  await page.keyboard.press('Space');
  await page.keyboard.press('Tab'); // web
  await page.keyboard.press('Tab'); // deploy
  await page.keyboard.press('Space');
  // A person sees the environment field appear before tabbing to it; so does the test.
  await expect(page.locator('select[name="env"]')).toBeVisible();
  await page.keyboard.press('Tab'); // environment select
  await expect(page.locator('select[name="env"]')).toBeFocused();
  await page.keyboard.press('ArrowDown'); // dev -> staging
  await expect(page.locator('select[name="env"]')).toHaveValue('staging');

  const next = (await lastNumber(request, 'keyboard')) + 1;
  await page.getByRole('button', { name: 'Build', exact: true }).focus();
  await page.keyboard.press('Enter');
  await waitForBuild(request, 'keyboard', next);
  expect(await consoleText(request, 'keyboard', next))
    .toContain('BUILD_CONFIG={"branch":"kb/branch","services":["api","worker"],"deploy":true,"env":"staging"}');
});

test('forms follow the Jenkins theme colors, so a dark theme stays readable', async ({ page, request }) => {
  await pipelineJob(request, 'theme', `
    pipeline { agent none
      parameters { uiParameter(name: 'CFG', runtime: 'schema-form', schema: '{"type":"object","properties":{"name":{"type":"string","title":"Name"}}}') }
      stages { stage('s') { steps { echo 'ok' } } } }`);
  await build(request, 'theme');
  await page.goto('job/theme/build?delay=0sec');
  await expect(page.getByLabel('Name')).toBeVisible();
  // What a dark theme does: redefine Jenkins's color variables on the page.
  await page.evaluate(() => {
    document.documentElement.style.setProperty('--text-color', 'rgb(230, 230, 230)');
    document.body.style.color = 'var(--text-color)';
  });
  const color = await page.locator('.build-ui-root').evaluate((el) => {
    const label = el.shadowRoot.querySelector('label');
    return getComputedStyle(label).color;
  });
  expect(color).toBe('rgb(230, 230, 230)');
});
