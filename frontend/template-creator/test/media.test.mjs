import test from 'node:test';
import assert from 'node:assert/strict';
import { deflateSync } from 'node:zlib';
import * as media from '../media.mjs';

const { inspectHeader, loadLocalImage } = media;
const rejects = fn => assert.throws(fn, error => error instanceof Error && error.message.length > 0);

function crc32(bytes) {
    let crc = 0xffffffff;
    for (const byte of bytes) {
        crc ^= byte;
        for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
    return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data = Buffer.alloc(0)) {
    const output = Buffer.alloc(data.length + 12);
    output.writeUInt32BE(data.length, 0); output.write(type, 4, 'ascii');
    data.copy(output, 8);
    output.writeUInt32BE(crc32(output.subarray(4, -4)), output.length - 4);
    return output;
}

function png(width = 2, height = 3, exif) {
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4);
    ihdr[8] = 8; ihdr[9] = 6;
    // Small successful fixtures have real generated RGBA scanlines and CRCs.
    // Oversized fixtures declare dimensions only; no huge pixels are allocated.
    const pixels = width * height <= 100 ? Buffer.alloc((width * 4 + 1) * height) : Buffer.alloc(0);
    return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr),
        ...(exif ? [chunk('eXIf', exif)] : []), chunk('IDAT', deflateSync(pixels)), chunk('IEND')]);
}

function segment(marker, payload) {
    const header = Buffer.from([0xff, marker, 0, 0]);
    header.writeUInt16BE(payload.length + 2, 2);
    return Buffer.concat([header, payload]);
}

function tiff(orientation = 1, little = true) {
    const data = Buffer.alloc(26);
    data.write(little ? 'II' : 'MM');
    const u16 = (value, offset) => little ? data.writeUInt16LE(value, offset) : data.writeUInt16BE(value, offset);
    const u32 = (value, offset) => little ? data.writeUInt32LE(value, offset) : data.writeUInt32BE(value, offset);
    u16(42, 2); u32(8, 4); u16(1, 8);
    u16(0x0112, 10); u16(3, 12); u32(1, 14); u16(orientation, 18); u32(0, 22);
    return data;
}

function jpeg(width = 2, height = 3, { exif, afterSof = false, sof = 0xc0, extra = [] } = {}) {
    const frame = Buffer.from([8, 0, 0, 0, 0, 1, 1, 0x11, 0]);
    frame.writeUInt16BE(height, 1); frame.writeUInt16BE(width, 3);
    const app1 = exif ? [segment(0xe1, Buffer.concat([Buffer.from('Exif\0\0'), exif]))] : [];
    // Generated header fixtures. Decode lifecycle tests use a browser double,
    // not an assertion that these synthetic entropy bytes are real JPEG pixels.
    return Buffer.concat([Buffer.from([0xff, 0xd8]), ...extra, ...(!afterSof ? app1 : []),
        segment(sof, frame), ...(afterSof ? app1 : []),
        segment(0xda, Buffer.from([1, 1, 0, 0, 63, 0])), Buffer.from([0x3f, 0xff, 0xd9])]);
}

