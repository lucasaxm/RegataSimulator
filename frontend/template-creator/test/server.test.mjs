import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { request } from 'node:http';
import { fileURLToPath } from 'node:url';

const cwd = fileURLToPath(new URL('..', import.meta.url));

function get(path, { method = 'GET', host = '127.0.0.1:4174' } = {}) {
    return new Promise((resolve, reject) => {
        const req = request({ hostname: '127.0.0.1', port: 4174, path, method, headers: { Host: host } }, res => {
            let body = '';
            res.setEncoding('utf8');
            res.on('data', chunk => { body += chunk; });
            res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body }));
            res.on('error', reject);
        });
        req.setTimeout(5000, () => req.destroy(new Error('Preview request timed out')));
        req.on('error', reject);
        req.end();
    });
}

for (const telegram of [false, true]) {
    test(`static preview restrictions with Telegram mode ${telegram ? 'enabled' : 'disabled'}`, { timeout: 20_000 }, async () => {
        const child = spawn(process.execPath, ['server.mjs', ...(telegram ? ['--telegram-preview'] : [])], {
            cwd, stdio: ['ignore', 'pipe', 'pipe']
        });
        const exited = once(child, 'exit');
        let errors = '';
        child.stderr.on('data', data => { errors += data; });
        try {
            await new Promise((resolve, reject) => {
                const timer = setTimeout(() => reject(new Error('Preview did not start')), 10_000);
                const finish = error => {
                    clearTimeout(timer);
                    child.stdout.off('data', ready);
                    child.off('exit', stopped);
                    child.off('error', finish);
                    if (error) reject(error); else resolve();
                };
                const ready = data => { if (data.toString().includes('Editor: http://127.0.0.1:4174/')) finish(); };
                const stopped = () => finish(new Error(`Preview exited before binding: ${errors}`));
                child.stdout.on('data', ready);
                child.once('exit', stopped);
                child.once('error', finish);
            });
            const html = await get('/create/editor/');
            assert.equal(html.status, 200);
            assert.match(html.body, /https:\/\/telegram\.org\/js\/telegram-web-app\.js/);
            assert.match(html.body, /href="\/create\/legacy"/);
            assert.doesNotMatch(html.body, /href="\/create"/);
            const csp = html.headers['content-security-policy'];
            assert.ok(csp.endsWith(telegram
                ? "frame-ancestors 'self' https://*.telegram.org https://telegram.org"
                : "frame-ancestors 'none'"));
            assert.match(csp, /script-src 'self' https:\/\/telegram\.org\/js\/telegram-web-app\.js;/);
            assert.match(csp, /connect-src 'none';/);
            assert.equal(html.headers['cache-control'], 'no-store');
            assert.equal((await get('/create/editor/', { host: 'untrusted.example' })).status, 403);
            assert.equal((await get('/create/editor/', { method: 'POST' })).status, 405);
            for (const path of ['/api/csrf', '/.env', '/create', '/create/editor/../server.mjs', '/create/editor/%2e%2e/server.mjs']) {
                assert.equal((await get(path)).status, 404, path);
            }
            assert.equal((await get('/create/editor/', { method: 'HEAD' })).body, '');
        } finally {
            if (child.exitCode === null) child.kill('SIGTERM');
            await exited;
        }
    });
}

test('Telegram preview refuses LAN binding before building or listening', async () => {
    const child = spawn(process.execPath, ['server.mjs', '--lan', '--telegram-preview'], { cwd, stdio: 'ignore' });
    const [code] = await once(child, 'exit');
    assert.notEqual(code, 0);
});