import { chromium } from '@playwright/test';
import fs from 'node:fs';
const names = fs.readdirSync(new URL('./pages', import.meta.url)).filter(f => f.startsWith('ex-')).map(f => f.slice(0, -4));
const b = await chromium.launch();
const want = JSON.stringify({ branch: 'feature/x', autoDeploy: true, env: 'prod' });
const rows = [];
for (const n of names) for (const csp of ['strict', 'off']) {
  const p = await b.newPage(); const errs = [];
  p.on('pageerror', e => errs.push(String(e).slice(0, 110)));
  p.on('console', m => m.type() === 'error' && !m.text().includes('404') && errs.push(m.text().slice(0, 110)));
  let r = 'FAIL';
  try {
    await p.goto(`http://127.0.0.1:4321/page/${n}${csp === 'off' ? '?csp=off' : ''}`);
    await p.waitForSelector('#branch', { timeout: 3000 });
    const hiddenBefore = !(await p.locator('#env').isVisible().catch(() => false));
    await p.locator('#branch').fill('feature/x');
    await p.locator('#auto').check();
    await p.locator('#env').selectOption('prod', { timeout: 3000 });
    await p.waitForTimeout(150);
    const got = JSON.stringify(JSON.parse(await p.locator('#out').textContent()));
    const img = await p.evaluate(() => document.querySelector('.banner')?.naturalWidth || (document.querySelector('build-form-lit')?.shadowRoot?.querySelector('.banner')?.naturalWidth ?? 0));
    r = got === want && hiddenBefore && img === 48 ? 'PASS' : `FAIL got=${got} hiddenBefore=${hiddenBefore} img=${img}`;
  } catch (e) { r = 'FAIL ' + String(e).split('\n')[0].slice(0, 80); }
  rows.push(`${n.padEnd(18)} ${csp.padEnd(6)} ${r}${errs.length ? '  | ' + errs[0] : ''}`);
  await p.close();
}
console.log(rows.join('\n')); await b.close();
