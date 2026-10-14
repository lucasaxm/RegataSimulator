// Image-only, original-pixel model. No scene, image bytes, text, view or native JSON import.
const LIMIT = 10000;
const HEADER = 'Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background';
const DEFAULT_VIEW = Object.freeze({ scale: 1, x: 0, y: 0 });
const SIGNS = [[-1, -1], [1, -1], [1, 1], [-1, 1]];

function requireThat(condition, message = 'Documento ou geometria inválida.') {
    if (!condition) throw new Error(message);
}

const finite = value => typeof value === 'number' && Number.isFinite(value);

// Check descriptors before values: getters, inherited payload, symbols, sparse
// arrays and prototype-sensitive keys must never be executed or silently dropped.
function safeTree(value) {
    let visited = 0;
    const ancestors = new WeakSet();
    function visit(item, depth) {
        requireThat(++visited <= 8192 && depth <= 16);
        if (item === null || typeof item !== 'object') {
            requireThat(item === null || ['string', 'number', 'boolean'].includes(typeof item));
            return;
        }
        const array = Array.isArray(item);
        const prototype = Object.getPrototypeOf(item);
        requireThat(!ancestors.has(item) && (array ? prototype === Array.prototype
            : prototype === Object.prototype || prototype === null));
        const keys = Reflect.ownKeys(item);
        requireThat(keys.length <= 129 && (!array || item.length <= 128));
        ancestors.add(item);
        for (const key of keys) {
            requireThat(typeof key === 'string' && !['__proto__', 'constructor', 'prototype'].includes(key));
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

function exactKeys(value, keys) {
    requireThat(value !== null && typeof value === 'object' && !Array.isArray(value));
    requireThat(Reflect.ownKeys(value).length === keys.length
        && keys.every(key => Object.hasOwn(value, key)));
}

export function clone(value) {
    safeTree(value);
    try { return structuredClone(value); }
    catch { throw new Error('Não foi possível copiar o documento.'); }
}

function dimensions(width, height) {
    requireThat(Number.isInteger(width) && Number.isInteger(height)
        && width > 0 && height > 0 && width <= LIMIT && height <= LIMIT
        && width * height <= 40000000, 'Dimensões inválidas: limite de 10.000 px e 40 milhões de pixels.');
}

function point(value) {
    safeTree(value);
    exactKeys(value, ['x', 'y']);
    requireThat(finite(value.x) && finite(value.y), 'Ponto inválido.');
}

function validatePoints(points) {
    requireThat(Array.isArray(points) && points.length === 4);
    for (const value of points) {
        exactKeys(value, ['x', 'y']);
        requireThat(Number.isInteger(value.x) && Number.isInteger(value.y)
            && Math.abs(value.x) <= LIMIT && Math.abs(value.y) <= LIMIT);
    }
    let direction = 0;
    for (let index = 0; index < 4; index++) {
        const a = points[index], b = points[(index + 1) % 4], c = points[(index + 2) % 4];
        const cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
        requireThat(cross !== 0 && (direction === 0 || Math.sign(cross) === direction),
            'A área deve ser um quadrilátero convexo sem cantos cruzados.');
        direction = Math.sign(cross);
    }
    return direction;
}

function validateArea(area) {
    safeTree(area);
    exactKeys(area, ['id', 'source', 'background', 'points']);
    requireThat(typeof area.id === 'string' && /^[a-z0-9_-]{1,64}$/i.test(area.id)
        && Number.isInteger(area.source) && area.source >= 1 && area.source <= 128
        && typeof area.background === 'boolean', 'Área inválida.');
    validatePoints(area.points);
    return area;
}

export function rectPoints(rect) {
    safeTree(rect);
    exactKeys(rect, ['x', 'y', 'width', 'height']);
    requireThat(Object.values(rect).every(Number.isInteger) && rect.width > 0 && rect.height > 0,
        'Retângulo inválido: use dimensões inteiras positivas.');
    const { x, y, width, height } = rect;
    const points = [{ x, y }, { x: x + width, y },
        { x: x + width, y: y + height }, { x, y: y + height }];
    validatePoints(points);
    return points;
}

function overlapsCanvas(points, width, height) {
    const canvas = rectPoints({ x: 0, y: 0, width, height });
    const axes = [{ x: 1, y: 0 }, { x: 0, y: 1 }];
    points.forEach((a, index) => {
        const b = points[(index + 1) % 4];
        axes.push({ x: a.y - b.y, y: b.x - a.x });
    });
    return axes.every(axis => {
        const first = points.map(p => p.x * axis.x + p.y * axis.y);
        const second = canvas.map(p => p.x * axis.x + p.y * axis.y);
        return Math.min(Math.max(...first), Math.max(...second))
            > Math.max(Math.min(...first), Math.min(...second));
    });
}

function contiguous(slots) {
    for (let slot = 1; slot <= slots.size; slot++) if (!slots.has(slot)) return false;
    return true;
}

export function validateDocument(doc) {
    safeTree(doc);
    exactKeys(doc, ['width', 'height', 'areas']);
    dimensions(doc.width, doc.height);
    requireThat(Array.isArray(doc.areas) && doc.areas.length <= 128, 'Limite de 128 áreas.');
    const ids = new Set(), slots = new Set();
    for (const area of doc.areas) {
        validateArea(area);
        requireThat(!ids.has(area.id), 'Identificador de área repetido.');
        requireThat(overlapsCanvas(area.points, doc.width, doc.height),
            'A área deve sobrepor a imagem com área positiva, não apenas tocar a borda.');
        ids.add(area.id);
        slots.add(area.source);
    }
    requireThat(contiguous(slots), 'As fontes usadas devem ser consecutivas a partir de 1.');
    return doc;
}

export function createDocument(width, height) {
    return validateDocument({ width, height, areas: [] });
}

export function isRectangle(area) {
    try {
        validateArea(area);
        const [tl, tr, br, bl] = area.points;
        return tl.y === tr.y && tr.x === br.x && br.y === bl.y && bl.x === tl.x
            && tl.x < tr.x && tl.y < bl.y;
    } catch { return false; }
}

function areaIndex(doc, id) {
    const index = doc.areas.findIndex(area => area.id === id);
    requireThat(index !== -1, 'Área não encontrada.');
    return index;
}

export function sourceOptions(doc, id) {
    validateDocument(doc);
    const index = areaIndex(doc, id);
    let candidates = [1];
    if (index > 0) {
        const previous = doc.areas[index - 1].source;
        candidates = [previous, previous + 1];
        if (index < doc.areas.length - 1) {
            const difference = doc.areas[index + 1].source - previous;
            if (difference === 0) candidates = [previous];
            else if (difference !== 1) candidates = [previous + 1];
        }
    }
    // Imported repetitions remain valid even if the narrow picker excludes the
    // current value. The UI must retain that value as a disabled original option.
    return candidates.filter(candidate => candidate <= 128 && contiguous(new Set(
        doc.areas.map((area, position) => position === index ? candidate : area.source))));
}

function newId(doc) {
    const ids = new Set(doc.areas.map(area => area.id));
    let number = 1;
    while (ids.has(`area-${number}`)) number++;
    return `area-${number}`;
}

export function addArea(doc, rect) {
    validateDocument(doc);
    requireThat(doc.areas.length < 128, 'Limite de 128 áreas atingido.');
    const slots = new Set(doc.areas.map(area => area.source));
    let next = 1;
    while (slots.has(next)) next++;
    const previous = doc.areas.at(-1)?.source;
    const choices = previous === undefined ? [1] : [previous, previous + 1].filter(slot => slot <= 128);
    const result = clone(doc);
    result.areas.push({ id: newId(doc), source: choices.includes(next) ? next : choices.at(-1),
        background: false, points: rectPoints(rect) });
    return validateDocument(result);
}

export function updateArea(doc, id, area) {
    validateDocument(doc);
    const index = areaIndex(doc, id);
    validateArea(area);
    requireThat(area.id === id, 'O identificador da área deve ser preservado.');
    const result = clone(doc);
    result.areas[index] = clone(area);
    return validateDocument(result);
}

export function deleteArea(doc, id) {
    validateDocument(doc);
    const result = clone(doc);
    result.areas.splice(areaIndex(doc, id), 1);
    const used = [...new Set(result.areas.map(area => area.source))].sort((a, b) => a - b);
    const mapping = new Map(used.map((source, index) => [source, index + 1]));
    result.areas.forEach(area => { area.source = mapping.get(area.source); });
    return validateDocument(result);
}

export function duplicateArea(doc, id) {
    validateDocument(doc);
    requireThat(doc.areas.length < 128, 'Limite de 128 áreas atingido.');
    const index = areaIndex(doc, id);
    const result = clone(doc), copy = clone(doc.areas[index]);
    copy.id = newId(doc);
    // A small original-pixel offset is a convenience, never a clamp or a reason
    // to reject an otherwise valid duplicate at the coordinate/canvas boundary.
    try {
        const moved = moveArea(copy, 10, 10);
        if (overlapsCanvas(moved.points, doc.width, doc.height)) copy.points = moved.points;
    } catch { /* Retain the original valid geometry. */ }
    result.areas.splice(index + 1, 0, copy);
    return validateDocument(result);
}

export function reorderArea(doc, id, direction) {
    validateDocument(doc);
    requireThat(direction === -1 || direction === 1, 'Direção inválida: use -1 ou +1.');
    const index = areaIndex(doc, id), target = index + direction;
    const result = clone(doc);
    if (target >= 0 && target < result.areas.length) {
        [result.areas[index], result.areas[target]] = [result.areas[target], result.areas[index]];
    }
    return validateDocument(result);
}

export function importCsv(csv, width, height) {
    requireThat(typeof csv === 'string' && csv.length > 0 && csv.length <= 65536
        && new TextEncoder().encode(csv).byteLength <= 65536, 'CSV inválido: limite de 64 KiB.');
    const doc = createDocument(width, height);
    const lines = csv.split(/\r?\n/u);
    if (lines.at(-1) === '') lines.pop();
    requireThat(lines[0] === HEADER && lines.length >= 2 && lines.length <= 129, 'Cabeçalho ou linhas do CSV inválidos.');
    let base;
    for (let index = 1; index < lines.length; index++) {
        const fields = lines[index].split(',');
        requireThat(fields.length === 11 && fields.every(field => /^-?\d+$/u.test(field)),
            'CSV inválido: são necessárias exatamente 11 colunas inteiras.');
        const values = fields.map(Number);
        requireThat(values.every(Number.isSafeInteger), 'Número inválido no CSV.');
        const [position, source, ...coordinates] = values;
        if (index === 1) {
            base = position;
            requireThat(base === 0 || base === 1, 'O índice inicial do CSV deve ser 0 ou 1.');
        }
        const background = coordinates.pop();
        requireThat(position === base + index - 1 && (background === 0 || background === 1),
            'Índices ou Background inválidos no CSV.');
        doc.areas.push({ id: `area-${index}`, source, background: background === 1,
            points: Array.from({ length: 4 }, (_, corner) => ({
                x: coordinates[2 * corner], y: coordinates[2 * corner + 1],
            })) });
    }
    return validateDocument(doc);
}

export function exportCsv(doc) {
    validateDocument(doc); // Unknown/text payload is rejected, never silently filtered.
    requireThat(doc.areas.length > 0, 'O CSV precisa de pelo menos uma área de imagem.');
    return [HEADER, ...doc.areas.map((area, index) => [index + 1, area.source,
        ...area.points.flatMap(p => [p.x, p.y]), area.background ? 1 : 0].join(','))].join('\n');
}

function cornerIndex(index) {
    requireThat(Number.isInteger(index) && index >= 0 && index < 4, 'Canto ou borda inválida.');
}

function modeName(mode) {
    requireThat(mode === 'perspective' || mode === 'rectangle', 'Modo de edição inválido.');
}

function quantized(value) {
    point(value);
    return { x: Math.round(value.x), y: Math.round(value.y) };
}

function transformed(area, points) {
    const result = { ...clone(area), points };
    validateArea(result);
    requireThat(validatePoints(points) === validatePoints(area.points),
        'O movimento não pode inverter a orientação dos cantos.');
    // Canvas overlap and the complete source union belong to updateArea.
    return result;
}

export function moveArea(area, dx, dy) {
    validateArea(area);
    requireThat(finite(dx) && finite(dy), 'Movimento inválido.');
    const delta = { x: Math.round(dx), y: Math.round(dy) };
    return transformed(area, area.points.map(p => ({ x: p.x + delta.x, y: p.y + delta.y })));
}

function diagonalRectangle(anchor, opposite, index) {
    const [sx, sy] = SIGNS[index];
    requireThat(sx * (anchor.x - opposite.x) > 0 && sy * (anchor.y - opposite.y) > 0,
        'Retângulo inválido: os cantos não podem cruzar, colapsar ou trocar de posição.');
    return rectPoints({ x: Math.min(anchor.x, opposite.x), y: Math.min(anchor.y, opposite.y),
        width: Math.abs(anchor.x - opposite.x), height: Math.abs(anchor.y - opposite.y) });
}

export function moveCorner(area, index, target, mode = 'perspective') {
    validateArea(area);
    cornerIndex(index);
    modeName(mode);
    const moved = quantized(target);
    if (mode === 'rectangle') {
        requireThat(isRectangle(area), 'Use Transformar em retângulo antes de editar neste modo.');
        return transformed(area, diagonalRectangle(moved, area.points[(index + 2) % 4], index));
    }
    const points = clone(area.points);
    points[index] = moved;
    return transformed(area, points);
}

// delta is {x,y} in original-image pixels. In rectangle mode its orthogonal
// component is ignored; both endpoints receive the same once-rounded delta.
export function moveEdge(area, index, delta, mode = 'perspective') {
    validateArea(area);
    cornerIndex(index);
    modeName(mode);
    const moved = quantized(delta);
    if (mode === 'rectangle') {
        requireThat(isRectangle(area), 'Use Transformar em retângulo antes de editar neste modo.');
        if (index % 2 === 0) moved.x = 0;
        else moved.y = 0;
    }
    const points = area.points.map((p, corner) => corner === index || corner === (index + 1) % 4
        ? { x: p.x + moved.x, y: p.y + moved.y } : { ...p });
    if (mode === 'rectangle') requireThat(isRectangle({ ...area, points }),
        'A borda não pode cruzar ou colapsar o retângulo.');
    return transformed(area, points);
}

export function rectify(area, anchor, dimensionsOverride) {
    validateArea(area);
    cornerIndex(anchor);
    const fixed = area.points[anchor];
    let opposite = area.points[(anchor + 2) % 4];
    if (dimensionsOverride !== undefined) {
        safeTree(dimensionsOverride);
        exactKeys(dimensionsOverride, ['width', 'height']);
        const { width, height } = dimensionsOverride;
        requireThat(Number.isInteger(width) && Number.isInteger(height) && width > 0 && height > 0,
            'Largura e altura devem ser inteiras positivas.');
        const [sx, sy] = SIGNS[anchor];
        opposite = { x: fixed.x - sx * width, y: fixed.y - sy * height };
    }
    // Return a preview clone: the original is unchanged until updateArea commits it.
    return validateArea({ ...clone(area), points: diagonalRectangle(fixed, opposite, anchor) });
}

function validView(view) {
    safeTree(view);
    exactKeys(view, ['scale', 'x', 'y']);
    requireThat(finite(view.scale) && view.scale > 0
        && finite(view.x) && finite(view.y), 'Visualização inválida.');
}

export function viewToImage(value, view = DEFAULT_VIEW) {
    point(value);
    validView(view);
    const result = { x: (value.x - view.x) / view.scale, y: (value.y - view.y) / view.scale };
    point(result);
    return result;
}

export function imageToView(value, view = DEFAULT_VIEW) {
    point(value);
    validView(view);
    const result = { x: value.x * view.scale + view.x, y: value.y * view.scale + view.y };
    point(result);
    return result;
}

export function zoomAt(view, screenPoint, factor) {
    validView(view);
    point(screenPoint);
    requireThat(finite(factor) && factor > 0, 'Zoom inválido.');
    const anchor = viewToImage(screenPoint, view);
    const scale = Math.max(0.01, Math.min(16, view.scale * factor));
    const result = { scale, x: screenPoint.x - anchor.x * scale, y: screenPoint.y - anchor.y * scale };
    validView(result);
    return result;
}