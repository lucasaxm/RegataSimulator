const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function browser(responses) {
    const calls = [];
    const context = vm.createContext({
        Headers, URLSearchParams,
        window: { location: { href: '', search: '' } },
        fetch: async (url, options) => {
            calls.push({ url, options });
            const response = responses.shift();
            assert.ok(response, 'Unexpected extra request');
            return response;
        }
    });
    for (const file of ['api.js', 'login.js']) {
        vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static', file), 'utf8'), context);
    }
    return { context, calls, run: expression => vm.runInContext(expression, context) };
}

const token = () => ({ ok: true, json: async () => ({ headerName: 'X-CSRF-TOKEN', token: 'masked-test-token' }) });
const success = () => ({ ok: true, status: 204 });

test('mutations acquire and submit a token, GET does not', async () => {
    const b = browser([success(), token(), success()]);
    await b.run("webSecurity.request('/api/templates')");
    await b.run("webSecurity.request('/api/sources/id', {method: 'DELETE'})");
    assert.deepEqual(b.calls.map(c => c.url), ['/api/templates', '/api/csrf', '/api/sources/id']);
    assert.equal(b.calls[2].options.headers.get('X-CSRF-TOKEN'), 'masked-test-token');
    assert.equal(b.calls[2].options.credentials, 'same-origin');
});

test('bad credentials never look like successful redirected login', async () => {
    const b = browser([token(), { ok: false, status: 401 }]);
    await b.run("globalThis.app = loginApp(); app.submitLogin()");
    assert.equal(b.context.window.location.href, '');
    assert.equal(b.run('app.errorMessage'), 'Invalid username or password');
    assert.equal(b.calls.length, 2);
});

test('successful login refreshes the token before navigation', async () => {
    const b = browser([token(), success(), token()]);
    await b.run('loginApp().submitLogin()');
    assert.deepEqual(b.calls.map(c => c.url), ['/api/csrf', '/api/login', '/api/csrf']);
    assert.equal(b.context.window.location.href, '/');
});

test('logout refreshes token and forbidden mutations are never replayed', async () => {
    const b = browser([token(), success(), token(), token(), { ok: false, status: 403 }]);
    await b.run('webSecurity.logout()');
    assert.equal(b.context.window.location.href, '/login.html');
    await b.run("webSecurity.request('/api/sources/review', {method: 'POST'})");
    assert.equal(b.calls.filter(c => c.url === '/api/sources/review').length, 1);
});

test('token acquisition failure prevents the mutation', async () => {
    const b = browser([{ ok: false, status: 500 }]);
    await assert.rejects(b.run("webSecurity.request('/api/sources/id', {method: 'DELETE'})"));
    assert.equal(b.calls.length, 1);
});