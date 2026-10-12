// Pure, browser-independent spike model. No rendering, storage, or network effects.
export const FONT_FAMILIES = Object.freeze({
    sans: 'Arial',
    serif: 'Georgia',
    mono: 'Courier New',
});

const MAX_FIELD_LENGTH = 512;
const MAX_LAYOUT_LENGTH = 16384;
const MAX_DOCUMENT_BYTES = 128 * 1024;
const MAX_COORDINATE = 10000;
const FORBIDDEN_KEYS = new Set(['__proto__', 'constructor', 'prototype']);
const COLOR = /^#[0-9a-f]{6}$/i;
const ID = /^[a-z0-9_-]{1,64}$/i;
const SEGMENTER = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
const DEFAULT_VIEW = Object.freeze({ scale: 1, x: 0, y: 0 });

function requireThat(condition, message = 'Documento inválido.') {
    if (!condition) throw new Error(message);
}

function finite(value) {
    return typeof value === 'number' && Number.isFinite(value);
}

function boundedString(value, limit = MAX_FIELD_LENGTH) {
    return typeof value === 'string' && value.length <= limit;
}

function exactKeys(value, keys) {
    requireThat(value !== null && typeof value === 'object' && !Array.isArray(value));
    const actual = Object.keys(value);
    requireThat(actual.length === keys.length
        && keys.every(key => Object.hasOwn(value, key)));
}

// Inspect descriptors before reading values: imported JSON cannot introduce keys
// with prototype semantics; direct callers cannot introduce getters or cycles.
function safeTree(value) {
    let visited = 0;
    const ancestors = new WeakSet();
    function visit(item, depth) {
        requireThat(++visited <= 8192 && depth <= 16);
        if (item === null || typeof item !== 'object') {
            requireThat(['string', 'number', 'boolean'].includes(typeof item) || item === null);
            return;
        }
        requireThat(!ancestors.has(item));
        const array = Array.isArray(item);
        const prototype = Object.getPrototypeOf(item);
        requireThat(array ? prototype === Array.prototype && item.length <= 128
            : prototype === Object.prototype || prototype === null);
        const keys = Reflect.ownKeys(item);
        requireThat(keys.length <= 129);
        ancestors.add(item);
        for (const key of keys) {
            requireThat(typeof key === 'string' && !FORBIDDEN_KEYS.has(key));
            if (array && key === 'length') continue;
            if (array) requireThat(/^(0|[1-9]\d*)$/.test(key) && Number(key) < item.length);
            const descriptor = Object.getOwnPropertyDescriptor(item, key);
            requireThat(Object.hasOwn(descriptor, 'value') && descriptor.enumerable);
            visit(descriptor.value, depth + 1);
        }
        if (array) requireThat(keys.length === item.length + 1);
        ancestors.delete(item);
    }
    visit(value, 0);
}

export function clone(value) {
    return structuredClone(value);
}

function rectangle(rect, bounded = true) {
    exactKeys(rect, ['x', 'y', 'width', 'height']);
    requireThat([rect.x, rect.y, rect.width, rect.height].every(finite)
        && rect.width > 0 && rect.height > 0);
    if (bounded) {
        requireThat(Math.abs(rect.x) <= MAX_COORDINATE && Math.abs(rect.y) <= MAX_COORDINATE
            && rect.width <= MAX_COORDINATE && rect.height <= MAX_COORDINATE);
    }
}

export function rectPoints(rect) {
    rectangle(rect, false);
    const { x, y, width, height } = rect;
    const points = [
        { x, y },
        { x: x + width, y },
        { x: x + width, y: y + height },
        { x, y: y + height },
    ];
    requireThat(points.every(point => finite(point.x) && finite(point.y)));
    return points;
}

function defaultStyle() {
    return {
        font: 'sans', size: 34, minSize: 14, fill: '#ffffff', outline: '#000000',
        outlineWidth: 0, align: 'center', lineHeight: 1.15,
        weight: 'normal', overflow: 'shrink',
    };
}