function browser(t, { width = 2, height = 3, event = 'load', throwConstructor = false,
    throwSource = false, url = 'blob:local-test', bitmapWidth = width, bitmapHeight = height,
    decodeBitmap } = {}) {
    const created = [], revoked = [], images = [], bitmaps = [], bitmapInputs = [];
    const sourceAssigned = Promise.withResolvers();
    const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'Image');
    const bitmapDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'createImageBitmap');
    const makeBitmap = () => {
        const bitmap = { width: bitmapWidth, height: bitmapHeight, closes: 0,
            close() { this.closes++; } };
        bitmaps.push(bitmap);
        return bitmap;
    };
    class FakeImage {
        constructor() {
            if (throwConstructor) throw new Error('constructor failed');
            this.naturalWidth = width; this.naturalHeight = height;
            images.push(this);
        }
        set src(value) {
            this.source = value;
            if (!value) return;
            if (throwSource) throw new Error('source failed');
            assert.ok(value.startsWith('blob:'), 'no HTTP image source');
            sourceAssigned.resolve();
            queueMicrotask(() => this[`on${event}`]?.());
        }
        get src() { return this.source; }
        async decode() { }
    }
    Object.defineProperty(globalThis, 'Image', { configurable: true, writable: true, value: FakeImage });
    t.after(() => descriptor ? Object.defineProperty(globalThis, 'Image', descriptor) : delete globalThis.Image);
    Object.defineProperty(globalThis, 'createImageBitmap', { configurable: true, writable: true,
        value: (image, options) => {
            bitmapInputs.push(image);
            assert.equal(image, images.at(-1), 'probe the existing HTMLImage, not the Blob');
            assert.equal(options, undefined, 'full bitmap decode without resizing');
            return decodeBitmap ? decodeBitmap(image, makeBitmap) : Promise.resolve(makeBitmap());
        } });
    t.after(() => bitmapDescriptor ? Object.defineProperty(globalThis, 'createImageBitmap', bitmapDescriptor)
        : delete globalThis.createImageBitmap);
    t.mock.method(URL, 'createObjectURL', file => { created.push(file); return url; });
    t.mock.method(URL, 'revokeObjectURL', value => { revoked.push(value); });
    return { created, revoked, images, bitmaps, bitmapInputs, makeBitmap, sourceAssigned: sourceAssigned.promise };
}

function assertReleased(state) {
    assert.deepEqual(state.revoked, ['blob:local-test']);
    for (const image of state.images) {
        assert.equal(image.src, '');
        assert.equal(image.onload, null); assert.equal(image.onerror, null); assert.equal(image.onabort, null);
    }
    for (const bitmap of state.bitmaps) assert.equal(bitmap.closes, 1);
}

test('exact media API; magic-based headers and sliced byte offsets', () => {
    assert.deepEqual(Object.keys(media).sort(), ['inspectHeader', 'loadLocalImage']);
    assert.deepEqual(inspectHeader(png()), { mime: 'image/png', width: 2, height: 3, orientation: 1 });
    for (const sof of [0xc0, 0xc2]) {
        assert.deepEqual(inspectHeader(jpeg(2, 3, { sof })),
            { mime: 'image/jpeg', width: 2, height: 3, orientation: 1 });
    }
    for (const bytes of [png(), jpeg()]) {
        const padded = Buffer.concat([Buffer.alloc(19), bytes, Buffer.alloc(7)]);
        assert.deepEqual(inspectHeader(padded.subarray(19, -7)), inspectHeader(bytes));
    }
    for (const bytes of [null, [], new Uint8Array(), Buffer.from('<svg/>'),
        Buffer.from('%PDF-1.7'), Buffer.alloc(1024 * 1024 + 1)]) rejects(() => inspectHeader(bytes));
});

test('declared dimensions fail before decoding; inclusive dimension/pixel limits', () => {
    for (const encode of [png, jpeg]) {
        for (const [width, height] of [[1, 1], [10000, 4000], [4000, 10000]]) {
            assert.equal(inspectHeader(encode(width, height)).width, width);
        }
        for (const [width, height] of [[0, 1], [1, 0], [10001, 1], [1, 10001], [10000, 4001]]) {
            assert.throws(() => inspectHeader(encode(width, height)), /10.000 px.*40 milhões/u);
        }
    }
});

test('identity EXIF and absent orientation are accepted in either TIFF byte order, before/after SOF', () => {
    for (const little of [true, false]) {
        for (const afterSof of [true, false]) {
            assert.equal(inspectHeader(jpeg(2, 3, { exif: tiff(1, little), afterSof })).orientation, 1);
        }
        const absent = tiff(1, little);
        if (little) absent.writeUInt16LE(0x0100, 10); else absent.writeUInt16BE(0x0100, 10);
        assert.equal(inspectHeader(jpeg(2, 3, { exif: absent })).orientation, 1);
    }
    assert.equal(inspectHeader(png(2, 3, tiff())).orientation, 1);
});

test('all nonidentity orientations fail with normalization guidance, even after JPEG SOF', () => {
    for (let orientation = 2; orientation <= 8; orientation++) {
        for (const little of [true, false]) {
            for (const afterSof of [true, false]) {
                assert.throws(() => inspectHeader(jpeg(2, 3, { exif: tiff(orientation, little), afterSof })),
                    /EXIF.*Gire\/normalize.*antes de marcar/u);
            }
        }
        assert.throws(() => inspectHeader(png(2, 3, tiff(orientation))), /EXIF/u);
    }
});

