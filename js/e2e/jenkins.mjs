// Small Jenkins REST helpers for the end-to-end tests.
import { expect } from '@playwright/test';

const escapeXml = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

async function crumb(request) {
  const res = await request.get('crumbIssuer/api/json');
  if (!res.ok()) return {};
  const { crumbRequestField, crumb: value } = await res.json();
  return { [crumbRequestField]: value };
}

/** Creates (or replaces) a Pipeline job running `script` in the Groovy sandbox. */
export async function pipelineJob(request, name, script) {
  const xml = `<flow-definition plugin="workflow-job"><definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps"><script>${escapeXml(script)}</script><sandbox>true</sandbox></definition></flow-definition>`;
  const headers = { 'Content-Type': 'application/xml', ...(await crumb(request)) };
  await request.post(`job/${name}/doDelete`, { headers, maxRedirects: 0 });
  const res = await request.post(`createItem?name=${encodeURIComponent(name)}`, { headers, data: xml });
  expect(res.ok(), `createItem ${name}: ${res.status()} ${await res.text()}`).toBeTruthy();
}

/** Starts a build and waits for it to finish (or only to start); returns its number. */
export async function build(request, name, { finished = true } = {}) {
  const before = await lastNumber(request, name);
  const res = await request.post(`job/${name}/build?delay=0sec`, { headers: await crumb(request), maxRedirects: 0 });
  expect(res.status(), await res.text()).toBeLessThan(400);
  return waitForBuild(request, name, before + 1, { finished });
}

export async function lastNumber(request, name) {
  const res = await request.get(`job/${name}/api/json?tree=lastBuild[number]`);
  return (await res.json()).lastBuild?.number ?? 0;
}

export async function waitForBuild(request, name, number, { finished = true } = {}) {
  await expect.poll(async () => {
    const res = await request.get(`job/${name}/${number}/api/json?tree=building`);
    if (!res.ok()) return 'missing';
    return (await res.json()).building && finished ? 'building' : 'ready';
  }, { timeout: 90_000, intervals: [500] }).toBe('ready');
  return number;
}

export async function consoleText(request, name, number) {
  return (await request.get(`job/${name}/${number}/consoleText`)).text();
}

/** Records CSP violations and console errors on a page. */
export async function watch(page) {
  const problems = [];
  // Jenkins answers the build form page itself with 405 on purpose ("not a build trigger").
  const expected = /status of 405 \(Method Not Allowed\)/;
  page.on('console', (m) => { if (m.type() === 'error' && !expected.test(m.text())) problems.push(m.text()); });
  page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (e) => console.error(`CSP ${e.violatedDirective} ${e.blockedURI}`));
  });
  return problems;
}
