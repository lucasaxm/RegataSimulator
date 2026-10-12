import test from 'node:test';
import assert from 'node:assert/strict';
import * as model from '../model.mjs';

const {
    FONT_FAMILIES, clone, rectPoints, createScene, resolveText, layoutText,
    validateDocument, layerOrder, moveArea, moveCorner, encodeDocument,
    decodeDocument, viewToImage, imageToView, zoomAt,
} = model;
const segmenter = new Intl.Segmenter('pt', { granularity: 'grapheme' });
const graphemes = text => Array.from(segmenter.segment(text), item => item.segment);
const measure = (text, size) => graphemes(text).length * size;
const style = (overrides = {}) => ({ ...createScene().areas[1].style, ...overrides });
const rect = (width, height) => ({ x: 0, y: 0, width, height });
const invalid = (fn, message = /inválid[oa]/u) => assert.throws(fn, error => error instanceof Error && message.test(error.message));

function imageDoc(points) {
    const doc = createScene('chat');
    doc.areas = [doc.areas[0]];
    doc.areas[0].points = points;
    return doc;
}

function approximately(actual, expected) {
    assert.ok(Math.abs(actual - expected) < 1e-9, `${actual} differs from ${expected}`);
}

test('public exports are exactly the agreed browser-independent API', () => {
    assert.deepEqual(Object.keys(model).sort(), [
        'FONT_FAMILIES', 'clone', 'rectPoints', 'createScene', 'resolveText', 'layoutText',
        'validateDocument', 'layerOrder', 'moveArea', 'moveCorner', 'encodeDocument',
        'decodeDocument', 'viewToImage', 'imageToView', 'zoomAt',
    ].sort());
    assert.deepEqual(FONT_FAMILIES, { sans: 'Arial', serif: 'Georgia', mono: 'Courier New' });
    assert.ok(Object.isFrozen(FONT_FAMILIES));
});

test('captions scene uses only synthetic samples, three quads and three text labels', () => {
    const doc = createScene();
    assert.equal(validateDocument(doc), doc);
    assert.equal(doc.format, 'regata-creator-spike');
    assert.equal(doc.version, 1);
    assert.equal(doc.width, 900);
    assert.equal(doc.height, 620);
    assert.equal(doc.scene, 'captions');
    assert.deepEqual(doc.sources, [
        { slot: 1, description: 'Ana Lima', color: '#70b9cd' },
        { slot: 2, description: 'Bruno do bairro', color: '#e3b56a' },
        { slot: 3, description: 'João com uma descrição muito mais comprida', color: '#b298d5' },
    ]);
    assert.equal(doc.areas.length, 6);
    const images = doc.areas.filter(area => area.type === 'image');
    const texts = doc.areas.filter(area => area.type === 'text');
    assert.deepEqual(images.map(area => area.source), [1, 2, 3]);
    assert.deepEqual(texts.map(area => area.source), [1, 2, 3]);
    assert.deepEqual(images.map(area => area.points[0]), [{ x: 80, y: 250 }, { x: 355, y: 250 }, { x: 630, y: 250 }]);
    assert.notDeepEqual(images[0].points, rectPoints({ x: 80, y: 250, width: 200, height: 200 }));
    for (const area of texts) {
        assert.equal(area.rect.y, 465);
        assert.equal(area.rect.width, 250);
        assert.equal(area.rect.height, 100);
        assert.equal(area.text, 'Candidato {{source.description}}');
        assert.deepEqual(area.style, {
            font: 'sans', size: 34, minSize: 14, fill: '#ffffff', outline: '#000000',
            outlineWidth: 0, align: 'center', lineHeight: 1.15, weight: 'normal', overflow: 'shrink',
        });
    }
    assert.ok(!JSON.stringify(doc).includes('http'));
    const second = createScene();
    second.areas[1].style.fill = '#123456';
    assert.equal(doc.areas[1].style.fill, '#ffffff');
    assert.equal(second.areas[3].style.fill, '#ffffff');
});

test('chat scene binds only source 1 while retaining all three declared samples', () => {
    const doc = createScene('chat');
    assert.equal(validateDocument(doc), doc);
    assert.equal(doc.sources.length, 3);
    assert.equal(doc.areas.length, 2);
    assert.deepEqual(doc.areas[0].points, rectPoints({ x: 85, y: 150, width: 110, height: 110 }));
    assert.deepEqual(doc.areas[1].rect, { x: 225, y: 145, width: 580, height: 160 });
    assert.equal(doc.areas[1].text, '{{source.description}} entrou no grupo');
    assert.equal(doc.areas[1].style.align, 'left');
    assert.equal(doc.areas[1].style.fill, '#70b9cd');
    assert.equal(doc.areas[1].style.size, 48);
    invalid(() => createScene('unknown'));
    invalid(() => createScene(null));
});