test('PNG EXIF after IDAT, including beyond the header window, is checked before decoding', async t => {
    const state = browser(t);
    for (const payload of [undefined, Buffer.alloc(1024 * 1024 + 16)]) {
        const original = png();
        const prefix = payload ? Buffer.concat([original.subarray(0, 33), chunk('IDAT', payload)])
            : original.subarray(0, -12);
        for (let orientation = 2; orientation <= 8; orientation++) {
            const bytes = Buffer.concat([prefix, chunk('eXIf', tiff(orientation)), chunk('IEND')]);
            await assert.rejects(loadLocalImage(new Blob([bytes], { type: 'image/png' })), /EXIF.*Gire\/normalize/u);
        }
    }
    assert.equal(state.created.length, 0);
    const identity = Buffer.concat([png().subarray(0, -12), chunk('eXIf', tiff(1)), chunk('IEND')]);
    (await loadLocalImage(new Blob([identity]))).dispose();
    const duplicate = Buffer.concat([png(2, 3, tiff()).subarray(0, -12), chunk('eXIf', tiff()), chunk('IEND')]);
    await assert.rejects(loadLocalImage(new Blob([duplicate])), /Cabeçalho/u);
    await assert.rejects(loadLocalImage(new Blob([png().subarray(0, -12)])), /Cabeçalho/u);
    const tooMany = Buffer.concat([png().subarray(0, -12), ...Array.from({ length: 2048 }, () => chunk('tEXt')), chunk('IEND')]);
    await assert.rejects(loadLocalImage(new Blob([tooMany])), /Cabeçalho/u);
    assert.equal(state.created.length, 1);
});

test('truncated and malicious TIFF never reads outside its own bounded segment', () => {
    const valid = tiff();
    for (let length = 0; length < valid.length; length++) {
        rejects(() => inspectHeader(jpeg(2, 3, { exif: valid.subarray(0, length) })));
    }
    const mutations = [
        data => { data[0] = 0; }, data => { data.writeUInt16LE(43, 2); },
        data => { data.writeUInt32LE(0xffffffff, 4); }, data => { data.writeUInt32LE(0, 4); },
        data => { data.writeUInt16LE(1025, 8); }, data => { data.writeUInt16LE(0, 12); },
        data => { data.writeUInt16LE(4, 12); }, data => { data.writeUInt32LE(2, 14); },
        data => { data.writeUInt32LE(0xffffffff, 14); }, data => { data.writeUInt16LE(0, 18); },
        data => { data.writeUInt16LE(9, 18); }, data => { data.writeUInt32LE(8, 22); },
        data => { data.writeUInt32LE(25, 22); },
        data => { data.writeUInt16LE(0x0100, 10); data.writeUInt16LE(2, 12);
            data.writeUInt32LE(20, 14); data.writeUInt32LE(0xfffffffa, 18); },
    ];
    for (const mutate of mutations) {
        const data = tiff(); mutate(data);
        rejects(() => inspectHeader(jpeg(2, 3, { exif: data })));
    }
    const duplicate = Buffer.alloc(38); tiff().copy(duplicate);
    duplicate.writeUInt16LE(2, 8); tiff().subarray(10, 22).copy(duplicate, 22);
    rejects(() => inspectHeader(jpeg(2, 3, { exif: duplicate })));
    const brokenExif = segment(0xe1, Buffer.from('Exif\0x'));
    rejects(() => inspectHeader(jpeg(2, 3, { extra: [brokenExif] })));
    // Non-EXIF APP1 (e.g. XMP) must not be interpreted as TIFF.
    assert.equal(inspectHeader(jpeg(2, 3, { extra: [segment(0xe1, Buffer.from('XMP'))] })).width, 2);
});

