import test from 'node:test';
import assert from 'node:assert/strict';
import * as adapter from '../telegram.mjs';

// All SDK/DOM interactions are local doubles: no hosted script or bot requests.
function environment(t, { sdk, script = true } = {}) {
    const originals = new Map(['window', 'document', 'fetch'].map(key =>
        [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
    const properties = new Map();
    const writes = [];
    const target = new EventTarget();
    const style = {
        setProperty(name, value) { properties.set(name, value); writes.push([name, value]); },
        removeProperty(name) { properties.delete(name); },
    };
    const window = { Telegram: sdk ? { WebApp: sdk } : undefined };
    const document = {
        documentElement: { style },
        getElementById(id) { assert.equal(id, 'telegram-sdk'); return script ? target : null; },
    };
    Object.defineProperties(globalThis, {
        window: { configurable: true, writable: true, value: window },
        document: { configurable: true, writable: true, value: document },
        fetch: { configurable: true, writable: true, value() { assert.fail('network forbidden'); } },
    });
    t.after(() => {
        for (const [key, descriptor] of originals) {
            if (descriptor) Object.defineProperty(globalThis, key, descriptor);
            else delete globalThis[key];
        }
    });
    return { window, document, properties, writes, target };
}

function sdkDouble(overrides = {}) {
    const calls = [];
    const events = new Map();
    const sdk = {
        platform: 'android', version: '8.0', initData: '',
        themeParams: {}, colorScheme: 'light', viewportStableHeight: 600,
        ready() { calls.push('ready'); },
        expand() { calls.push('expand'); },
        enableClosingConfirmation() { calls.push('closing'); },
        close() { calls.push('close'); },
        onEvent(name, handler) { calls.push(name); events.set(name, handler); },
        showPopup(params, callback) { calls.push('popup'); sdk.popup = { params, callback }; },
        showConfirm(message, callback) { calls.push('confirm'); sdk.confirm = { message, callback }; },
        ...overrides,
    };
    return { sdk, calls, events };
}

test('exact module and returned API; missing SDK is harmless standalone', t => {
    environment(t, { script: false });
    assert.deepEqual(Object.keys(adapter), ['initializeTelegram']);
    const bridge = adapter.initializeTelegram();
    assert.deepEqual(Object.keys(bridge).sort(), ['close', 'isEmbedded', 'notifyCopied']);
    assert.equal(bridge.isEmbedded(), false);
    assert.doesNotThrow(() => { bridge.notifyCopied(); bridge.close(); });
});

test('no browser globals are required', t => {
    environment(t);
    delete globalThis.window;
    delete globalThis.document;
    assert.equal(adapter.initializeTelegram().isEmbedded(), false);
});

test('already available SDK initializes bridge methods/events and notifies controls', t => {
    const { sdk, calls, events } = sdkDouble();
    const env = environment(t, { sdk });
    let updates = 0;
    const bridge = adapter.initializeTelegram({ onViewportChanged() { updates++; } });
    assert.equal(bridge.isEmbedded(), true);
    assert.deepEqual(calls, ['ready', 'expand', 'closing', 'themeChanged', 'viewportChanged']);
    assert.equal(updates, 1);
    assert.equal(env.properties.get('--tg-viewport-stable-height'), '600px');
    assert.equal(events.size, 2);
    env.target.dispatchEvent(new Event('load'));
    assert.equal(updates, 1);
});

test('late SDK load updates dynamic embedding and initializes exactly once without polling', t => {
    const env = environment(t);
    let updates = 0;
    const bridge = adapter.initializeTelegram({ onViewportChanged() { updates++; } });
    assert.equal(bridge.isEmbedded(), false);
    bridge.notifyCopied(); bridge.close();
    const { sdk, calls } = sdkDouble();
    env.window.Telegram = { WebApp: sdk };
    env.target.dispatchEvent(new Event('load'));
    env.target.dispatchEvent(new Event('load'));
    assert.equal(bridge.isEmbedded(), true);
    assert.equal(updates, 1);
    assert.equal(calls.filter(call => call === 'ready').length, 1);
    assert.equal(calls.includes('close'), false);
    assert.deepEqual(Object.keys(env.window), ['Telegram']);
});

test('script load error is silent and removes listeners; local controls remain usable', t => {
    const env = environment(t);
    const bridge = adapter.initializeTelegram();
    assert.doesNotThrow(() => env.target.dispatchEvent(new Event('error')));
    const { sdk, calls } = sdkDouble();
    env.window.Telegram = { WebApp: sdk };
    env.target.dispatchEvent(new Event('load'));
    bridge.notifyCopied(); bridge.close();
    assert.equal(bridge.isEmbedded(), false);
    assert.deepEqual(calls, []);
});

test('unknown platform is standalone; nonempty initData is UX-only embedding', t => {
    const { sdk, calls } = sdkDouble({ platform: 'unknown' });
    environment(t, { sdk });
    const bridge = adapter.initializeTelegram();
    assert.equal(bridge.isEmbedded(), false);
    bridge.notifyCopied(); bridge.close();
    assert.deepEqual(calls, ['themeChanged', 'viewportChanged']);
    sdk.initData = 'synthetic-unverified-data';
    assert.equal(bridge.isEmbedded(), true);
    bridge.close();
    assert.equal(calls.at(-1), 'close');
    sdk.initData = '   ';
    for (const platform of ['unknown', 'UNKNOWN', '', ' ', null, undefined]) {
        sdk.platform = platform;
        assert.equal(bridge.isEmbedded(), false);
    }
});

test('validated theme colors map to CSS variables and themeChanged switches light/dark', t => {
    const { sdk, events } = sdkDouble({ themeParams: {
        bg_color: '#123456', text_color: '#ABC', button_color: '#1234',
        button_text_color: '#abcdef80', hint_color: 'red', link_color: '#123456;display:none',
        arbitrary_color: '#fff',
    } });
    const env = environment(t, { sdk });
    adapter.initializeTelegram();
    assert.equal(env.properties.get('--tg-theme-bg-color'), '#123456');
    assert.equal(env.properties.get('--tg-theme-text-color'), '#ABC');
    assert.equal(env.properties.get('--tg-theme-button-color'), '#1234');
    assert.equal(env.properties.get('--tg-theme-button-text-color'), '#abcdef80');
    assert.equal(env.properties.has('--tg-theme-hint-color'), false);
    assert.equal(env.properties.has('--tg-theme-link-color'), false);
    assert.equal(env.properties.has('--tg-theme-arbitrary-color'), false);
    assert.equal(env.properties.get('color-scheme'), 'light');
    sdk.colorScheme = 'dark'; sdk.themeParams.bg_color = '#000';
    events.get('themeChanged')();
    assert.equal(env.properties.get('color-scheme'), 'dark');
    assert.equal(env.properties.get('--tg-theme-bg-color'), '#000');
    for (const invalid of ['#12', '#12345', '#GGGGGG', 'url(evil)', '', null, 123, '#fff\n']) {
        sdk.themeParams.bg_color = invalid;
        events.get('themeChanged')();
        assert.equal(env.properties.has('--tg-theme-bg-color'), false);
    }
    sdk.colorScheme = 'dark;display:none';
    events.get('themeChanged')();
    assert.equal(env.properties.has('color-scheme'), false);
});

test('viewport updates only stable-height CSS and callback, never canonical document state', t => {
    const { sdk, events } = sdkDouble({ viewportHeight: 999 });
    const env = environment(t, { sdk });
    const canonical = Object.freeze({ width: 1080, height: 1114, areas: Object.freeze([]) });
    let updates = 0;
    adapter.initializeTelegram({ onViewportChanged() { updates++; assert.equal(canonical.height, 1114); } });
    sdk.viewportStableHeight = 420.5;
    events.get('viewportChanged')({ isStateStable: false });
    assert.equal(env.properties.get('--tg-viewport-stable-height'), '420.5px');
    assert.equal(updates, 2);
    for (const invalid of [0, -1, NaN, Infinity, '600', null, undefined]) {
        sdk.viewportStableHeight = invalid;
        events.get('viewportChanged')({ isStateStable: true });
        assert.equal(env.properties.has('--tg-viewport-stable-height'), false);
    }
    assert.ok(env.writes.every(([key]) => key === 'color-scheme' || key.startsWith('--tg-')));
    assert.deepEqual(Object.keys(env.document), ['documentElement', 'getElementById']);
});

test('copied popup explains document/caption workflow and closes only on explicit close ID', t => {
    const { sdk, calls } = sdkDouble();
    environment(t, { sdk });
    const bridge = adapter.initializeTelegram();
    bridge.notifyCopied();
    assert.equal(sdk.popup.params.message,
        'Dados copiados. Envie a imagem como arquivo ao bot e cole os dados na legenda.');
    assert.deepEqual(sdk.popup.params.buttons, [
        { id: 'close', type: 'default', text: 'Voltar ao bot' },
        { id: 'continue', type: 'default', text: 'Continuar editando' },
    ]);
    assert.equal(calls.includes('close'), false);
    for (const value of ['continue', null, undefined, '', 'unexpected', true]) sdk.popup.callback(value);
    assert.equal(calls.includes('close'), false);
    sdk.popup.callback('close');
    assert.equal(calls.filter(call => call === 'close').length, 1);
    assert.equal(calls.includes('confirm'), false);
});

test('popup unsupported falls back to gated confirm with explicit acceptance only', t => {
    const { sdk, calls } = sdkDouble({ showPopup: undefined, version: '6.2' });
    environment(t, { sdk });
    const bridge = adapter.initializeTelegram();
    bridge.notifyCopied();
    assert.match(sdk.confirm.message, /Envie a imagem como arquivo ao bot/);
    assert.match(sdk.confirm.message, /Voltar ao bot\?/);
    for (const value of [false, undefined, null, 'true']) sdk.confirm.callback(value);
    assert.equal(calls.includes('close'), false);
    sdk.confirm.callback(true);
    assert.equal(calls.at(-1), 'close');
});

test('old and unknown versions skip newer features; explicit close still works', t => {
    const { sdk, calls } = sdkDouble({ version: '6.1' });
    environment(t, { sdk });
    const bridge = adapter.initializeTelegram();
    bridge.notifyCopied();
    assert.deepEqual(calls, ['ready', 'expand', 'themeChanged', 'viewportChanged']);
    for (const version of [undefined, null, '', 'not-a-version', '6.2;evil']) {
        sdk.version = version; bridge.notifyCopied();
    }
    assert.equal(calls.includes('popup'), false);
    assert.equal(calls.includes('confirm'), false);
    bridge.close();
    assert.equal(calls.at(-1), 'close');
});

test('SDK version helper gates newer methods and failures are harmless', t => {
    const versions = [];
    const { sdk, calls } = sdkDouble({
        version: undefined,
        isVersionAtLeast(version) { versions.push(version); return false; },
    });
    environment(t, { sdk });
    const bridge = adapter.initializeTelegram();
    bridge.notifyCopied();
    assert.ok(versions.every(version => version === '6.2'));
    assert.equal(calls.includes('popup'), false);
    sdk.isVersionAtLeast = () => true;
    bridge.notifyCopied();
    assert.equal(calls.at(-1), 'popup');
    sdk.isVersionAtLeast = () => { throw new Error('unsupported'); };
    assert.doesNotThrow(() => bridge.notifyCopied());
});

test('absent methods are harmless and callback cannot close after embedding disappears', t => {
    const { sdk, calls } = sdkDouble({ ready: null, expand: undefined, enableClosingConfirmation: false });
    environment(t, { sdk });
    const bridge = adapter.initializeTelegram();
    bridge.notifyCopied();
    sdk.platform = 'unknown';
    sdk.popup.callback('close'); bridge.close(); bridge.notifyCopied();
    assert.equal(calls.includes('close'), false);
    sdk.platform = 'ios'; sdk.close = undefined; sdk.showPopup = null; sdk.showConfirm = null;
    assert.doesNotThrow(() => { bridge.close(); bridge.notifyCopied(); });
});

test('SDK methods and owner callback exceptions never propagate or auto-close', t => {
    const fail = () => { throw new Error('synthetic bridge failure'); };
    const { sdk, calls, events } = sdkDouble({ ready: fail, expand: fail, enableClosingConfirmation: fail,
        showPopup: fail, close: fail });
    environment(t, { sdk });
    let bridge;
    assert.doesNotThrow(() => { bridge = adapter.initializeTelegram({ onViewportChanged: fail }); });
    assert.doesNotThrow(() => { bridge.notifyCopied(); bridge.close(); events.get('viewportChanged')(); });
    assert.equal(calls.includes('confirm'), false);
    sdk.showPopup = undefined; sdk.showConfirm = fail;
    assert.doesNotThrow(() => bridge.notifyCopied());
    sdk.onEvent = fail;
    assert.doesNotThrow(() => adapter.initializeTelegram());
});