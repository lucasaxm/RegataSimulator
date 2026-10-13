import test from 'node:test';
import assert from 'node:assert/strict';
import * as model from '../model.mjs';

const {
    createEmptyDocument, createScene, validateDocument, clone, rectPoints,
    encodeDocument, decodeDocument, sourceOptions, nextImageSource, addDrawnArea,
    importImageCsv, exportImageCsv, layoutText,
} = model;
const HEADER = 'Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background';
// User-provided coordinates only. No image or example files are opened.
const CSV = `${HEADER}\n1,1,105,840,274,840,274,1018,105,1018,0\n2,2,461,842,631,842,631,1020,461,1020,0\n3,3,800,840,969,840,969,1018,800,1018,0`;
const COLORS = ['#70b9cd', '#e3b56a', '#b298d5'];
const RECT = { x: 20, y: 30, width: 100, height: 80 };
const LEGACY_STYLE = {
    font: 'sans', size: 34, minSize: 14, fill: '#ffffff', outline: '#000000',
    outlineWidth: 0, align: 'center', lineHeight: 1.15,
    weight: 'normal', overflow: 'shrink',
};
const invalid = fn => assert.throws(fn, Error);
const draw = (doc, type, id, extra = {}) => addDrawnArea(doc, { type, id, rect: RECT, ...extra });

function documentWith(entries) {
    const doc = createEmptyDocument(1200, 1200);
    const count = Math.max(0, ...entries.map(([, source]) => source));
    doc.sources = Array.from({ length: count }, (_, index) => ({ slot: index + 1,
        description: `Source ${index + 1}`, color: COLORS[index % COLORS.length] }));
    doc.areas = entries.map(([type, source], index) => {
        const common = { id: `area-${index + 1}`, type, source, background: false };
        return type === 'image' ? { ...common, points: rectPoints(RECT) }
            : { ...common, rect: clone(RECT), text: '{{source.description}}', style: clone(LEGACY_STYLE) };
    });
    return validateDocument(doc);
}

function imageOptions(images) {
    const doc = documentWith(images.map(source => ['image', source]));
    return doc.areas.map(area => sourceOptions(doc, area.id));
}

function changeField(csv, row, field, value) {
    const lines = csv.split('\n');
    const fields = lines[row].split(',');
    fields[field] = value;
    lines[row] = fields.join(',');
    return lines.join('\n');
}

test('the six real-image workflow functions are exported alongside every legacy API', () => {
    assert.deepEqual(Object.keys(model).sort(), [
        'FONT_FAMILIES', 'clone', 'rectPoints', 'createScene', 'resolveText', 'layoutText',
        'validateDocument', 'layerOrder', 'moveArea', 'moveCorner', 'encodeDocument',
        'decodeDocument', 'viewToImage', 'imageToView', 'zoomAt',
        'createEmptyDocument', 'sourceOptions', 'nextImageSource', 'addDrawnArea',
        'importImageCsv', 'exportImageCsv',
    ].sort());
});

test('empty custom editor drafts have the existing version-1 shape and JSON roundtrip', () => {
    const doc = createEmptyDocument();
    assert.deepEqual(doc, {
        format: 'regata-creator-spike', version: 1, width: 900, height: 620,
        scene: 'custom', sources: [], areas: [],
    });
    assert.equal(validateDocument(doc), doc);
    assert.deepEqual(decodeDocument(encodeDocument(doc)), doc);
    assert.deepEqual(model.layerOrder(doc), ['template']);
    assert.equal(nextImageSource(doc), 1);
    const second = createEmptyDocument(1080, 1114);
    second.sources.push({ slot: 1, description: 'Source 1', color: COLORS[0] });
    assert.deepEqual(doc.sources, []);
    // Empty editor JSON is intentionally not an empty bot-upload CSV.
    invalid(() => exportImageCsv(doc));
});