test('PNG and JPEG malformed/truncated/unknown headers fail closed', () => {
    const bytes = jpeg();
    const sosEnd = bytes.length - 3;
    for (let length = 0; length < sosEnd; length++) rejects(() => inspectHeader(bytes.subarray(0, length)));
    for (let length = 0; length < 33; length++) rejects(() => inspectHeader(png().subarray(0, length)));
    const badPng = png(); badPng.writeUInt32BE(12, 8);
    rejects(() => inspectHeader(badPng));
    const badType = png(); badType.write('OTHER', 12);
    rejects(() => inspectHeader(badType));
    const badLength = jpeg(); badLength.writeUInt16BE(1, 4);
    rejects(() => inspectHeader(badLength));
    const badSof = jpeg(); badSof[11] = 4;
    rejects(() => inspectHeader(badSof));
    rejects(() => inspectHeader(Buffer.from([255, 216, 255, 217])));
    rejects(() => inspectHeader(Buffer.from([255, 216, 255, 218, 0, 8, 1, 1, 0, 0, 63, 0])));
    rejects(() => inspectHeader(Buffer.from([255, 216, 255, 208])));
    rejects(() => inspectHeader(Buffer.from([255, 216, 255, 224, 255, 255])));
    const markerFill = Buffer.concat([Buffer.from([255, 216, 255]), jpeg().subarray(2)]);
    assert.equal(inspectHeader(markerFill).width, 2);
});

test('local image success owns a blob URL, preserves dimensions, never reencodes; dispose is idempotent', async t => {
    const state = browser(t);
    for (const [bytes, type] of [[png(), 'image/png'], [jpeg(), 'image/jpeg'], [png(), '']]) {
        const file = new Blob([bytes], { type }), result = await loadLocalImage(file);
        assert.deepEqual(Object.keys(result).sort(), ['image', 'url', 'width', 'height', 'dispose'].sort());
        assert.equal(result.width, 2); assert.equal(result.height, 3);
        assert.equal(state.created.at(-1), file);
        assert.equal(state.revoked.length, state.created.length - 1);
        assert.equal(result.image.src, 'blob:local-test');
        assert.equal(result.image, state.images.at(-1), 'retain the HTMLImage contract');
        assert.equal(state.bitmapInputs.length, state.created.length, 'one probe per load');
        assert.equal(result.image.onload, null); assert.equal(result.image.onerror, null);
        assert.equal(result.image.onabort, null);
        assert.equal(state.bitmapInputs.at(-1), result.image);
        assert.equal(state.bitmaps.at(-1).closes, 1, 'validation bitmap closed before returning');
        result.dispose(); result.dispose();
        assert.equal(state.revoked.length, state.created.length);
        assert.equal(state.bitmaps.at(-1).closes, 1);
    }
});

test('selection cancellation, invalid Blob, size/MIME/magic and predecode dimensions allocate no URL', async t => {
    const state = browser(t);
    for (const file of [null, undefined, 'https://example.invalid/image.png', {},
        new Blob(), new Blob(['<svg/>'], { type: 'image/svg+xml' }),
        new Blob([png()], { type: 'image/jpeg' }), new Blob([jpeg()], { type: 'image/png' }),
        new Blob(['fake'], { type: 'image/png' }),
        new Blob([png(10001, 1)], { type: 'image/png' }),
        new Blob([jpeg(10000, 4001)], { type: 'image/jpeg' }),
        new Blob([jpeg(2, 3, { exif: tiff(6) })], { type: 'image/jpeg' })]) {
        await assert.rejects(loadLocalImage(file), Error);
    }
    const tooBig = new Blob([png()], { type: 'image/png' });
    Object.defineProperty(tooBig, 'size', { value: 20 * 1024 * 1024 + 1 });
    await assert.rejects(loadLocalImage(tooBig), /20 MiB/u);
    await assert.rejects(loadLocalImage(null), /cancelada/u);
    assert.equal(state.created.length, 0); assert.equal(state.images.length, 0);
});

test('one bounded read min(1 MiB,size); SOF beyond bound is refused without decode', async t => {
    const state = browser(t);
    const extra = Array.from({ length: 17 }, () => segment(0xe2, Buffer.alloc(65530)));
    const hugeHeader = new Blob([jpeg(2, 3, { extra })], { type: 'image/jpeg' });
    const calls = [];
    t.mock.method(hugeHeader, 'slice', (start, end) => {
        calls.push([start, end]); return Blob.prototype.slice.call(hugeHeader, start, end);
    });
    await assert.rejects(loadLocalImage(hugeHeader), /1 MiB/u);
    assert.deepEqual(calls, [[0, 1024 * 1024]]);
    assert.equal(state.created.length, 0);
    const small = new Blob([png()], { type: 'image/png' });
    const smallCalls = [];
    t.mock.method(small, 'slice', (start, end) => {
        smallCalls.push([start, end]); return Blob.prototype.slice.call(small, start, end);
    });
    const result = await loadLocalImage(small); result.dispose();
    assert.deepEqual(smallCalls, [[0, small.size]]);
});

