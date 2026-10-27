// End-to-end tests against a running Jenkins with the plugin (mvn hpi:run).
// JENKINS_URL defaults to the hpi:run address. BROWSERS picks the engines,
// comma-separated: chromium (default), firefox, webkit.
import { defineConfig } from '@playwright/test';

const browsers = (process.env.BROWSERS || 'chromium').split(',').map((b) => b.trim()).filter(Boolean);
// CHROMIUM_EXECUTABLE_PATH runs the chromium project on a Chromium this
// Playwright version did not download, for a machine that cannot run
// `playwright install`. CI leaves it unset and uses the matching build.
const chromiumPath = process.env.CHROMIUM_EXECUTABLE_PATH;
const launchOptions = (name) => (name === 'chromium' && chromiumPath ? { launchOptions: { executablePath: chromiumPath } } : {});

export default defineConfig({
  testDir: './e2e',
  timeout: 120_000,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.JENKINS_URL || 'http://127.0.0.1:8080/jenkins/',
    // Firefox and WebKit run only in CI, so a failure there must carry its own
    // evidence: the trace holds every frame's console, network and DOM.
    trace: process.env.CI ? 'retain-on-failure' : 'off',
  },
  projects: browsers.map((name) => ({ name, use: { browserName: name, ...launchOptions(name) } })),
});
