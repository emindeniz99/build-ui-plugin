// The two motivating forms from the RFC, in their generic versions under
// examples/, driven in a real browser against a real Jenkins.
import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  pipelineJob, scmPipelineJob, build, buildWithParameters, waitForBuild, lastNumber, consoleText, watch,
} from './jenkins.mjs';

const example = (name) => readFileSync(new URL(`../../examples/${name}`, import.meta.url), 'utf8');

async function submit(page, request, job) {
  const next = (await lastNumber(request, job)) + 1;
  await page.getByRole('button', { name: 'Build', exact: true }).click();
  await waitForBuild(request, job, next);
  return consoleText(request, job, next);
}

test('parallel-envs: cards show each environment\'s last build from structured history', async ({ page, request }) => {
  await pipelineJob(request, 'parallel-envs', example('parallel-envs.Jenkinsfile'));
  // #1 registers the parameter and runs without it, as every declarative pipeline's first build does.
  await build(request, 'parallel-envs');
  // #2 runs with the default value. A parameterized job answers a bare POST build with 400.
  expect((await buildWithParameters(request, 'parallel-envs', {})).status()).toBe(201);
  await waitForBuild(request, 'parallel-envs', 2);
  const res = await buildWithParameters(request, 'parallel-envs', {
    DEPLOY: JSON.stringify({ branch: 'feature/a', envs: ['env-2', 'env-3'], services: ['api'], autoDeploy: true }),
  });
  expect(res.status()).toBe(201);
  await waitForBuild(request, 'parallel-envs', 3);

  const problems = await watch(page);
  await page.goto('job/parallel-envs/build?delay=0sec');
  await expect(page.locator('[data-env="env-2"] .last')).toHaveText('#3 SUCCESS · feature/a');
  await expect(page.locator('[data-env="env-1"] .last')).toHaveText('#2 SUCCESS · main');
  await expect(page.locator('[data-env="env-6"] .last')).toHaveText('no build yet');

  await page.locator('input[name="search"]').fill('load');
  await expect(page.locator('[data-env]')).toHaveCount(1);
  await page.locator('button[name="select-all"]').click();
  await page.locator('input[name="search"]').fill('');
  await page.locator('input[name="svc-worker"]').check();
  await expect(page.locator('.summary')).toHaveText('2 service(s) from main to 2 environment(s)');

  const log = await submit(page, request, 'parallel-envs');
  expect(log).toContain('DEPLOY={"branch":"main","envs":["env-1","stress-test"],"services":["api","worker"],"autoDeploy":true}');
  expect(log).toContain('Building [api, worker] from main for [env-1, stress-test]');
  expect(problems, problems.join('\n')).toEqual([]);
});

test('parallel-envs: the controller rejects an empty environment list', async ({ request }) => {
  await pipelineJob(request, 'parallel-envs-strict', example('parallel-envs.Jenkinsfile'));
  await build(request, 'parallel-envs-strict');
  const res = await buildWithParameters(request, 'parallel-envs-strict', {
    DEPLOY: JSON.stringify({ branch: 'main', envs: [], services: ['api'], autoDeploy: false }),
  });
  expect(res.status()).toBe(400);
  expect(await res.text()).toContain('/envs: must have at least 1 items');
});

test('release: refs come from the job\'s repository, newest version first, and x-git-ref guards the value', async ({ page, request }) => {
  const repo = mkdtempSync(path.join(tmpdir(), 'build-ui-release-'));
  const git = (...args) => execFileSync('git', ['-C', repo, ...args], { stdio: 'pipe' });
  git('init', '-q', '-b', 'main');
  writeFileSync(path.join(repo, 'Jenkinsfile'), example('release.Jenkinsfile'));
  git('add', 'Jenkinsfile');
  git('-c', 'user.name=t', '-c', 'user.email=t@example.invalid', 'commit', '-q', '-m', 'release pipeline');
  git('branch', 'release/1.x');
  for (const tag of ['v1.0.0', 'v1.2.0', 'v1.10.0', 'nightly']) git('tag', tag);

  await scmPipelineJob(request, 'release', pathToFileURL(repo).href);
  await build(request, 'release'); // registers the parameter and records the checkout
  const problems = await watch(page);
  await page.goto('job/release/build?delay=0sec');

  const tags = page.locator('select[name="baseTag"] option');
  await expect(tags).toHaveText(['v1.10.0', 'v1.2.0', 'v1.0.0']);
  // version-desc sorts branches too; the form still preselects the default branch.
  await expect(page.locator('select[name="branch"] option')).toHaveText(['release/1.x', 'main']);
  await expect(page.locator('select[name="branch"]')).toHaveValue('main');
  await page.locator('select[name="baseTag"]').selectOption('v1.10.0');
  await page.locator('input[name="bump"][value="minor"]').check();
  await expect(page.locator('[name="next-tag"]')).toHaveText('v1.11.0');
  await page.locator('input[name="override"]').fill('1.0.nightly-rc');
  await expect(page.locator('[name="next-tag"]')).toHaveText('1.0.nightly-rc');
  await page.locator('input[name="override"]').fill('');
  await page.locator('select[name="branch"]').selectOption('release/1.x');

  const log = await submit(page, request, 'release');
  expect(log).toContain('RELEASE={"branch":"release/1.x","baseTag":"v1.10.0","bump":"minor"}');
  expect(problems, problems.join('\n')).toEqual([]);

  const bad = await buildWithParameters(request, 'release', {
    RELEASE: JSON.stringify({ branch: 'main', baseTag: 'v9.9.9', bump: 'patch' }),
  });
  expect(bad.status()).toBe(400);
  expect(await bad.text()).toContain("/baseTag: 'v9.9.9' is not a tag of release's repository");
});

test('two forms on one page share one download of their runtime', async ({ page, request }) => {
  await pipelineJob(request, 'two-forms', `
    pipeline { agent none
      parameters {
        uiParameter(name: 'A', module: 'export default ({ root, runtime: { html, render } }) => render(html\`<p class="a">first</p>\`, root)')
        uiParameter(name: 'B', module: 'export default ({ root, runtime: { html, render } }) => render(html\`<p class="b">second</p>\`, root)')
      }
      stages { stage('s') { steps { echo "A=\${params.A} B=\${params.B}" } } } }`);
  await build(request, 'two-forms');
  const runtimeRequests = [];
  page.on('request', (r) => { if (r.url().includes('/runtimes/preact11-htm3.js')) runtimeRequests.push(r.url()); });
  await page.goto('job/two-forms/build?delay=0sec');
  await expect(page.locator('p.a')).toHaveText('first');
  await expect(page.locator('p.b')).toHaveText('second');
  expect(runtimeRequests).toHaveLength(1);
});