test('read failure and AbortError have friendly messages and no URL', async t => {
    const state = browser(t);
    for (const error of [new Error('private I/O detail'), Object.assign(new Error(), { name: 'AbortError' })]) {
        const file = new Blob([png()], { type: 'image/png' });
        t.mock.method(file, 'slice', () => ({ arrayBuffer: async () => { throw error; } }));
        await assert.rejects(loadLocalImage(file), error.name === 'AbortError' ? /cancelada/u : /ler a imagem/u);
    }
    assert.equal(state.created.length, 0);
});

for (const [name, options, message] of [
    ['decode error', { event: 'error' }, /decodificar/u],
    ['decode cancellation', { event: 'abort' }, /cancelada/u],
    ['decoded oversized side', { width: 10001, height: 1 }, /10.000 px/u],
    ['decoded oversized pixels', { width: 10000, height: 4001 }, /40 milhões/u],
    ['decoded zero', { width: 0 }, /10.000 px/u],
    ['decoded nonfinite', { height: NaN }, /10.000 px/u],
    ['decoded mismatch', { width: 3 }, /não correspondem/u],
    ['constructor failure', { throwConstructor: true }, /Não foi possível abrir a imagem local/u],
    ['source failure', { throwSource: true }, /Não foi possível abrir a imagem local/u],
    ['unsafe URL factory result', { url: 'https://example.invalid/image' }, /segurança/u],
]) {
    test(`${name} cleans the owned URL exactly once and clears handlers`, async t => {
        const state = browser(t, options);
        await assert.rejects(loadLocalImage(new Blob([png()], { type: 'image/png' })), message);
        assert.equal(state.created.length, 1); assert.equal(state.revoked.length, 1);
        for (const image of state.images) {
            assert.equal(image.onload, null); assert.equal(image.onerror, null); assert.equal(image.onabort, null);
            assert.equal(image.src, '');
        }
    });
}

test('decode timeout cancels the local source and releases URL without waiting in real time', async t => {
    const state = browser(t, { event: 'never' });
    const cleared = [];
    t.mock.method(globalThis, 'setTimeout', (callback, milliseconds) => {
        assert.equal(milliseconds, 15000);
        queueMicrotask(callback);
        return 123;
    });
    t.mock.method(globalThis, 'clearTimeout', timer => { cleared.push(timer); });
    await assert.rejects(loadLocalImage(new Blob([png()], { type: 'image/png' })), /demorou demais/u);
    assert.deepEqual(state.revoked, ['blob:local-test']);
    assert.deepEqual(cleared, [123]);
    assert.equal(state.images[0].src, '');
    assert.equal(state.images[0].onload, null);
});

test('URL creation failure is friendly and does not revoke an unowned URL', async t => {
    const state = browser(t);
    t.mock.method(URL, 'createObjectURL', () => { throw new Error('private browser detail'); });
    await assert.rejects(loadLocalImage(new Blob([png()], { type: 'image/png' })), /Não foi possível abrir/u);
    assert.equal(state.revoked.length, 0);
    assert.equal(state.images.length, 0);
});

test('corrupt PNG IDAT fails despite successful HTMLImage onload and decode', async t => {
    const bytes = Buffer.concat([png().subarray(0, 33), chunk('IDAT', Buffer.from('not a zlib stream')), chunk('IEND')]);
    assert.deepEqual(inspectHeader(bytes), { mime: 'image/png', width: 2, height: 3, orientation: 1 });
    let decoded = false;
    const state = browser(t, { decodeBitmap: async image => {
        await image.decode();
        decoded = true;
        throw new DOMException('private corrupt pixel detail', 'InvalidStateError');
    } });
    await assert.rejects(loadLocalImage(new Blob([bytes], { type: 'image/png' })),
        { message: 'Não foi possível decodificar a imagem. Use outro PNG ou JPEG.' });
    assert.equal(decoded, true);
    assert.equal(state.bitmapInputs.length, 1);
    assert.equal(state.bitmaps.length, 0);
    assertReleased(state);
});