test('only a fully empty custom draft may have no areas and no sources', () => {
    for (const scene of ['captions', 'chat']) {
        const empty = createEmptyDocument();
        empty.scene = scene;
        invalid(() => validateDocument(empty));
        const legacy = createScene(scene);
        legacy.areas = [];
        invalid(() => validateDocument(legacy));
    }
    const paletteOnly = createEmptyDocument();
    paletteOnly.sources = [{ slot: 1, description: 'Source 1', color: COLORS[0] }];
    invalid(() => validateDocument(paletteOnly));
    const reference = draw(createEmptyDocument(), 'image', 'image-1');
    reference.sources = [];
    invalid(() => validateDocument(reference));
});

for (const [width, height] of [
    [0, 620], [-1, 620], [900, 0], [900.5, 620], [900, 620.5],
    [10001, 1], [1, 10001], [10000, 4001], [Infinity, 620], [900, NaN],
    ['900', 620], [null, 620],
]) {
    test(`empty document rejects invalid dimensions ${width} x ${height}`, () => {
        invalid(() => createEmptyDocument(width, height));
    });
}

test('empty dimensions retain inclusive coordinate and pixel bounds', () => {
    assert.equal(createEmptyDocument(10000, 4000).width, 10000);
    assert.equal(createEmptyDocument(1, 1).height, 1);
});

test('legacy scenes and synthetic text styles are not augmented or mutated', () => {
    const captions = createScene();
    const chat = createScene('chat');
    assert.deepEqual(captions.areas[1].style, LEGACY_STYLE);
    assert.deepEqual(chat.areas[1].style, {
        ...LEGACY_STYLE, align: 'left', fill: '#70b9cd', size: 48,
    });
    for (const doc of [captions, chat]) {
        const before = clone(doc);
        validateDocument(doc);
        decodeDocument(encodeDocument(doc));
        for (const area of doc.areas.filter(area => area.type === 'text')) {
            assert.equal(Object.hasOwn(area.style, 'boxFill'), false);
        }
        assert.deepEqual(doc, before);
    }
});

for (const [references, expected] of [
    [[1], [[1]]],
    [[1, 2], [[1], [1, 2]]],
    [[1, 1, 1], [[1], [1], [1, 2]]],
    [[1, 1, 2], [[1], [1, 2], [1, 2]]],
    [[1, 2, 3], [[1], [2], [2, 3]]],
    [[1, 2, 2], [[1], [1, 2], [2, 3]]],
    [[1, 2, 1], [[1], [1], [2, 3]]],
    [[1, 3, 2], [[1], [1, 2], []]],
]) {
    test(`image picker applies adjacency and candidate-union filtering to ${references}`, () => {
        assert.deepEqual(imageOptions(references), expected);
    });
}

test('image adjacency ignores interleaved texts and text source numbers', () => {
    const doc = documentWith([
        ['text', 3], ['image', 1], ['text', 3], ['image', 2], ['text', 1], ['image', 3],
    ]);
    assert.deepEqual(sourceOptions(doc, 'area-2'), [1]);
    assert.deepEqual(sourceOptions(doc, 'area-4'), [2]);
    assert.deepEqual(sourceOptions(doc, 'area-6'), [2, 3]);
    assert.deepEqual(sourceOptions(doc, 'area-1'), [1, 2, 3]);
});

test('image rebinding filters candidates which would leave text-used slots above a hole', () => {
    const doc = documentWith([['image', 1], ['image', 2], ['text', 3]]);
    assert.deepEqual(sourceOptions(doc, 'area-2'), [2]);
    const gap = clone(doc);
    gap.areas[1].source = 1;
    invalid(() => validateDocument(gap));
    const bridged = documentWith([['image', 1], ['text', 2], ['image', 2], ['text', 3]]);
    assert.deepEqual(sourceOptions(bridged, 'area-3'), [1, 2]);
});

test('first image always offers source 1, even if earlier text supplies the union', () => {
    const doc = documentWith([['text', 1], ['image', 2], ['image', 3]]);
    assert.deepEqual(sourceOptions(doc, 'area-2'), [1]);
    // The controller must validate edits; the first-image rule is not normalization.
    const gap = clone(doc);
    gap.areas[1].source = 1;
    invalid(() => validateDocument(gap));
});

