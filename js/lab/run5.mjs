import { chromium } from '@playwright/test';
const b = await chromium.launch();
for (const mode of ['enforce', 'off']) {
  const p = await b.newPage(); const errs = [];
  p.on('console', m => m.type() === 'error' && errs.push(m.text().slice(0, 140)));
  await p.goto('http://127.0.0.1:4321/page/probe-style' + (mode === 'enforce' ? '' : '?csp=off'));
  await p.waitForFunction(() => document.getElementById('out').textContent.length > 2, null, { timeout: 5000 }).catch(() => {});
  console.log(mode, await p.locator('#out').textContent());
  if (errs.length) console.log('  console errors:\n  ' + errs.join('\n  '));
  await p.close();
}
await b.close();
