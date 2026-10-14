import { defineConfig } from '@playwright/test';
import { chmodSync, mkdtempSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const frontend = path.dirname(fileURLToPath(import.meta.url));
const privateOutput = mkdtempSync(path.join(os.tmpdir(), 'regata-creator-browser-'));
chmodSync(privateOutput, 0o700);

export default defineConfig({
    testDir: path.join(frontend, 'test'),
    testMatch: '**/*.spec.mjs',
    outputDir: path.join(privateOutput, 'results'),
    reporter: [['list']],
    workers: 1,
    fullyParallel: false,
    retries: 0,
    use: { baseURL: 'http://127.0.0.1:4174/create/editor/', browserName: 'chromium',
        trace: 'retain-on-failure', screenshot: 'only-on-failure', video: 'off' },
    projects: [
        { name: 'desktop', use: { viewport: { width: 1280, height: 900 } } },
        { name: 'mobile', use: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } },
        { name: 'small-mobile', use: { viewport: { width: 360, height: 640 }, isMobile: true, hasTouch: true } }
    ],
    webServer: {
        command: 'node server.mjs', cwd: frontend,
        url: 'http://127.0.0.1:4174/create/editor/',
        reuseExistingServer: false, timeout: 60_000
    }
});