test('text picker lists used references, not all declared synthetic palette samples', () => {
    const captions = createScene();
    assert.deepEqual(sourceOptions(captions, 'text-1'), [1, 2, 3]);
    const chat = createScene('chat');
    assert.equal(chat.sources.length, 3);
    assert.deepEqual(sourceOptions(chat, 'text-1'), [1]);
    assert.equal(chat.areas[1].source, sourceOptions(chat, 'text-1')[0]);
    const onlyText = documentWith([['text', 2], ['text', 1]]);
    onlyText.sources.reverse();
    assert.deepEqual(sourceOptions(onlyText, 'area-1'), [1, 2]);
    invalid(() => sourceOptions(chat, 'missing'));
    invalid(() => sourceOptions(createEmptyDocument(), 'missing'));
});

test('text binds and rebinds to source 2 or source 1 without introducing slots', () => {
    let doc = documentWith([['image', 1], ['image', 2]]);
    doc = draw(doc, 'text', 'label', { source: 2 });
    assert.equal(doc.areas.at(-1).source, 2);
    assert.deepEqual(sourceOptions(doc, 'label'), [1, 2]);
    const rebound = clone(doc);
    rebound.areas.at(-1).source = 1;
    assert.equal(validateDocument(rebound), rebound);
    assert.equal(doc.areas.at(-1).source, 2);
    const defaultBound = draw(doc, 'text', 'default-label');
    assert.equal(defaultBound.areas.at(-1).source, 2);
    const invalidSelection = draw(doc, 'text', 'unknown-label', { source: 3 });
    assert.equal(invalidSelection.areas.at(-1).source, 2);
    assert.equal(invalidSelection.sources.length, 2);
});

test('text creation ignores unused declared slots and defaults to the last image', () => {
    const chat = draw(createScene('chat'), 'text', 'new-label', { source: 2 });
    assert.equal(chat.areas.at(-1).source, 1);
    assert.deepEqual(sourceOptions(chat, 'new-label'), [1]);
    const nonadjacent = documentWith([['image', 1], ['image', 2], ['text', 3], ['image', 1]]);
    assert.equal(draw(nonadjacent, 'text', 'new-label').areas.at(-1).source, 1);
    assert.equal(draw(nonadjacent, 'text', 'bound-label', { source: 3 }).areas.at(-1).source, 3);
});

test('first text without images creates slot 1 and the first image shares its picker slot', () => {
    const empty = createEmptyDocument(1200, 1200);
    const text = draw(empty, 'text', 'text-1', { source: 2 });
    assert.equal(text.areas[0].source, 1);
    assert.deepEqual(text.sources, [{ slot: 1, description: 'Source 1', color: COLORS[0] }]);
    assert.deepEqual(sourceOptions(text, 'text-1'), [1]);
    assert.equal(nextImageSource(text), 1);
    const doc = draw(text, 'image', 'image-1');
    assert.deepEqual(doc.areas.map(area => area.source), [1, 1]);
    assert.deepEqual(doc.sources, text.sources);
    assert.deepEqual(sourceOptions(doc, 'text-1'), [1]);
    assert.deepEqual(sourceOptions(doc, 'image-1'), [1]);
    assert.ok(sourceOptions(doc, 'image-1').includes(doc.areas.at(-1).source));
    assert.equal(validateDocument(doc), doc);
    assert.deepEqual(draw(text, 'image', 'image-1', { source: nextImageSource(text) }), doc);
    assert.deepEqual(empty.areas, []);
});

