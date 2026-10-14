import { defineConfig } from '@playwright/test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const packagePath = fileURLToPath(new URL('./', import.meta.url));

export default defineConfig({
  testDir: './test/browser',
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  outputDir: mkdtempSync(join(tmpdir(), 'regata-creator-playwright-')),
  use: { browserName: 'chromium', baseURL: 'http://127.0.0.1:4173', serviceWorkers: 'block' },
  projects: [
    { name: 'chromium-desktop', use: { viewport: { width: 1280, height: 900 },
      deviceScaleFactor: 1, hasTouch: false, isMobile: false } },
    { name: 'chromium-mobile', use: { viewport: { width: 390, height: 844 },
      deviceScaleFactor: 2, hasTouch: true, isMobile: true } },
  ],
  webServer: {
    command: 'node server.mjs',
    cwd: packagePath,
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: true,
    timeout: 30_000,
  },
});