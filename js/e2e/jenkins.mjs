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

/** Creates (or replaces) a Pipeline job that loads `Jenkinsfile` from the Git repository at `url`. */
export async function scmPipelineJob(request, name, url) {
  const xml = `<flow-definition plugin="workflow-job"><definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition" plugin="workflow-cps">
    <scm class="hudson.plugins.git.GitSCM" plugin="git"><configVersion>2</configVersion>
      <userRemoteConfigs><hudson.plugins.git.UserRemoteConfig><url>${escapeXml(url)}</url></hudson.plugins.git.UserRemoteConfig></userRemoteConfigs>
      <branches><hudson.plugins.git.BranchSpec><name>*/main</name></hudson.plugins.git.BranchSpec></branches>
      <extensions/></scm>
    <scriptPath>Jenkinsfile</scriptPath><lightweight>false</lightweight></definition></flow-definition>`;
  const headers = { 'Content-Type': 'application/xml', ...(await crumb(request)) };
  await request.post(`job/${name}/doDelete`, { headers, maxRedirects: 0 });
  const res = await request.post(`createItem?name=${encodeURIComponent(name)}`, { headers, data: xml });
  expect(res.ok(), `createItem ${name}: ${res.status()} ${await res.text()}`).toBeTruthy();
}

/** POSTs buildWithParameters with the values in the body; returns the response. */
export async function buildWithParameters(request, name, values) {
  return request.post(`job/${name}/buildWithParameters`, {
    headers: await crumb(request), form: values, maxRedirects: 0,
  });
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

/** Runs Groovy in the script console (hpi:run has security off) and returns what it printed. */
export async function groovy(request, script) {
  const res = await request.post('scriptText', { headers: await crumb(request), form: { script } });
  const text = await res.text();
  expect(res.ok(), `scriptText: ${res.status()} ${text}`).toBeTruthy();
  return text.trim();
}

/** Sets Build UI's allowEvalRuntimes setting; returns the previous value so a test can restore it. */
export async function allowEvalRuntimes(request, on) {
  return (await groovy(request, `
    def c = io.jenkins.plugins.buildui.BuildUiConfiguration.get()
    print c.allowEvalRuntimes
    c.allowEvalRuntimes = ${Boolean(on)}`)) === 'true';
}

// Jenkins reads this system property on every request, before development
// mode's enforced default, so a test can switch CSP modes without a restart.
const CSP_HEADER_PROPERTY = 'jenkins.security.csp.CspHeader.headerName';

/** Makes Jenkins send CSP as 'Content-Security-Policy-Report-Only', or restores the default with null. */
export async function cspHeader(request, name) {
  await groovy(request, name
    ? `System.setProperty('${CSP_HEADER_PROPERTY}', '${name}')`
    : `System.clearProperty('${CSP_HEADER_PROPERTY}')`);
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