test('next image default respects image adjacency when text already occupies unused image slots', () => {
    assert.equal(nextImageSource(createScene('chat')), 2);
    const doc = documentWith([['image', 1], ['text', 2], ['image', 1], ['text', 3]]);
    assert.equal(nextImageSource(doc), 2);
    const result = draw(doc, 'image', 'new-image');
    assert.deepEqual(result.areas.map(area => area.source), [1, 2, 1, 3, 2]);
    assert.deepEqual(result.sources, doc.sources);
    assert.deepEqual(sourceOptions(result, 'new-image'), [1, 2]);
    assert.ok(sourceOptions(result, 'new-image').includes(result.areas.at(-1).source));
    const textOnly = documentWith([['text', 1], ['text', 2]]);
    assert.equal(nextImageSource(textOnly), 1);
    assert.equal(draw(textOnly, 'text', 'default-text').areas.at(-1).source, 1);
});

for (const [name, entries, expected, options] of [
    ['text-only slots', [['text', 2], ['text', 1]], 1, [1]],
    ['text occupies slot 2', [['image', 1], ['text', 2]], 2, [1, 2]],
    ['text occupies slot 3', [['image', 1], ['image', 2], ['text', 3]], 3, [2, 3]],
    ['text occupies slots 3 and 4', [['image', 1], ['image', 2], ['text', 4], ['text', 3]], 3, [2, 3]],
    ['interleaved text precedes a reused image', [['text', 3], ['image', 1], ['text', 2], ['image', 1]], 2, [1, 2]],
    ['reused image 1 excludes unused slot 3', [['image', 1], ['image', 2], ['image', 1]], 2, [1, 2]],
    ['reused image 2 excludes unused slot 4', [['image', 1], ['image', 2], ['image', 3], ['image', 2]], 3, [2, 3]],
]) {
    test(`default appended image remains in its picker: ${name}`, () => {
        const doc = documentWith(entries);
        const before = clone(doc);
        assert.equal(nextImageSource(doc), expected);
        const result = draw(doc, 'image', 'new-image');
        assert.equal(result.areas.at(-1).source, expected);
        assert.deepEqual(sourceOptions(result, 'new-image'), options);
        assert.ok(sourceOptions(result, 'new-image').includes(result.areas.at(-1).source));
        assert.deepEqual(result.sources, before.sources);
        assert.deepEqual(result.areas.slice(0, -1), before.areas);
        assert.equal(validateDocument(result), result);
        assert.deepEqual(decodeDocument(encodeDocument(result)), result);
        // The controller passes the default explicitly, rather than omitting it.
        assert.deepEqual(draw(doc, 'image', 'new-image', { source: nextImageSource(doc) }), result);
        assert.deepEqual(doc, before);
    });
}

test('explicit reuse of image source 2 still makes the next default source 3', () => {
    const original = documentWith([['image', 1], ['image', 2]]);
    const reused = draw(original, 'image', 'reused', { source: 2 });
    assert.equal(nextImageSource(reused), 3);
    const result = draw(reused, 'image', 'new-image');
    assert.deepEqual(result.areas.map(area => area.source), [1, 2, 2, 3]);
    assert.deepEqual(sourceOptions(result, 'new-image'), [2, 3]);
    assert.ok(sourceOptions(result, 'new-image').includes(result.areas.at(-1).source));
    assert.deepEqual(result.sources[2], { slot: 3, description: 'Source 3', color: COLORS[2] });
});

test('all short contiguous mixed-area unions append a picker-consistent default', () => {
    function check(entries) {
        const references = new Set(entries.map(([, source]) => source));
        if (Array.from({ length: references.size }, (_, index) => index + 1)
            .every(source => references.has(source))) {
            const doc = documentWith(entries);
            const result = draw(doc, 'image', 'new-image');
            const previous = entries.findLast(([type]) => type === 'image')?.[1];
            const options = sourceOptions(result, 'new-image');
            assert.deepEqual(options, previous === undefined ? [1] : [previous, previous + 1]);
            assert.ok(options.includes(result.areas.at(-1).source), JSON.stringify(entries));
            assert.equal(result.areas.at(-1).source, nextImageSource(doc));
            assert.equal(result.areas.at(-1).source, options.at(-1));
            assert.equal(validateDocument(result), result);
        }
        if (entries.length === 4) return;
        for (const type of ['image', 'text']) {
            for (let source = 1; source <= 3; source++) check([...entries, [type, source]]);
        }
    }
    check([]);
});

