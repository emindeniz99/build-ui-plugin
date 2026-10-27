// Browser tests of the local preview (pnpm preview). They start the preview
// themselves and need no Jenkins. BROWSERS picks the engines, as in
// playwright.config.mjs.
import { defineConfig } from '@playwright/test';

const browsers = (process.env.BROWSERS || 'chromium').split(',').map((b) => b.trim()).filter(Boolean);

export default defineConfig({
  testDir: './preview',
  testMatch: '*.spec.mjs',
  timeout: 60_000,
  workers: 1,
  reporter: [['list']],
  projects: browsers.map((name) => ({ name, use: { browserName: name } })),
});
