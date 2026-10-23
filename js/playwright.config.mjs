// End-to-end tests against a running Jenkins with the plugin (mvn hpi:run).
// JENKINS_URL defaults to the hpi:run address. BROWSERS picks the engines,
// comma-separated: chromium (default), firefox, webkit.
import { defineConfig } from '@playwright/test';

const browsers = (process.env.BROWSERS || 'chromium').split(',').map((b) => b.trim()).filter(Boolean);

export default defineConfig({
  testDir: './e2e',
  timeout: 120_000,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.JENKINS_URL || 'http://127.0.0.1:8080/jenkins/',
  },
  projects: browsers.map((name) => ({ name, use: { browserName: name } })),
});