test('synchronous bitmap failure is a friendly decode error, not an open error', async t => {
    const state = browser(t, { decodeBitmap: () => { throw new Error('private decoder detail'); } });
    await assert.rejects(loadLocalImage(new Blob([png()])),
        { message: 'Não foi possível decodificar a imagem. Use outro PNG ou JPEG.' });
    assertReleased(state);
});

test('missing or nonfunction bitmap API fails closed without allocating a URL', async t => {
    const state = browser(t);
    for (const value of [undefined, null, {}]) {
        globalThis.createImageBitmap = value;
        await assert.rejects(loadLocalImage(new Blob([png()])), /navegador.*verificação.*atualizado.*createImageBitmap/u);
    }
    delete globalThis.createImageBitmap;
    await assert.rejects(loadLocalImage(new Blob([png()])), /navegador.*verificação.*atualizado.*createImageBitmap/u);
    assert.equal(state.created.length, 0); assert.equal(state.revoked.length, 0);
    assert.equal(state.images.length, 0); assert.equal(state.bitmapInputs.length, 0);
});

for (const [name, options, message] of [
    ['width mismatch', { bitmapWidth: 3 }, /não correspondem/u],
    ['height mismatch', { bitmapHeight: 2 }, /não correspondem/u],
    ['zero width', { bitmapWidth: 0 }, /10.000 px/u],
    ['zero height', { bitmapHeight: 0 }, /10.000 px/u],
    ['negative', { bitmapWidth: -1 }, /10.000 px/u],
    ['fractional', { bitmapWidth: 2.5 }, /10.000 px/u],
    ['nonfinite', { bitmapHeight: NaN }, /10.000 px/u],
    ['oversized', { bitmapWidth: 10001 }, /10.000 px/u],
    ['oversized pixels', { bitmapWidth: 10000, bitmapHeight: 4001 }, /40 milhões/u],
]) {
    test(`bitmap ${name} is rejected and closed exactly once`, async t => {
        const state = browser(t, options);
        await assert.rejects(loadLocalImage(new Blob([png()])), message);
        assert.equal(state.bitmaps.length, 1);
        assertReleased(state);
    });
}

test('bitmap must also match the current decoded HTML dimensions', async t => {
    const state = browser(t, { decodeBitmap: async (image, makeBitmap) => {
        image.naturalWidth = 3;
        return makeBitmap();
    } });
    await assert.rejects(loadLocalImage(new Blob([png()])), /não correspondem/u);
    assertReleased(state);
});

test('a single 15 second deadline covers image load and bitmap decode; late bitmap closes', async t => {
    let finishBitmap;
    const bitmapStarted = Promise.withResolvers();
    const state = browser(t, { event: 'never', decodeBitmap: () => {
        bitmapStarted.resolve();
        return new Promise(resolve => { finishBitmap = resolve; });
    } });
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const timer = t.mock.method(globalThis, 'setTimeout');
    const clear = t.mock.method(globalThis, 'clearTimeout');
    const loading = loadLocalImage(new Blob([png()]));
    const rejected = assert.rejects(loading, /demorou demais/u);
    // Blob reads complete asynchronously; use a source-set signal, not a sleep.
    await state.sourceAssigned;
    t.mock.timers.tick(10000);
    state.images[0].onload();
    await bitmapStarted.promise;
    t.mock.timers.tick(4999);
    assert.equal(state.revoked.length, 0);
    t.mock.timers.tick(1);
    await rejected;
    assert.equal(timer.mock.calls.length, 1);
    assert.equal(timer.mock.calls[0].arguments[1], 15000);
    assert.equal(clear.mock.calls.length, 1);
    assertReleased(state);
    const bitmap = state.makeBitmap();
    finishBitmap(bitmap);
    await Promise.resolve();
    assertReleased(state);
    assert.equal(bitmap.closes, 1);
});