test('clone is a deep structured clone and rectPoints returns TL, TR, BR, BL', () => {
    const original = { nested: { values: [1, 2] }, date: new Date('2026-01-01T00:00:00Z') };
    const copy = clone(original);
    assert.deepEqual(copy, original);
    copy.nested.values[0] = 3;
    assert.equal(original.nested.values[0], 1);
    assert.deepEqual(rectPoints({ x: -5, y: 7, width: 20, height: 10 }), [
        { x: -5, y: 7 }, { x: 15, y: 7 }, { x: 15, y: 17 }, { x: -5, y: 17 },
    ]);
    invalid(() => rectPoints({ x: 0, y: 0, width: 0, height: 10 }));
});

test('text resolution allows only the description token, whitespace and static plain text', () => {
    assert.equal(resolveText('Olá {{source.description}}!', 'Ana'), 'Olá Ana!');
    assert.equal(resolveText('{{ \t source.description \n }} / {{source.description}}', 'João'), 'João / João');
    assert.equal(resolveText('Texto estático'), 'Texto estático');
    assert.equal(resolveText(''), '');
    assert.equal(resolveText('{{source.description}}', ''), '');
    assert.equal(resolveText('<b>{{source.description}}</b>', '<img src=x onerror=alert(1)>'),
        '<b><img src=x onerror=alert(1)></b>');
    assert.equal(resolveText('{{source.description}} {{source.description}}', '{{evil}}'), '{{evil}} {{evil}}');
    assert.equal(resolveText('{{source.description}}', '${globalThis.process.exit()}'), '${globalThis.process.exit()}');
});

test('unknown, executable and malformed placeholders are rejected', () => {
    for (const template of [
        '{{evil}}', '{{source}}', '{{source.name}}', '{{source.description.toUpperCase()}}',
        '{{ source ["description"] }}', '{{Source.description}}', '{{source .description}}',
        '{{source.description}', '{source.description}}', '{{source.description',
        'source.description}}', '{{{source.description}}}', '{{source.description}}}',
        '{{}}', '{{source.description}}{{evil}}', 'a {{ b {{source.description}}',
    ]) invalid(() => resolveText(template, 'Ana'));
});

test('templates and descriptions have explicit bounds and missing descriptions fail only when needed', () => {
    assert.equal(resolveText('a'.repeat(512)), 'a'.repeat(512));
    assert.equal(resolveText('{{source.description}}', 'é'.repeat(512)), 'é'.repeat(512));
    assert.equal(resolveText('static', undefined), 'static');
    for (const description of [undefined, null, 1, {}, [], 'x'.repeat(513)]) {
        invalid(() => resolveText('{{source.description}}', description));
    }
    invalid(() => resolveText('a'.repeat(513), 'Ana'));
    invalid(() => resolveText(123, 'Ana'));
    const repeated = '{{source.description}}'.repeat(23);
    assert.equal(resolveText(repeated, 'a'.repeat(512)).length, 23 * 512);
});

test('missing and gapped slots fail; repeated and nonadjacent references use the union of both area types', () => {
    const missing = createScene('chat');
    missing.areas[0].source = 4;
    invalid(() => validateDocument(missing));
    const gap = createScene();
    gap.areas = gap.areas.filter(area => area.source !== 2);
    invalid(() => validateDocument(gap));
    const union = createScene();
    union.areas = [union.areas[4], union.areas[1], union.areas[2], union.areas[5]];
    assert.deepEqual(union.areas.map(area => area.source), [3, 1, 2, 3]);
    assert.equal(validateDocument(union), union);
    union.sources.reverse();
    assert.equal(validateDocument(union), union);
    const declarations = createScene('chat');
    declarations.sources[1].slot = 4;
    invalid(() => validateDocument(declarations));
    declarations.sources[1].slot = 1;
    invalid(() => validateDocument(declarations));
});

