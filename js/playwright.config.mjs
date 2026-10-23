// End-to-end tests against a running Jenkins with the plugin (mvn hpi:run).
// JENKINS_URL defaults to the hpi:run address.
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 120_000,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.JENKINS_URL || 'http://127.0.0.1:8080/jenkins/',
  },
});
