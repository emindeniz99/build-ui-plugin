import { chromium } from '@playwright/test';
const b = await chromium.launch();
for (const mode of ['enforce', 'report', 'off']) {
  const p = await b.newPage(); await p.goto('http://127.0.0.1:4321/page/probe-eval' + (mode === 'enforce' ? '' : '?csp=' + mode)); await p.waitForTimeout(400);
  console.log(mode.padEnd(8), await p.locator('#out').textContent()); await p.close();
}
for (const ex of ['ex-vue', 'ex-alpine']) { const p = await b.newPage(); await p.goto(`http://127.0.0.1:4321/page/${ex}?csp=report`);
  await p.waitForTimeout(600); console.log(ex, 'report-only renders #branch:', await p.locator('#branch').count()); await p.close(); }
await b.close();