test('text-only source binding works without an image area', () => {
    const doc = createScene();
    doc.areas = doc.areas.filter(area => area.type === 'text');
    assert.equal(validateDocument(doc), doc);
    assert.equal(resolveText(doc.areas[2].text, doc.sources[2].description),
        'Candidato João com uma descrição muito mais comprida');
    assert.deepEqual(layerOrder(doc), ['template', 'text-1', 'text-2', 'text-3']);
    doc.areas = [doc.areas[0]];
    assert.equal(validateDocument(doc), doc);
    doc.areas[0].source = 2;
    invalid(() => validateDocument(doc));
});

test('all imported prototype-sensitive own keys are rejected recursively without pollution', () => {
    for (const key of ['__proto__', 'constructor', 'prototype']) {
        for (const location of ['root', 'source', 'area', 'point', 'style', 'rect']) {
            const doc = createScene();
            const target = {
                root: doc, source: doc.sources[0], area: doc.areas[0],
                point: doc.areas[0].points[0], style: doc.areas[1].style, rect: doc.areas[1].rect,
            }[location];
            Object.defineProperty(target, key, { value: { polluted: true }, enumerable: true });
            invalid(() => decodeDocument(JSON.stringify(doc)));
        }
    }
    assert.equal({}.polluted, undefined);
});

test('unknown fields, arbitrary image URLs, paths and editor state cannot enter documents', () => {
    for (const key of ['viewport', 'selection', 'imageURL', 'path', 'arbitrary']) {
        const doc = createScene();
        doc[key] = 'untrusted';
        invalid(() => validateDocument(doc));
        invalid(() => encodeDocument(doc));
    }
    for (const location of ['source', 'image', 'text', 'point', 'rect', 'style']) {
        const doc = createScene();
        const target = {
            source: doc.sources[0], image: doc.areas[0], text: doc.areas[1],
            point: doc.areas[0].points[0], rect: doc.areas[1].rect, style: doc.areas[1].style,
        }[location];
        target.url = 'file:///etc/passwd';
        invalid(() => decodeDocument(JSON.stringify(doc)));
    }
});

test('direct validation rejects cycles, getters, sparse arrays and custom prototypes', () => {
    const cycle = createScene();
    cycle.areas[0].points[0].cycle = cycle;
    invalid(() => validateDocument(cycle));
    const getter = createScene();
    Object.defineProperty(getter, 'width', { enumerable: true, get() { assert.fail('getter must not execute'); } });
    invalid(() => validateDocument(getter));
    const sparse = createScene();
    delete sparse.areas[0].points[1];
    invalid(() => validateDocument(sparse));
    const inherited = createScene();
    Object.setPrototypeOf(inherited.sources[0], { extra: true });
    invalid(() => validateDocument(inherited));
    const symbol = createScene();
    symbol[Symbol('untrusted')] = true;
    invalid(() => validateDocument(symbol));
    assert.equal(validateDocument(Object.assign(Object.create(null), createScene())).format, 'regata-creator-spike');
});