test('drawn image defaults progress through 1, 2, 3 with stable synthetic palette', () => {
    let doc = createEmptyDocument(1200, 1200);
    for (let source = 1; source <= 3; source++) {
        doc = draw(doc, 'image', `image-${source}`);
        assert.ok(sourceOptions(doc, `image-${source}`).includes(source));
        doc = draw(doc, 'text', `text-${source}`);
    }
    assert.deepEqual(doc.areas.map(area => area.source), [1, 1, 2, 2, 3, 3]);
    assert.deepEqual(doc.sources, COLORS.map((color, index) => ({ slot: index + 1,
        description: `Source ${index + 1}`, color })));
    assert.deepEqual(decodeDocument(encodeDocument(doc)), doc);
});

test('drawing clones existing state and input rectangles and preserves palette metadata', () => {
    const original = createScene('chat');
    const snapshot = clone(original);
    const rect = clone(RECT);
    const image = addDrawnArea(original, { type: 'image', id: 'drawn', rect });
    assert.deepEqual(image.areas.at(-1), {
        id: 'drawn', type: 'image', source: 2, background: false, points: rectPoints(rect),
    });
    const text = addDrawnArea(original, { type: 'text', id: 'label', rect });
    assert.deepEqual(text.areas.at(-1), {
        id: 'label', type: 'text', source: 1, background: false,
        rect: RECT, text: '{{source.description}}', style: { ...LEGACY_STYLE, boxFill: null },
    });
    rect.x = 700;
    text.areas.at(-1).rect.y = 300;
    image.areas[0].points[0].x = 1;
    image.sources[0].description = 'Changed';
    assert.deepEqual(original, snapshot);
    assert.equal(text.sources[0].description, 'Ana Lima');
    assert.equal(text.areas.at(-1).rect.x, RECT.x);
});

test('explicit reused image source is allowed but gaps never normalize silently', () => {
    const original = documentWith([['image', 1], ['image', 2]]);
    const reused = draw(original, 'image', 'reused', { source: 1 });
    assert.deepEqual(reused.areas.map(area => area.source), [1, 2, 1]);
    assert.equal(reused.sources.length, 2);
    invalid(() => draw(original, 'image', 'gap', { source: 4 }));
    const edited = clone(original);
    edited.areas[0].source = 2;
    invalid(() => validateDocument(edited));
    invalid(() => nextImageSource(edited));
    invalid(() => sourceOptions(edited, 'area-1'));
    invalid(() => draw(edited, 'text', 'label'));
});

test('the source and area ceilings are 128 and no picker offers source 129', () => {
    const full = documentWith(Array.from({ length: 128 }, (_, index) => ['image', index + 1]));
    assert.deepEqual(sourceOptions(full, 'area-128'), [127, 128]);
    invalid(() => nextImageSource(full));
    invalid(() => draw(full, 'image', 'overflow'));
    invalid(() => draw(full, 'image', 'overflow-reused', { source: 1 }));
    invalid(() => draw(full, 'text', 'overflow-text'));
    const lateFirst = documentWith([
        ...Array.from({ length: 126 }, (_, index) => ['text', index + 1]),
        ['image', 128], ['image', 127],
    ]);
    assert.deepEqual(sourceOptions(lateFirst, 'area-128'), []);
});

test('drawn rectangles permit integer partial overlap and exact coordinate bounds', () => {
    for (const type of ['image', 'text']) {
        const doc = draw(createEmptyDocument(), type, 'partial', {
            rect: { x: -10, y: -10, width: 11, height: 11 },
        });
        assert.equal(validateDocument(doc), doc);
        assert.equal(draw(createEmptyDocument(1, 1), type, 'pixel', {
            rect: { x: 0, y: 0, width: 1, height: 1 },
        }).areas.length, 1);
    }
    const span = draw(createEmptyDocument(), 'image', 'span', {
        rect: { x: -10000, y: -10000, width: 20000, height: 20000 },
    });
    assert.deepEqual(span.areas[0].points[2], { x: 10000, y: 10000 });
});