export function createScene(name = 'captions') {
    requireThat(name === 'captions' || name === 'chat', 'Cena inválida.');
    const doc = {
        format: 'regata-creator-spike', version: 1, width: 900, height: 620, scene: name,
        sources: [
            { slot: 1, description: 'Ana Lima', color: '#70b9cd' },
            { slot: 2, description: 'Bruno do bairro', color: '#e3b56a' },
            { slot: 3, description: 'João com uma descrição muito mais comprida', color: '#b298d5' },
        ],
        areas: [],
    };
    if (name === 'chat') {
        doc.areas = [
            { id: 'image-1', type: 'image', source: 1, background: false,
                points: rectPoints({ x: 85, y: 150, width: 110, height: 110 }) },
            { id: 'text-1', type: 'text', source: 1, background: false,
                rect: { x: 225, y: 145, width: 580, height: 160 },
                text: '{{source.description}} entrou no grupo',
                style: { ...defaultStyle(), align: 'left', fill: '#70b9cd', size: 48 } },
        ];
    } else {
        for (let slot = 1; slot <= 3; slot++) {
            const x = 80 + (slot - 1) * 275;
            const points = slot === 1
                ? [{ x: 80, y: 250 }, { x: 280, y: 260 }, { x: 270, y: 450 }, { x: 90, y: 445 }]
                : rectPoints({ x, y: 250, width: 200, height: 200 });
            doc.areas.push(
                { id: `image-${slot}`, type: 'image', source: slot, background: false, points },
                { id: `text-${slot}`, type: 'text', source: slot, background: false,
                    rect: { x: x - 25, y: 465, width: 250, height: 100 },
                    text: 'Candidato {{source.description}}', style: defaultStyle() },
            );
        }
    }
    return validateDocument(doc);
}

export function resolveText(template, description) {
    requireThat(boundedString(template), 'Texto inválido.');
    let result = '';
    // Braces are reserved for the single supported token. Description content is
    // appended literally and is never scanned, evaluated, or interpreted as HTML.
    for (let index = 0; index < template.length;) {
        const character = template[index];
        if (character === '{') {
            const match = /^\{\{\s*source\.description\s*\}\}/.exec(template.slice(index));
            requireThat(match !== null && template[index + match[0].length] !== '}', 'Texto inválido.');
            requireThat(boundedString(description), 'Descrição inválida.');
            result += description;
            index += match[0].length;
        } else {
            requireThat(character !== '}', 'Texto inválido.');
            result += character;
            index++;
        }
    }
    requireThat(result.length <= MAX_LAYOUT_LENGTH, 'Texto inválido.');
    return result;
}

function validateStyle(style) {
    exactKeys(style, ['font', 'size', 'minSize', 'fill', 'outline', 'outlineWidth',
        'align', 'lineHeight', 'weight', 'overflow']);
    requireThat(typeof style.font === 'string' && Object.hasOwn(FONT_FAMILIES, style.font)
        && finite(style.size) && style.size >= 8 && style.size <= 120
        && finite(style.minSize) && style.minSize >= 8 && style.minSize <= style.size
        && finite(style.lineHeight) && style.lineHeight >= 1 && style.lineHeight <= 2
        && finite(style.outlineWidth) && style.outlineWidth >= 0 && style.outlineWidth <= 8
        && typeof style.fill === 'string' && COLOR.test(style.fill)
        && typeof style.outline === 'string' && COLOR.test(style.outline)
        && ['normal', 'bold'].includes(style.weight)
        && ['left', 'center', 'right'].includes(style.align)
        && ['shrink', 'clip'].includes(style.overflow));
}

function fittingPrefix(graphemes, start, width, measured) {
    // Font advance measurements are monotonic over grapheme prefixes. The first
    // grapheme already fits; binary search avoids quadratic prefix scans.
    let low = start + 2;
    let high = graphemes.length;
    let end = start + 1;
    while (low <= high) {
        const middle = Math.floor((low + high) / 2);
        if (measured(graphemes.slice(start, middle).join('')) <= width) {
            end = middle;
            low = middle + 1;
        } else {
            high = middle - 1;
        }
    }
    return end;
}

function splitWord(token, width, measured) {
    if (measured(token.word) <= width) return [token.word];
    token.graphemes ??= Array.from(SEGMENTER.segment(token.word), segment => segment.segment);
    const { graphemes } = token;
    const chunks = [];
    for (let start = 0; start < graphemes.length;) {
        // An indivisible oversized grapheme must be retained, not dropped. It
        // cannot gain space by searching longer prefixes of the same word.
        const end = measured(graphemes[start]) > width ? start + 1
            : fittingPrefix(graphemes, start, width, measured);
        chunks.push(graphemes.slice(start, end).join(''));
        start = end;
    }
    return chunks;
}

function wrapParagraph(words, width, measured) {
    const lines = [];
    let line = '';
    for (const token of words) {
        const candidate = line === '' ? token.word : `${line} ${token.word}`;
        if (measured(candidate) <= width) {
            line = candidate;
            continue;
        }
        if (line !== '') lines.push(line);
        const chunks = splitWord(token, width, measured);
        for (let index = 0; index < chunks.length - 1; index++) lines.push(chunks[index]);
        line = chunks.at(-1);
    }
    lines.push(line);
    return lines;
}

function textMeasurer(measure, size, style) {
    const widths = new Map();
    let cachedCharacters = 0;
    return value => {
        if (widths.has(value)) return widths.get(value);
        const width = measure(value, size, FONT_FAMILIES[style.font], style.weight);
        requireThat(finite(width) && width >= 0, 'Medição inválida.');
        // Bound cache memory as well as iterations: distinct long prefixes must
        // not retain a quadratic amount of text across a single fitting attempt.
        if (widths.size < 1024 && cachedCharacters + value.length <= 2 * MAX_LAYOUT_LENGTH) {
            widths.set(value, width);
            cachedCharacters += value.length;
        }
        return width;
    };
}

