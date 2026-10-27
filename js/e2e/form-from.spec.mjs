// formFrom: the form's files live in the repository and Jenkins reads them from
// the branch head when Build with Parameters opens, so a push shows up without
// a build. Also the single-file form: style, markup and module in one HTML text.
import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { cpSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { pipelineJob, scmPipelineJob, build, waitForBuild, lastNumber, consoleText, watch } from './jenkins.mjs';

async function submit(page, request, job) {
  const next = (await lastNumber(request, job)) + 1;
  await page.getByRole('button', { name: 'Build', exact: true }).click();
  await waitForBuild(request, job, next);
  return consoleText(request, job, next);
}

test('formFrom: a pushed form.html shows up on the next page load without a build', async ({ page, request }) => {
  const repo = mkdtempSync(path.join(tmpdir(), 'build-ui-form-from-'));
  cpSync(fileURLToPath(new URL('../../examples/form-from', import.meta.url)), repo, { recursive: true });
  const git = (...args) => execFileSync('git', ['-C', repo, '-c', 'user.name=t', '-c', 'user.email=t@example.invalid', ...args], { stdio: 'pipe' });
  git('init', '-q', '-b', 'main');
  git('add', '-A');
  git('commit', '-q', '-m', 'form v1');

  await scmPipelineJob(request, 'form-from', pathToFileURL(repo).href);
  await build(request, 'form-from'); // registers the parameter, as every declarative pipeline's first build does
  const problems = await watch(page);
  await page.goto('job/form-from/build?delay=0sec');
  await expect(page.locator('.title')).toHaveText('Deploy');
  await expect(page.locator('.summary')).toHaveText('Dry run for dev');
  // The <style> of the single file reached the form.
  await expect(page.locator('label.row').first()).toHaveCSS('display', 'flex');

  const form = path.join(repo, 'forms/deploy/form.html');
  writeFileSync(form, readFileSync(form, 'utf8').replace('<strong>Deploy</strong>', '<strong>Deploy, pushed</strong>'));
  git('commit', '-q', '-am', 'form v2');
  // The controller caches a read for 10 s; a reload after that shows the push.
  await expect.poll(async () => {
    await page.goto('job/form-from/build?delay=0sec');
    return page.locator('.title').textContent();
  }, { timeout: 40_000, intervals: [2_000] }).toBe('Deploy, pushed');
  expect(await lastNumber(request, 'form-from'), 'no build may have run').toBe(1);

  await page.locator('select[name="env"]').selectOption('staging');
  await page.locator('input[name="dryRun"]').uncheck();
  await expect(page.locator('.summary')).toHaveText('Deploy to staging');
  const log = await submit(page, request, 'form-from');
  expect(log).toContain('DEPLOY={"env":"staging","dryRun":false}');
  expect(problems, problems.join('\n')).toEqual([]);
});

test('single-file html: style, markup and module script in one field render under enforced CSP', async ({ page, request }) => {
  await pipelineJob(request, 'single-file', `
    properties([parameters([uiParameter(name: 'CFG', html: '''
<style>.hi { display: flex; }</style>
<p class="hi">waiting</p>
<script type="module">
export default function mount({ root, ui }) {
  const p = root.querySelector('.hi')
  p.textContent = 'mounted'
  ui.setValue({ said: 'hi' })
}
</script>''')])])
    echo "CFG=\${params.CFG}"`);
  await build(request, 'single-file');
  const problems = await watch(page);
  await page.goto('job/single-file/build?delay=0sec');
  await expect(page.locator('p.hi')).toHaveText('mounted');
  await expect(page.locator('p.hi')).toHaveCSS('display', 'flex');
  const log = await submit(page, request, 'single-file');
  expect(log).toContain('CFG={"said":"hi"}');
  // Enforced CSP blocks inline scripts; a violation here would mean the script ran inline.
  expect(problems, problems.join('\n')).toEqual([]);
});