for (const [name, rect] of [
    ['zero width', { ...RECT, width: 0 }],
    ['subpixel width', { ...RECT, width: 0.5 }],
    ['fractional origin', { ...RECT, x: 0.5 }],
    ['nonfinite origin', { ...RECT, x: NaN }],
    ['infinite height', { ...RECT, height: Infinity }],
    ['reverse drawing', { ...RECT, width: -10, height: -20 }],
    ['left bound', { ...RECT, x: -10001, width: 10002 }],
    ['right corner bound', { ...RECT, width: 10000 }],
    ['bottom corner bound', { ...RECT, height: 10000 }],
    ['edge-only overlap', { x: -100, y: 10, width: 100, height: 100 }],
    ['corner-only overlap', { x: -100, y: -100, width: 100, height: 100 }],
    ['fully outside', { x: 901, y: 10, width: 100, height: 100 }],
]) {
    test(`drawing rejects ${name} for both area types without changing the document`, () => {
        const doc = createEmptyDocument();
        for (const type of ['image', 'text']) {
            invalid(() => draw(doc, type, 'bad', { rect }));
            assert.deepEqual(doc, createEmptyDocument());
        }
    });
}

test('reverse drag normalization belongs to the controller, not addDrawnArea', () => {
    const from = { x: 150, y: 110 };
    const to = { x: 20, y: 30 };
    const rect = { x: Math.min(from.x, to.x), y: Math.min(from.y, to.y),
        width: Math.abs(to.x - from.x), height: Math.abs(to.y - from.y) };
    const doc = draw(createEmptyDocument(), 'image', 'normalized', { rect });
    assert.deepEqual(doc.areas[0].points, rectPoints(rect));
});

test('drawing rejects invalid IDs, types, image sources and unknown input fields', () => {
    const empty = createEmptyDocument();
    for (const source of [0, -1, 129, 1.5, NaN, Infinity, '1']) {
        invalid(() => draw(empty, 'image', 'bad-source', { source }));
    }
    for (const id of ['', 'a'.repeat(65), '<script>', null]) invalid(() => draw(empty, 'image', id));
    invalid(() => draw(empty, 'video', 'video'));
    invalid(() => draw(empty, 'image', 'extra', { url: 'https://invalid.test/image.png' }));
    invalid(() => draw(empty, 'image', 'extra-rect', { rect: { ...RECT, extra: true } }));
    const existing = draw(empty, 'image', 'duplicate');
    invalid(() => draw(existing, 'text', 'duplicate'));
    invalid(() => addDrawnArea(empty, null));
    const input = { type: 'image', id: 'getter', rect: RECT };
    Object.defineProperty(input, 'source', { enumerable: true, get() { assert.fail('getter executed'); } });
    invalid(() => addDrawnArea(empty, input));
});

test('boxFill is optional, nullable or exactly hex6 and does not affect text layout', () => {
    for (const boxFill of [null, '#ffffff', '#aB12Cd']) {
        const doc = draw(createEmptyDocument(), 'text', 'label');
        doc.areas[0].style.boxFill = boxFill;
        assert.equal(validateDocument(doc), doc);
        assert.deepEqual(decodeDocument(encodeDocument(doc)), doc);
        const measure = (text, size) => text.length * size;
        assert.deepEqual(layoutText('Test', RECT, doc.areas[0].style, measure),
            layoutText('Test', RECT, LEGACY_STYLE, measure));
    }
});