export function layoutText(text, rect, style, measure) {
    requireThat(boundedString(text, MAX_LAYOUT_LENGTH) && typeof measure === 'function', 'Texto inválido.');
    rectangle(rect);
    validateStyle(style);
    const padding = 4 + style.outlineWidth;
    const width = Math.max(0, rect.width - 2 * padding);
    const height = Math.max(0, rect.height - 2 * padding);
    const paragraphs = text === '' ? [] : text.split(/\r\n|[\r\n\u2028\u2029]/u)
        .map(paragraph => (paragraph.match(/\S+/gu) ?? []).map(word => ({ word, graphemes: null })));

    function atSize(fontSize) {
        const measured = textMeasurer(measure, fontSize, style);
        const lines = paragraphs.flatMap(words => wrapParagraph(words, width, measured));
        const lineHeight = fontSize * style.lineHeight;
        const overflow = lines.length > 0 && (rect.width < 2 * padding || rect.height < 2 * padding
            || lines.length * lineHeight > height || lines.some(line => measured(line) > width));
        return { lines, fontSize, lineHeight, overflow };
    }

    if (style.overflow === 'clip') return atSize(style.size);
    for (let size = Math.floor(style.size); size >= Math.ceil(style.minSize); size--) {
        const result = atSize(size);
        if (!result.overflow) return result;
        if (size === style.minSize) return result;
    }
    return atSize(style.minSize);
}

function validatePoints(points) {
    requireThat(Array.isArray(points) && points.length === 4);
    for (const point of points) {
        exactKeys(point, ['x', 'y']);
        requireThat(Number.isInteger(point.x) && Number.isInteger(point.y)
            && Math.abs(point.x) <= MAX_COORDINATE && Math.abs(point.y) <= MAX_COORDINATE);
    }
    let direction = 0;
    for (let index = 0; index < 4; index++) {
        const a = points[index];
        const b = points[(index + 1) % 4];
        const c = points[(index + 2) % 4];
        const cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
        requireThat(cross !== 0 && (direction === 0 || Math.sign(cross) === direction));
        direction = Math.sign(cross);
    }
}

// Strict separating-axis overlap rejects edge/corner-only contact, including
// rotated quads whose bounding box overlaps but whose polygon does not.
function overlapsCanvas(points, width, height) {
    const canvas = rectPoints({ x: 0, y: 0, width, height });
    const axes = [{ x: 1, y: 0 }, { x: 0, y: 1 }];
    for (let index = 0; index < 4; index++) {
        const a = points[index];
        const b = points[(index + 1) % 4];
        axes.push({ x: a.y - b.y, y: b.x - a.x });
    }
    return axes.every(axis => {
        const first = points.map(point => point.x * axis.x + point.y * axis.y);
        const second = canvas.map(point => point.x * axis.x + point.y * axis.y);
        return Math.min(Math.max(...first), Math.max(...second))
            > Math.max(Math.min(...first), Math.min(...second));
    });
}

export function validateDocument(doc) {
    safeTree(doc);
    exactKeys(doc, ['format', 'version', 'width', 'height', 'scene', 'sources', 'areas']);
    requireThat(doc.format === 'regata-creator-spike' && doc.version === 1
        && Number.isInteger(doc.width) && doc.width > 0 && doc.width <= MAX_COORDINATE
        && Number.isInteger(doc.height) && doc.height > 0 && doc.height <= MAX_COORDINATE
        && doc.width * doc.height <= 40000000
        && ['captions', 'chat'].includes(doc.scene)
        && Array.isArray(doc.sources) && doc.sources.length >= 1 && doc.sources.length <= 128
        && Array.isArray(doc.areas) && doc.areas.length >= 1 && doc.areas.length <= 128);
    const slots = new Set();
    for (const source of doc.sources) {
        exactKeys(source, ['slot', 'description', 'color']);
        requireThat(Number.isInteger(source.slot) && source.slot >= 1
            && source.slot <= doc.sources.length && !slots.has(source.slot)
            && boundedString(source.description) && typeof source.color === 'string' && COLOR.test(source.color));
        slots.add(source.slot);
    }
    const ids = new Set();
    const references = new Set();
    for (const area of doc.areas) {
        requireThat(area !== null && typeof area === 'object'
            && (area.type === 'image' || area.type === 'text'));
        exactKeys(area, area.type === 'image'
            ? ['id', 'type', 'source', 'background', 'points']
            : ['id', 'type', 'source', 'background', 'rect', 'text', 'style']);
        requireThat(typeof area.id === 'string' && ID.test(area.id) && !ids.has(area.id)
            && Number.isInteger(area.source) && slots.has(area.source)
            && typeof area.background === 'boolean');
        ids.add(area.id);
        references.add(area.source);
        if (area.type === 'image') {
            validatePoints(area.points);
            requireThat(overlapsCanvas(area.points, doc.width, doc.height));
        } else {
            rectangle(area.rect);
            validateStyle(area.style);
            resolveText(area.text, doc.sources.find(source => source.slot === area.source).description);
            requireThat(overlapsCanvas(rectPoints(area.rect), doc.width, doc.height));
        }
    }
    for (let slot = 1; slot <= references.size; slot++) requireThat(references.has(slot));
    return doc;
}