const invalidDocuments = [
    ['format', doc => { doc.format = 'other'; }],
    ['version', doc => { doc.version = 2; }],
    ['scene', doc => { doc.scene = 'other'; }],
    ['zero width', doc => { doc.width = 0; }],
    ['negative height', doc => { doc.height = -1; }],
    ['fractional dimensions', doc => { doc.width = 900.5; }],
    ['oversized dimensions', doc => { doc.height = 10001; }],
    ['pixel limit', doc => { doc.width = 10000; doc.height = 4001; }],
    ['nonfinite dimensions', doc => { doc.width = Infinity; }],
    ['no sources', doc => { doc.sources = []; }],
    ['too many sources', doc => { doc.sources = Array(129).fill(doc.sources[0]); }],
    ['description bound', doc => { doc.sources[0].description = 'a'.repeat(513); }],
    ['description type', doc => { doc.sources[0].description = null; }],
    ['source color', doc => { doc.sources[0].color = '#fff'; }],
    ['no areas', doc => { doc.areas = []; }],
    ['too many areas', doc => { doc.areas = Array(129).fill(doc.areas[0]); }],
    ['duplicate id', doc => { doc.areas[1].id = doc.areas[0].id; }],
    ['empty id', doc => { doc.areas[0].id = ''; }],
    ['id bound', doc => { doc.areas[0].id = 'a'.repeat(65); }],
    ['unsafe id', doc => { doc.areas[0].id = '<script>'; }],
    ['nonascii id', doc => { doc.areas[0].id = 'João'; }],
    ['area type', doc => { doc.areas[0].type = 'video'; }],
    ['background type', doc => { doc.areas[0].background = 1; }],
    ['fractional source', doc => { doc.areas[0].source = 1.5; }],
    ['unknown font', doc => { doc.areas[1].style.font = 'Comic Sans MS'; }],
    ['prototype font', doc => { doc.areas[1].style.font = 'toString'; }],
    ['nonstring font', doc => { doc.areas[1].style.font = ['sans']; }],
    ['small font', doc => { doc.areas[1].style.size = 7; }],
    ['large font', doc => { doc.areas[1].style.size = 121; }],
    ['nonfinite font', doc => { doc.areas[1].style.size = NaN; }],
    ['small minimum', doc => { doc.areas[1].style.minSize = 7; }],
    ['minimum above maximum', doc => { doc.areas[1].style.minSize = 35; }],
    ['short line height', doc => { doc.areas[1].style.lineHeight = 0.9; }],
    ['large line height', doc => { doc.areas[1].style.lineHeight = 2.1; }],
    ['weight', doc => { doc.areas[1].style.weight = '600'; }],
    ['alignment', doc => { doc.areas[1].style.align = 'justify'; }],
    ['overflow', doc => { doc.areas[1].style.overflow = 'ellipsis'; }],
    ['fill', doc => { doc.areas[1].style.fill = 'red'; }],
    ['outline', doc => { doc.areas[1].style.outline = '#00000000'; }],
    ['negative outline', doc => { doc.areas[1].style.outlineWidth = -1; }],
    ['large outline', doc => { doc.areas[1].style.outlineWidth = 9; }],
    ['text bound', doc => { doc.areas[1].text = 'a'.repeat(513); }],
    ['text token', doc => { doc.areas[1].text = '{{unknown}}'; }],
    ['text dimension', doc => { doc.areas[1].rect.width = 0; }],
    ['text coordinate bound', doc => { doc.areas[1].rect.x = -10001; }],
    ['text dimension bound', doc => { doc.areas[1].rect.height = 10001; }],
    ['nonfinite rectangle', doc => { doc.areas[1].rect.y = NaN; }],
];
for (const [name, mutate] of invalidDocuments) {
    test(`document rejects ${name}`, () => {
        const doc = createScene();
        mutate(doc);
        invalid(() => validateDocument(doc));
    });
}

test('inclusive document, style and ID bounds are accepted', () => {
    const doc = createScene('chat');
    doc.width = 10000;
    doc.height = 4000;
    doc.areas[0].id = 'a'.repeat(64);
    doc.areas[1].style = style({ size: 120, minSize: 8, lineHeight: 2, outlineWidth: 8,
        font: 'mono', weight: 'bold', align: 'right', overflow: 'clip', fill: '#Ab12Cd' });
    assert.equal(validateDocument(doc), doc);
});

test('layout wraps words, explicit newlines, CRLF, blank lines and trailing newlines', () => {
    const result = layoutText('Ana Lima\nJoão\r\n\nFim\n', rect(48, 100),
        style({ size: 10, minSize: 10, lineHeight: 1 }), measure);
    assert.deepEqual(result, {
        lines: ['Ana', 'Lima', 'João', '', 'Fim', ''], fontSize: 10, lineHeight: 10, overflow: false,
    });
});

test('layout selects the highest fitting integer size using width and height', () => {
    const result = layoutText('abcd', rect(48, 28), style({ size: 20, minSize: 8, lineHeight: 1 }), measure);
    assert.deepEqual(result, { lines: ['abcd'], fontSize: 10, lineHeight: 10, overflow: false });
    const heightOnly = layoutText('a\nb\nc', rect(100, 38), style({ size: 20, minSize: 8, lineHeight: 1 }), measure);
    assert.equal(heightOnly.fontSize, 10);
    assert.equal(heightOnly.lineHeight, 10);
    assert.equal(heightOnly.overflow, false);
    const fractional = layoutText('abc', rect(100, 100), style({ size: 19.9, minSize: 8.5 }), measure);
    assert.equal(fractional.fontSize, 19);
});

test('padding reserves four pixels plus outline on every side', () => {
    const normal = layoutText('abcd', rect(48, 40), style({ size: 10, minSize: 8, lineHeight: 1 }), measure);
    const outlined = layoutText('abcd', rect(48, 24), style({ size: 10, minSize: 8, lineHeight: 1, outlineWidth: 4 }), measure);
    assert.equal(normal.fontSize, 10);
    assert.deepEqual(outlined, { lines: ['abcd'], fontSize: 8, lineHeight: 8, overflow: false });
    assert.equal(layoutText('a', rect(7, 7), style({ size: 8, minSize: 8 }), measure).overflow, true);
});