test('unknown fonts, boxFill URLs and every other added style key remain rejected', () => {
    const original = draw(createEmptyDocument(), 'text', 'label');
    for (const boxFill of ['#fff', '#ffffff00', 'red', 0, {},
        'https://invalid.test/box.png', 'url(https://invalid.test/box.png)', 'file:///tmp/box.png']) {
        const doc = clone(original);
        doc.areas[0].style.boxFill = boxFill;
        invalid(() => validateDocument(doc));
        invalid(() => decodeDocument(JSON.stringify(doc)));
    }
    for (const font of ['unknown', 'toString', 'https://invalid.test/font.woff', ['sans']]) {
        const doc = clone(original);
        doc.areas[0].style.font = font;
        invalid(() => validateDocument(doc));
    }
    const extra = clone(original);
    extra.areas[0].style.boxImage = 'https://invalid.test/box.png';
    invalid(() => validateDocument(extra));
});

test('the supplied CSV roundtrips exactly at 1080x1114 and 1200x1200', () => {
    for (const [width, height] of [[1080, 1114], [1200, 1200]]) {
        const doc = importImageCsv(CSV, width, height);
        assert.equal(doc.scene, 'custom');
        assert.equal(doc.width, width);
        assert.equal(doc.height, height);
        assert.deepEqual(doc.areas.map(area => area.id), ['image-1', 'image-2', 'image-3']);
        assert.ok(doc.areas.every(area => area.type === 'image' && area.background === false));
        assert.deepEqual(doc.sources, COLORS.map((color, index) => ({ slot: index + 1,
            description: `Source ${index + 1}`, color })));
        assert.deepEqual(doc.areas[0].points, [
            { x: 105, y: 840 }, { x: 274, y: 840 }, { x: 274, y: 1018 }, { x: 105, y: 1018 },
        ]);
        assert.equal(exportImageCsv(doc), CSV);
        assert.ok(!exportImageCsv(doc).endsWith('\n'));
        assert.deepEqual(decodeDocument(encodeDocument(doc)), doc);
    }
});

test('CSV accepts CRLF or one trailing newline and preserves background and off-canvas geometry', () => {
    assert.equal(exportImageCsv(importImageCsv(CSV.replaceAll('\n', '\r\n') + '\r\n', 1080, 1114)), CSV);
    assert.equal(exportImageCsv(importImageCsv(CSV + '\n', 1080, 1114)), CSV);
    const partial = `${HEADER}\n1,1,-10000,-10000,10000,-10000,10000,10000,-10000,10000,1`;
    const doc = importImageCsv(partial, 1200, 1200);
    assert.equal(doc.areas[0].background, true);
    assert.equal(exportImageCsv(doc), partial);
});

test('CSV export reindexes by image list order rather than IDs and never changes the model', () => {
    const doc = importImageCsv(CSV, 1080, 1114);
    doc.areas.reverse();
    doc.areas[0].id = 'arbitrary-id';
    const before = clone(doc);
    const exported = exportImageCsv(doc);
    assert.deepEqual(exported.split('\n').slice(1).map(line => line.split(',').slice(0, 2)),
        [['1', '3'], ['2', '2'], ['3', '1']]);
    assert.deepEqual(doc, before);
    assert.equal(exportImageCsv(importImageCsv(exported, 1080, 1114)), exported);
});

for (const [name, csv] of [
    ['empty caption', ''], ['whitespace caption', '  \n'], ['header only', HEADER],
    ['header with empty row', HEADER + '\n'],
    ['changed header', CSV.replace('TLx', 'tlx')],
    ['header whitespace', ' ' + CSV],
    ['missing header field', CSV.replace('Area,Source,', 'Area,')],
    ['extra header field', CSV.replace('Background\n', 'Background,Other\n')],
    ['blank interior row', CSV.replace('\n2,', '\n\n2,')],
    ['multiple trailing newlines', CSV + '\n\n'],
    ['extra column', CSV + ',0'],
    ['missing column', CSV.slice(0, -2)],
    ['repeated index', changeField(CSV, 2, 0, '1')],
    ['gapped index', changeField(CSV, 2, 0, '3')],
    ['permuted indices', changeField(changeField(CSV, 1, 0, '2'), 2, 0, '1')],
    ['zero source', changeField(CSV, 1, 1, '0')],
    ['negative source', changeField(CSV, 1, 1, '-1')],
    ['source above 128', changeField(CSV, 1, 1, '129')],
    ['gapped references', changeField(CSV, 2, 1, '3')],
    ['background above 1', changeField(CSV, 1, 10, '2')],
    ['negative background', changeField(CSV, 1, 10, '-1')],
    ['coordinate above bound', changeField(CSV, 1, 2, '10001')],
    ['coordinate below bound', changeField(CSV, 1, 2, '-10001')],
]) {
    test(`CSV rejects ${name}`, () => invalid(() => importImageCsv(csv, 1080, 1114)));
}

