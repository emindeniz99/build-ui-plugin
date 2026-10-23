import { chromium } from '@playwright/test';
import fs from 'node:fs'; import zlib from 'node:zlib';
const root = new URL('..', import.meta.url).pathname;
const b = await chromium.launch();
for (const n of ['ex-preact-htm', 'ex-preact-signals', 'ex-solid2', 'ex-lit-html', 'ex-vanilla']) {
  const p = await b.newPage(); const files = new Set();
  p.on('response', r => { const u = new URL(r.url()); if (u.pathname.startsWith('/node_modules/')) files.add(u.pathname); });
  await p.goto('http://127.0.0.1:4321/page/' + n); await p.waitForTimeout(800);
  let raw = 0, gz = 0;
  for (const f of files) { const d = fs.readFileSync(root + f); raw += d.length; gz += zlib.gzipSync(d, { level: 9 }).length; }
  console.log(n.padEnd(18), 'files', String(files.size).padStart(2), 'raw', (raw / 1024).toFixed(1) + 'KB', 'gzip', (gz / 1024).toFixed(1) + 'KB');
  await p.close();
}
await b.close();