test('long words split without losing accented, combining, surrogate or ZWJ graphemes', () => {
    const input = 'áe\u0301👨‍👩‍👧‍👦👩🏽‍🚀🇧🇷Z';
    const result = layoutText(input, rect(18, 200), style({ size: 10, minSize: 10, lineHeight: 1 }), measure);
    assert.deepEqual(result.lines, graphemes(input));
    assert.equal(result.lines.join(''), input);
    assert.equal(result.overflow, false);
    const long = 'pneumoultramicroscopicossilicovulcanoconiótico';
    const split = layoutText(long, rect(58, 200), style({ size: 10, minSize: 10, lineHeight: 1 }), measure);
    assert.equal(split.lines.join(''), long);
    assert.ok(split.lines.every(line => graphemes(line).length <= 5));
});

test('an oversized indivisible grapheme is retained and explicitly overflows', () => {
    const family = '👨‍👩‍👧‍👦';
    const result = layoutText(family, rect(9, 40), style({ size: 10, minSize: 8, lineHeight: 1 }), measure);
    assert.deepEqual(result, { lines: [family], fontSize: 8, lineHeight: 8, overflow: true });
});

test('minimum-size failure preserves all words and lines without truncation or ellipsis', () => {
    const input = 'abcdefghij\nklmnopqrst';
    const result = layoutText(input, rect(28, 16), style({ size: 12, minSize: 8, lineHeight: 1 }), measure);
    assert.equal(result.fontSize, 8);
    assert.equal(result.overflow, true);
    assert.equal(result.lines.join(''), input.replace('\n', ''));
    assert.ok(result.lines.length > 1);
    assert.ok(!result.lines.some(line => line.includes('…')));
    const words = layoutText('um dois três quatro', rect(56, 16), style({ size: 12, minSize: 8 }), measure);
    assert.equal(words.lines.join(' '), 'um dois três quatro');
    assert.equal(words.overflow, true);
});

test('clip preserves maximum size and reports both height and width overflow', () => {
    const result = layoutText('a\nb\nc', rect(100, 38),
        style({ size: 20, minSize: 8, lineHeight: 1, overflow: 'clip' }), measure);
    assert.deepEqual(result, { lines: ['a', 'b', 'c'], fontSize: 20, lineHeight: 20, overflow: true });
    const indivisible = layoutText('👩‍🚀', rect(10, 100), style({ size: 20, minSize: 8, overflow: 'clip' }), measure);
    assert.equal(indivisible.fontSize, 20);
    assert.equal(indivisible.overflow, true);
    assert.deepEqual(indivisible.lines, ['👩‍🚀']);
});

test('empty text is valid and measurement receives resolved font families and weights', () => {
    const empty = layoutText('', rect(1, 1), style(), () => assert.fail('empty text needs no measurement'));
    assert.deepEqual(empty, { lines: [], fontSize: 34, lineHeight: 34 * 1.15, overflow: false });
    for (const [font, family] of Object.entries(FONT_FAMILIES)) {
        const result = layoutText('Oi', rect(200, 200), style({ font, weight: 'bold' }), (text, size, receivedFont, weight) => {
            assert.equal(receivedFont, family);
            assert.equal(weight, 'bold');
            return measure(text, size);
        });
        assert.equal(result.overflow, false);
    }
});

test('layout rejects unbounded text, invalid rectangles, styles and measurement results', () => {
    invalid(() => layoutText('a'.repeat(16385), rect(100, 100), style(), measure));
    invalid(() => layoutText(null, rect(100, 100), style(), measure));
    invalid(() => layoutText('a', rect(0, 100), style(), measure));
    invalid(() => layoutText('a', rect(Infinity, 100), style(), measure));
    invalid(() => layoutText('a', rect(10001, 100), style(), measure));
    invalid(() => layoutText('a', rect(100, 100), style({ font: 'unknown' }), measure));
    invalid(() => layoutText('a', rect(100, 100), style(), null));
    for (const width of [NaN, Infinity, -1, '10', undefined]) {
        invalid(() => layoutText('a', rect(100, 100), style(), () => width));
    }
});