test('CSV rejects NaN, unknown or noninteger values in every numeric field', () => {
    for (let field = 0; field < 11; field++) {
        for (const value of ['NaN', 'Infinity', 'undefined', 'unknown', '', '1.5', '1e2', '0x10', ' 1', '1 ', '+1',
            '9007199254740993', '9'.repeat(400)]) {
            invalid(() => importImageCsv(changeField(CSV, 1, field, value), 1080, 1114));
        }
    }
});

test('CSV rejects nonconvex, degenerate, fully outside and edge/corner-only geometry via SAT', () => {
    for (const coordinates of [
        '-100,10,0,10,0,110,-100,110',
        '-100,-100,0,-100,0,0,-100,0',
        '-20,-40,5,-15,-15,5,-40,-20',
        '-20,-20,10,-10,-10,10,-30,0',
        '1200,0,1300,0,1300,100,1200,100',
        '0,0,100,100,100,0,0,100',
        '0,0,100,0,50,20,0,100',
        '0,0,100,0,100,0,0,100',
    ]) invalid(() => importImageCsv(`${HEADER}\n1,1,${coordinates},0`, 1200, 1200));
    invalid(() => importImageCsv(CSV, 100, 100));
    invalid(() => importImageCsv(CSV, 10001, 1200));
    invalid(() => importImageCsv(CSV, 10000, 4001));
    for (const dimension of [undefined, null, '1200', 1200.5, NaN, Infinity, 0, -1]) {
        invalid(() => importImageCsv(CSV, dimension, 1200));
        invalid(() => importImageCsv(CSV, 1200, dimension));
    }
});

test('CSV accepts exactly 128 rows and rejects 129, with sources and area indices bounded', () => {
    const rows = Array.from({ length: 128 }, (_, index) =>
        `${index + 1},${index + 1},20,30,120,30,120,110,20,110,0`);
    const csv = [HEADER, ...rows].join('\n');
    const doc = importImageCsv(csv, 1200, 1200);
    assert.equal(doc.sources.length, 128);
    assert.equal(doc.areas.length, 128);
    assert.equal(exportImageCsv(doc), csv);
    invalid(() => importImageCsv(csv + '\n129,1,20,30,120,30,120,110,20,110,0', 1200, 1200));
});

test('CSV has an inclusive 64 Ki-character limit before parsing or numeric allocation', () => {
    const row = `${HEADER}\n1,1,20,30,120,30,120,110,20,110,0`;
    const limit = 64 * 1024;
    const padded = row.replace('\n1,', `\n${'0'.repeat(limit - row.length)}1,`);
    assert.equal(padded.length, limit);
    assert.equal(importImageCsv(padded, 1200, 1200).areas.length, 1);
    invalid(() => importImageCsv(padded + '\n', 1200, 1200));
    for (const csv of [null, undefined, {}, []]) invalid(() => importImageCsv(csv, 1200, 1200));
});

test('CSV refuses any text with an explicit Portuguese error instead of dropping it', () => {
    for (const doc of [createScene(), createScene('chat'), draw(createEmptyDocument(), 'text', 'only-text'),
        draw(importImageCsv(CSV, 1080, 1114), 'text', 'label', { source: 2 })]) {
        assert.throws(() => exportImageCsv(doc), /CSV.*não suporta áreas de texto.*JSON/u);
    }
    const invalidDoc = importImageCsv(CSV, 1080, 1114);
    invalidDoc.areas[0].source = 4;
    invalid(() => exportImageCsv(invalidDoc));
});