test('cancellation during bitmap decode releases URL and closes a late bitmap', async t => {
    let finishBitmap;
    const bitmapStarted = Promise.withResolvers();
    const state = browser(t, { decodeBitmap: () => {
        bitmapStarted.resolve();
        return new Promise(resolve => { finishBitmap = resolve; });
    } });
    const loading = loadLocalImage(new Blob([png()]));
    const rejected = assert.rejects(loading, /cancelada/u);
    await bitmapStarted.promise;
    state.images[0].onabort();
    await rejected;
    assertReleased(state);
    finishBitmap(state.makeBitmap());
    await Promise.resolve();
    assertReleased(state);
});

test('bitmap AbortError preserves the friendly cancellation cause', async t => {
    const state = browser(t, { decodeBitmap: async () => { throw new DOMException('private abort', 'AbortError'); } });
    await assert.rejects(loadLocalImage(new Blob([png()])), /cancelada/u);
    assertReleased(state);
});

test('late bitmap rejection after timeout is handled and never leaks a URL', async t => {
    let rejectBitmap;
    const bitmapStarted = Promise.withResolvers();
    const state = browser(t, { decodeBitmap: () => {
        bitmapStarted.resolve();
        return new Promise((resolve, reject) => { rejectBitmap = reject; });
    } });
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const loading = loadLocalImage(new Blob([png()]));
    const rejected = assert.rejects(loading, /demorou demais/u);
    await bitmapStarted.promise;
    t.mock.timers.tick(15000);
    await rejected;
    rejectBitmap(new DOMException('private late decode failure', 'InvalidStateError'));
    await Promise.resolve();
    assertReleased(state);
});

test('loader waits for full bitmap decode and clears its deadline on success', async t => {
    let finishBitmap;
    const bitmapStarted = Promise.withResolvers();
    const state = browser(t, { decodeBitmap: () => {
        bitmapStarted.resolve();
        return new Promise(resolve => { finishBitmap = resolve; });
    } });
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const clear = t.mock.method(globalThis, 'clearTimeout');
    let returned = false;
    const loading = loadLocalImage(new Blob([png()])).then(result => { returned = true; return result; });
    await bitmapStarted.promise;
    assert.equal(returned, false);
    t.mock.timers.tick(14999);
    assert.equal(returned, false);
    assert.equal(clear.mock.calls.length, 0);
    finishBitmap(state.makeBitmap());
    const result = await loading;
    assert.equal(state.bitmaps[0].closes, 1);
    assert.equal(clear.mock.calls.length, 1);
    assert.equal(result.image.onload, null); assert.equal(result.image.onerror, null); assert.equal(result.image.onabort, null);
    t.mock.timers.tick(15000);
    assert.equal(state.revoked.length, 0, 'cleared deadline cannot dispose the successful image');
    result.dispose(); result.dispose();
    assert.deepEqual(state.revoked, ['blob:local-test']);
});

test('bitmap failure clears its timer and late image events cannot restart decode', async t => {
    const state = browser(t, { event: 'never', decodeBitmap: async () => { throw new Error('bad pixels'); } });
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const clear = t.mock.method(globalThis, 'clearTimeout');
    const loading = loadLocalImage(new Blob([png()]));
    const rejected = assert.rejects(loading, /decodificar/u);
    await state.sourceAssigned;
    const onload = state.images[0].onload;
    await onload();
    await rejected;
    assert.equal(clear.mock.calls.length, 1);
    await onload();
    t.mock.timers.tick(15000);
    assert.equal(state.bitmapInputs.length, 1);
    assertReleased(state);
});

test('image error during bitmap decode closes a late bitmap and preserves the decode message', async t => {
    let finishBitmap;
    const bitmapStarted = Promise.withResolvers();
    const state = browser(t, { decodeBitmap: () => {
        bitmapStarted.resolve();
        return new Promise(resolve => { finishBitmap = resolve; });
    } });
    const loading = loadLocalImage(new Blob([png()]));
    const rejected = assert.rejects(loading,
        { message: 'Não foi possível decodificar a imagem. Use outro PNG ou JPEG.' });
    await bitmapStarted.promise;
    state.images[0].onerror();
    await rejected;
    assertReleased(state);
    finishBitmap(state.makeBitmap());
    await Promise.resolve();
    assertReleased(state);
});