test('maximum-length layout is bounded, preserves text and does not loop on a tiny box', () => {
    const input = 'a'.repeat(16384);
    let calls = 0;
    const result = layoutText(input, rect(9, 9), style({ size: 8, minSize: 8 }), (text, size) => {
        calls++;
        return text.length * size;
    });
    assert.equal(result.overflow, true);
    assert.equal(result.lines.join(''), input);
    assert.ok(calls < 40, `measurement cache should bound repeated chunks: ${calls}`);
});

test('layer order preserves B2/B4, template, F1/F3 across image and text types', () => {
    const doc = createScene();
    doc.areas = doc.areas.slice(0, 4);
    doc.areas.forEach((area, index) => {
        area.id = `area-${index + 1}`;
        area.background = index % 2 === 1;
    });
    assert.deepEqual(layerOrder(doc), ['area-2', 'area-4', 'template', 'area-1', 'area-3']);
    doc.areas[0].background = true;
    doc.areas[1].background = false;
    assert.deepEqual(layerOrder(doc), ['area-1', 'area-4', 'template', 'area-2', 'area-3']);
});

test('negative and off-canvas image coordinates with positive overlap remain valid', () => {
    for (const points of [
        rectPoints({ x: -50, y: -50, width: 100, height: 100 }),
        rectPoints({ x: 890, y: 610, width: 100, height: 100 }),
        rectPoints({ x: -10000, y: -10000, width: 20000, height: 20000 }),
        [{ x: -10, y: 0 }, { x: 0, y: -10 }, { x: 10, y: 0 }, { x: 0, y: 10 }],
    ]) assert.equal(validateDocument(imageDoc(points)).areas[0].points, points);
    const clockwise = rectPoints({ x: 10, y: 10, width: 100, height: 100 }).reverse();
    assert.equal(validateDocument(imageDoc(clockwise)).areas[0].points, clockwise);
});

test('image geometry rejects edge/corner touching, disjoint SAT overlap and malformed polygons', () => {
    for (const points of [
        rectPoints({ x: -100, y: 20, width: 100, height: 100 }),
        rectPoints({ x: 900, y: 20, width: 100, height: 100 }),
        rectPoints({ x: 20, y: 620, width: 100, height: 100 }),
        rectPoints({ x: -100, y: -100, width: 100, height: 100 }),
        // Bounding boxes overlap the canvas, but the diagonal separates them.
        [{ x: -20, y: -40 }, { x: 5, y: -15 }, { x: -15, y: 5 }, { x: -40, y: -20 }],
        // Diagonal corner-only contact at (0, 0).
        [{ x: -20, y: -20 }, { x: 10, y: -10 }, { x: -10, y: 10 }, { x: -30, y: 0 }],
        [{ x: 0, y: 0 }, { x: 100, y: 100 }, { x: 100, y: 0 }, { x: 0, y: 100 }],
        [{ x: 0, y: 0 }, { x: 100, y: 0 }, { x: 50, y: 20 }, { x: 0, y: 100 }],
        [{ x: 0, y: 0 }, { x: 50, y: 0 }, { x: 100, y: 0 }, { x: 0, y: 100 }],
        [{ x: 0, y: 0 }, { x: 100, y: 0 }, { x: 100, y: 0 }, { x: 0, y: 100 }],
        [{ x: 0.5, y: 0 }, { x: 100, y: 0 }, { x: 100, y: 100 }, { x: 0, y: 100 }],
        [{ x: -10001, y: 0 }, { x: 100, y: 0 }, { x: 100, y: 100 }, { x: 0, y: 100 }],
        [{ x: NaN, y: 0 }, { x: 100, y: 0 }, { x: 100, y: 100 }, { x: 0, y: 100 }],
        [{ x: 0, y: 0 }, { x: 100, y: 0 }, { x: 100, y: 100 }],
    ]) invalid(() => validateDocument(imageDoc(points)));
});

test('text rectangles accept partial negative overlap and reject edge/corner-only contact', () => {
    for (const rectangle of [
        { x: -20, y: -20, width: 40, height: 40 },
        { x: 899.5, y: 619.5, width: 1, height: 1 },
    ]) {
        const doc = createScene('chat');
        doc.areas = [doc.areas[1]];
        doc.areas[0].rect = rectangle;
        assert.equal(validateDocument(doc), doc);
    }
    for (const rectangle of [
        { x: -100, y: 10, width: 100, height: 100 },
        { x: 900, y: 10, width: 100, height: 100 },
        { x: 10, y: 620, width: 100, height: 100 },
        { x: -100, y: -100, width: 100, height: 100 },
        { x: -101, y: 10, width: 100, height: 100 },
    ]) {
        const doc = createScene('chat');
        doc.areas[1].rect = rectangle;
        invalid(() => validateDocument(doc));
    }
});

