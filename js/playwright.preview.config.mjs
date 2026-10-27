// Browser tests of the local preview (pnpm preview). They start the preview
// themselves and need no Jenkins. BROWSERS picks the engines, as in
// playwright.config.mjs.
import { defineConfig } from '@playwright/test';

const browsers = (process.env.BROWSERS || 'chromium').split(',').map((b) => b.trim()).filter(Boolean);
// CHROMIUM_EXECUTABLE_PATH runs the chromium project on a Chromium this
// Playwright version did not download, for a machine that cannot run
// `playwright install`. CI leaves it unset and uses the matching build.
const chromiumPath = process.env.CHROMIUM_EXECUTABLE_PATH;
const launchOptions = (name) => (name === 'chromium' && chromiumPath ? { launchOptions: { executablePath: chromiumPath } } : {});

export default defineConfig({
  testDir: './preview',
  testMatch: '*.spec.mjs',
  timeout: 60_000,
  workers: 1,
  reporter: [['list']],
  projects: browsers.map((name) => ({ name, use: { browserName: name, ...launchOptions(name) } })),
});