export function layerOrder(doc) {
    validateDocument(doc);
    return [...doc.areas.filter(area => area.background).map(area => area.id),
        'template', ...doc.areas.filter(area => !area.background).map(area => area.id)];
}

function movementArea(area) {
    requireThat(area !== null && typeof area === 'object', 'Área inválida.');
    if (area.type === 'image') {
        requireThat(Array.isArray(area.points) && area.points.length === 4
            && area.points.every(point => point !== null && typeof point === 'object'
                && finite(point.x) && finite(point.y)), 'Área inválida.');
    } else {
        requireThat(area.type === 'text', 'Área inválida.');
        rectangle(area.rect, false);
    }
}

export function moveArea(area, dx, dy) {
    movementArea(area);
    requireThat(finite(dx) && finite(dy), 'Movimento inválido.');
    const result = clone(area);
    if (result.type === 'image') {
        result.points = result.points.map(point => ({ x: Math.round(point.x + dx), y: Math.round(point.y + dy) }));
        requireThat(result.points.every(point => finite(point.x) && finite(point.y)), 'Movimento inválido.');
    } else {
        result.rect.x = Math.round(result.rect.x + dx);
        result.rect.y = Math.round(result.rect.y + dy);
        requireThat(finite(result.rect.x) && finite(result.rect.y), 'Movimento inválido.');
    }
    return result;
}

export function moveCorner(area, index, point) {
    movementArea(area);
    requireThat(Number.isInteger(index) && index >= 0 && index <= 3
        && point !== null && typeof point === 'object' && finite(point.x) && finite(point.y), 'Canto inválido.');
    const result = clone(area);
    const target = { x: Math.round(point.x), y: Math.round(point.y) };
    if (result.type === 'image') {
        result.points[index] = target;
    } else {
        const opposite = rectPoints(area.rect)[(index + 2) % 4];
        result.rect = {
            x: Math.min(target.x, opposite.x), y: Math.min(target.y, opposite.y),
            width: Math.abs(target.x - opposite.x), height: Math.abs(target.y - opposite.y),
        };
        requireThat(finite(result.rect.width) && finite(result.rect.height), 'Canto inválido.');
    }
    return result;
}

export function encodeDocument(doc) {
    validateDocument(doc);
    const encoded = JSON.stringify(doc, null, 2);
    requireThat(new TextEncoder().encode(encoded).byteLength <= MAX_DOCUMENT_BYTES);
    return encoded;
}

export function decodeDocument(text) {
    requireThat(typeof text === 'string' && text.length <= MAX_DOCUMENT_BYTES
        && new TextEncoder().encode(text).byteLength <= MAX_DOCUMENT_BYTES);
    let doc;
    try {
        doc = JSON.parse(text);
    } catch {
        throw new Error('Documento inválido.');
    }
    return validateDocument(doc);
}

function validPoint(point) {
    requireThat(point !== null && typeof point === 'object' && finite(point.x) && finite(point.y), 'Ponto inválido.');
}

function validView(view) {
    requireThat(view !== null && typeof view === 'object' && finite(view.scale)
        && view.scale > 0 && finite(view.x) && finite(view.y), 'Visualização inválida.');
}

export function viewToImage(point, view = DEFAULT_VIEW) {
    validPoint(point);
    validView(view);
    const result = { x: (point.x - view.x) / view.scale, y: (point.y - view.y) / view.scale };
    validPoint(result);
    return result;
}

export function imageToView(point, view = DEFAULT_VIEW) {
    validPoint(point);
    validView(view);
    const result = { x: point.x * view.scale + view.x, y: point.y * view.scale + view.y };
    validPoint(result);
    return result;
}

export function zoomAt(view, screenPoint, factor) {
    validView(view);
    validPoint(screenPoint);
    requireThat(finite(factor) && factor > 0, 'Zoom inválido.');
    const anchor = viewToImage(screenPoint, view);
    const scale = Math.max(0.15, Math.min(8, view.scale * factor));
    const result = { scale, x: screenPoint.x - anchor.x * scale, y: screenPoint.y - anchor.y * scale };
    validView(result);
    return result;
}