test('moving either area type clones nested state and quantizes without clamping', () => {
    const doc = createScene('chat');
    const original = clone(doc);
    const image = moveArea(doc.areas[0], -100.4, 10.6);
    assert.deepEqual(image.points[0], { x: -15, y: 161 });
    assert.deepEqual(doc, original);
    const text = moveArea(doc.areas[1], -300.4, 5.6);
    assert.deepEqual(text.rect, { x: -75, y: 151, width: 580, height: 160 });
    text.style.fill = '#123456';
    assert.equal(doc.areas[1].style.fill, '#70b9cd');
    doc.areas[0] = image;
    doc.areas[1] = text;
    assert.equal(validateDocument(doc), doc);
    assert.deepEqual(moveArea(original.areas[0], -20000, 0).points[0], { x: -19915, y: 150 });
    invalid(() => moveArea(original.areas[0], NaN, 1));
    invalid(() => moveArea(original.areas[1], 1, Infinity));
});

test('all four text corners resize axis-aligned rectangles while keeping the opposite corner fixed', () => {
    const area = createScene('chat').areas[1];
    area.rect = { x: 10, y: 20, width: 100, height: 60 };
    const original = clone(area);
    const targets = [{ x: 5.4, y: 15.4 }, { x: 115.4, y: 15.4 }, { x: 115.4, y: 85.4 }, { x: 5.4, y: 85.4 }];
    for (let index = 0; index < 4; index++) {
        const resized = moveCorner(area, index, targets[index]);
        const points = rectPoints(resized.rect);
        assert.deepEqual(points[(index + 2) % 4], rectPoints(area.rect)[(index + 2) % 4]);
        assert.deepEqual(points[index], { x: Math.round(targets[index].x), y: Math.round(targets[index].y) });
        assert.equal(resized.rect.width, 105);
        assert.equal(resized.rect.height, 65);
        assert.deepEqual(area, original);
    }
    const crossed = moveCorner(area, 0, { x: 130, y: 100 });
    assert.deepEqual(crossed.rect, { x: 110, y: 80, width: 20, height: 20 });
    assert.ok(rectPoints(crossed.rect).some(point => point.x === 110 && point.y === 80));
    const collapsed = moveCorner(area, 0, { x: 110, y: 80 });
    assert.deepEqual(collapsed.rect, { x: 110, y: 80, width: 0, height: 0 });
});

test('image corner editing stays immutable, permits off-canvas points and leaves validity to caller', () => {
    const doc = createScene('chat');
    const area = doc.areas[0];
    const original = clone(area);
    const moved = moveCorner(area, 0, { x: -20.2, y: -10.4 });
    assert.deepEqual(moved.points[0], { x: -20, y: -10 });
    assert.deepEqual(moved.points.slice(1), area.points.slice(1));
    assert.deepEqual(area, original);
    doc.areas[0] = moved;
    assert.equal(validateDocument(doc), doc);
    doc.areas[0] = moveCorner(area, 0, area.points[2]);
    invalid(() => validateDocument(doc));
    for (const index of [-1, 4, 1.2, NaN, '0']) invalid(() => moveCorner(area, index, { x: 1, y: 1 }));
    invalid(() => moveCorner(area, 0, { x: Infinity, y: 0 }));
    const outside = moveCorner(area, 0, { x: -20000, y: -20000 });
    assert.equal(outside.points[0].x, -20000);
    doc.areas[0] = outside;
    invalid(() => validateDocument(doc));
});

test('document roundtrip contains only model data, without viewport or selection', () => {
    for (const name of ['captions', 'chat']) {
        const doc = createScene(name);
        const original = clone(doc);
        const encoded = encodeDocument(doc);
        assert.ok(encoded.includes('\n  "format":'));
        assert.ok(!encoded.includes('viewport') && !encoded.includes('selection'));
        const decoded = decodeDocument(encoded);
        assert.deepEqual(decoded, doc);
        assert.notEqual(decoded, doc);
        assert.equal(encodeDocument(decoded), encoded);
        assert.deepEqual(doc, original);
    }
});

