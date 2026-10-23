// Preact vs Solid 2 on one complex form (design.md section 7). Start the lab
// server first (pnpm --dir js lab), ideally with LAB_CSP set to Jenkins's header.
import { chromium } from '@playwright/test';
import { build } from 'esbuild';
import { readFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';

const pages = fileURLToPath(new URL('./pages/', import.meta.url));
const expected = { branch: 'feature/x', services: ['api', 'worker'], deploy: true,
  target: { region: 'us', replicas: 3 }, steps: [{ id: 2, name: 'step 2' }] };

async function size(name) {
  const out = await build({ entryPoints: [`${pages}${name}.mjs`], bundle: true, minify: true, format: 'esm',
    write: false, logLevel: 'silent', conditions: ['browser'], absWorkingDir: fileURLToPath(new URL('..', import.meta.url)) });
  return (gzipSync(out.outputFiles[0].contents, { level: 9 }).length / 1024).toFixed(1) + ' KB';
}

function lines(name) {
  return readFileSync(`${pages}${name}.mjs`, 'utf8').split('\n').filter((l) => l.trim() && !l.trim().startsWith('//')).length;
}

const MODES = {
  'plain element, renders at once': '',
  'plain element, waits for definition': '?wait=1',
  'upgrade-safe element, renders at once': '?safe=1',
};

async function run(browser, name, mode) {
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  await page.goto(`http://127.0.0.1:4321/page/${name}${MODES[mode]}`);
  const r = { name, mode };
  try {
    await page.waitForSelector('#services button[data-option="worker"]', { timeout: 3000 });
    r.optionsShown = true;
  } catch { r.optionsShown = false; }
  r.initialSelectionShown = await page.locator('#services button[data-option="api"][aria-pressed="true"]').count() === 1;
  try {
    await page.locator('#services button[data-option="worker"]').click({ timeout: 1000 });
    r.customEvent = await page.locator('#services button[data-option="worker"][aria-pressed="true"]').count() === 1;
  } catch { r.customEvent = false; }
  await page.locator('#branch').fill('feature/x');
  r.conditionalHidden = await page.locator('#region').count() === 0;
  await page.locator('#deploy').check();
  await page.locator('#region').selectOption('us');
  await page.locator('#replicas').fill('3');
  await page.locator('#add-step').click();
  await page.locator('#add-step').click();
  await page.locator('li .remove').first().click();
  await page.waitForTimeout(100);
  const value = JSON.parse(await page.locator('#out').textContent());
  r.valueCorrect = JSON.stringify(value) === JSON.stringify(expected);
  if (!r.valueCorrect) r.value = value;
  r.firstPaintMs = Math.round(await page.evaluate(() => window.__firstPaint));
  await page.evaluate(() => window.__unmount());
  // Preact 11 runs effect cleanups shortly after render(null) returns, not inside it.
  await page.waitForTimeout(100);
  const ticks = await page.evaluate(() => window.__ticks);
  await page.waitForTimeout(200);
  r.timerStopped = (await page.evaluate(() => window.__ticks)) === ticks;
  r.domEmptied = (await page.locator('#app').innerHTML()).trim() === '';
  r.cleanup = r.timerStopped && r.domEmptied;
  if (!r.cleanup) r.cleanupDetail = { timerStopped: r.timerStopped, domEmptied: r.domEmptied, ticksAtUnmount: ticks };
  r.errors = errors;
  await page.close();
  return r;
}

const browser = await chromium.launch();
const results = [];
for (const name of ['cmp-preact', 'cmp-solid']) {
  for (const mode of Object.keys(MODES)) results.push(await run(browser, name, mode));
}
await browser.close();
for (const name of ['cmp-preact', 'cmp-solid']) console.log(name, 'bundle', await size(name), 'app lines', lines(name));
const checks = ['optionsShown', 'initialSelectionShown', 'customEvent', 'conditionalHidden', 'valueCorrect', 'cleanup'];
for (const r of results) {
  const failed = checks.filter((c) => !r[c]);
  console.log(`${r.name.padEnd(11)} ${r.mode.padEnd(38)} ${failed.length ? 'FAIL ' + failed.join(',') : 'PASS'} first paint ${r.firstPaintMs} ms${r.cleanupDetail ? ' ' + JSON.stringify(r.cleanupDetail) : ''}${r.errors.length ? ' errors: ' + r.errors.join(' | ') : ''}`);
}