test('decoding rejects malformed JSON and oversized UTF-8, not just oversized character counts', () => {
    for (const text of ['', '{', 'null', '[]', '"document"', '123', undefined]) invalid(() => decodeDocument(text));
    const base = JSON.stringify(createScene('chat'));
    assert.deepEqual(decodeDocument(base + ' '.repeat(128 * 1024 - Buffer.byteLength(base))), createScene('chat'));
    invalid(() => decodeDocument(base + ' '.repeat(128 * 1024 - Buffer.byteLength(base) + 1)));
    const multibyte = 'é'.repeat(70000);
    assert.ok(multibyte.length < 128 * 1024 && Buffer.byteLength(multibyte) > 128 * 1024);
    invalid(() => decodeDocument(multibyte));
});

test('large valid documents cannot be encoded into bundles that the decoder would reject', () => {
    const doc = createScene('chat');
    doc.sources = Array.from({ length: 128 }, (_, index) => ({ slot: index + 1,
        description: 'é'.repeat(512), color: '#123456' }));
    assert.equal(validateDocument(doc), doc);
    invalid(() => encodeDocument(doc));
});

test('viewport transforms are inverse, finite and independent of model coordinates', () => {
    const doc = createScene();
    const original = clone(doc);
    for (const view of [{ scale: 1, x: 0, y: 0 }, { scale: 0.15, x: -90, y: 37 }, { scale: 8, x: 250, y: -500 }]) {
        for (const point of [{ x: 0, y: 0 }, { x: -100, y: 1000 }, { x: 33.4, y: 12.7 }]) {
            const back = viewToImage(imageToView(point, view), view);
            approximately(back.x, point.x);
            approximately(back.y, point.y);
            const screen = imageToView(viewToImage(point, view), view);
            approximately(screen.x, point.x);
            approximately(screen.y, point.y);
        }
    }
    assert.deepEqual(viewToImage({ x: 10, y: 20 }), { x: 10, y: 20 });
    assert.deepEqual(imageToView({ x: 10, y: 20 }), { x: 10, y: 20 });
    assert.deepEqual(doc, original);
});

test('zoom is anchored at the screen point and clamps both scale extremes without mutation', () => {
    const view = { scale: 2, x: -100, y: 40 };
    const screen = { x: 245, y: 180 };
    const anchor = viewToImage(screen, view);
    for (const factor of [1, 1.5, 1e-10, 1e10, Number.MAX_VALUE]) {
        const zoomed = zoomAt(view, screen, factor);
        assert.ok(zoomed.scale >= 0.15 && zoomed.scale <= 8);
        const transformed = imageToView(anchor, zoomed);
        approximately(transformed.x, screen.x);
        approximately(transformed.y, screen.y);
        assert.notEqual(zoomed, view);
    }
    assert.equal(zoomAt(view, screen, 1e-10).scale, 0.15);
    assert.equal(zoomAt(view, screen, 1e10).scale, 8);
    assert.deepEqual(view, { scale: 2, x: -100, y: 40 });
    assert.deepEqual(screen, { x: 245, y: 180 });
});

test('viewport APIs reject invalid numeric inputs and nonfinite computed coordinates', () => {
    for (const view of [null, {}, { scale: 0, x: 0, y: 0 }, { scale: -1, x: 0, y: 0 },
        { scale: Infinity, x: 0, y: 0 }, { scale: 1, x: NaN, y: 0 }]) {
        invalid(() => viewToImage({ x: 0, y: 0 }, view));
        invalid(() => imageToView({ x: 0, y: 0 }, view));
        invalid(() => zoomAt(view, { x: 0, y: 0 }, 2));
    }
    for (const point of [null, {}, { x: '0', y: 0 }, { x: 0, y: Infinity }]) {
        invalid(() => viewToImage(point));
        invalid(() => imageToView(point));
        invalid(() => zoomAt({ scale: 1, x: 0, y: 0 }, point, 2));
    }
    for (const factor of [0, -1, NaN, Infinity, '2']) invalid(() => zoomAt({ scale: 1, x: 0, y: 0 }, { x: 1, y: 1 }, factor));
    invalid(() => imageToView({ x: Number.MAX_VALUE, y: 0 }, { scale: 8, x: 0, y: 0 }));
    invalid(() => viewToImage({ x: Number.MAX_VALUE, y: 0 }, { scale: 0.15, x: 0, y: 0 }));
});