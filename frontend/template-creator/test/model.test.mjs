import test from 'node:test';
import assert from 'node:assert/strict';
import * as model from '../model.mjs';

const { clone, createDocument, validateDocument, rectPoints, isRectangle, sourceOptions,
    addArea, updateArea, deleteArea, duplicateArea, reorderArea, importCsv, exportCsv,
    moveArea, moveCorner, moveEdge, rectify, viewToImage, imageToView, zoomAt } = model;
const HEADER = 'Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background';
// Exact supplied spike-test coordinates, not a sourced image or user-media read.
const CSV = `${HEADER}\n1,1,105,840,274,840,274,1018,105,1018,0\n2,2,461,842,631,842,631,1020,461,1020,0\n3,3,800,840,969,840,969,1018,800,1018,0`;
const RECT = { x: 10, y: 20, width: 100, height: 60 };
const rejects = fn => assert.throws(fn, error => error instanceof Error && error.message.length > 0);
const area = (points = rectPoints(RECT)) => ({ id: 'area-1', source: 1, background: false, points });
const documentWith = (sources = [1], points = rectPoints(RECT)) => validateDocument({
    width: 1200, height: 1200,
    areas: sources.map((source, index) => ({ ...area(clone(points)), id: `area-${index + 1}`, source })),
});
const signs = [[-1, -1], [1, -1], [1, 1], [-1, 1]];

function changeField(csv, row, field, value) {
    const lines = csv.split('\n'), fields = lines[row].split(',');
    fields[field] = value;
    lines[row] = fields.join(',');
    return lines.join('\n');
}

function freeze(value) {
    if (value && typeof value === 'object') {
        Object.values(value).forEach(freeze);
        Object.freeze(value);
    }
    return value;
}

test('exact image-only public API and document shape; clone is independent', () => {
    assert.deepEqual(Object.keys(model).sort(), ['clone', 'createDocument', 'validateDocument',
        'rectPoints', 'isRectangle', 'sourceOptions', 'addArea', 'updateArea', 'deleteArea',
        'duplicateArea', 'reorderArea', 'importCsv', 'exportCsv', 'moveArea', 'moveCorner',
        'moveEdge', 'rectify', 'viewToImage', 'imageToView', 'zoomAt'].sort());
    const empty = createDocument(1080, 1114);
    assert.deepEqual(empty, { width: 1080, height: 1114, areas: [] });
    assert.equal(validateDocument(empty), empty);
    rejects(() => exportCsv(empty));
    const doc = documentWith(), copy = clone(doc);
    copy.areas[0].points[0].x++;
    assert.equal(doc.areas[0].points[0].x, 10);
    assert.equal(validateDocument(freeze(doc)), doc);
});

test('pixel, dimension, area, source, ID and coordinate limits are inclusive', () => {
    assert.deepEqual(createDocument(1, 1), { width: 1, height: 1, areas: [] });
    assert.equal(createDocument(10000, 4000).height, 4000);
    for (const value of [0, -1, 0.5, 10001, NaN, Infinity, '10', null, undefined]) {
        rejects(() => createDocument(value, 1));
        rejects(() => createDocument(1, value));
    }
    rejects(() => createDocument(10000, 4001));
    const doc = documentWith(Array.from({ length: 128 }, (_, index) => index + 1));
    doc.areas[0].id = 'a'.repeat(64);
    validateDocument(doc);
    rejects(() => addArea(doc, RECT));
    rejects(() => duplicateArea(doc, 'area-2'));
    doc.areas.push({ ...area(), id: 'overflow' });
    rejects(() => validateDocument(doc));
    for (const change of [a => { a.source = 0; }, a => { a.source = 129; },
        a => { a.source = 1.5; }, a => { a.source = '1'; }, a => { a.id = ''; },
        a => { a.id = 'x'.repeat(65); }, a => { a.id = '<script>'; },
        a => { a.background = 1; }, a => { a.points[0].x = 0.5; },
        a => { a.points[0].x = -10001; }, a => { a.points[0].y = Infinity; }]) {
        const invalid = documentWith();
        change(invalid.areas[0]);
        rejects(() => validateDocument(invalid));
    }
    const fullSpan = rectPoints({ x: -10000, y: -10000, width: 20000, height: 20000 });
    validateDocument(documentWith([1], fullSpan));
    rejects(() => rectPoints({ ...RECT, x: 0.5 }));
    rejects(() => rectPoints({ ...RECT, width: 0 }));
    rejects(() => rectPoints({ ...RECT, width: 10000 }));
});

test('safe payload rejects getters without running them, cycles, symbols and unknown/text fields', () => {
    for (const key of ['type', 'text', 'style', 'scene', 'format', 'sources', 'url', 'path',
        '__proto__', 'constructor', 'prototype']) {
        for (const location of ['doc', 'area', 'point']) {
            const doc = documentWith();
            const target = location === 'doc' ? doc : location === 'area' ? doc.areas[0] : doc.areas[0].points[0];
            Object.defineProperty(target, key, { value: 'untrusted', enumerable: true });
            rejects(() => validateDocument(doc));
            rejects(() => exportCsv(doc));
        }
    }
    for (const location of ['doc', 'area', 'point']) {
        const doc = documentWith();
        const target = location === 'doc' ? doc : location === 'area' ? doc.areas[0] : doc.areas[0].points[0];
        const key = location === 'doc' ? 'width' : location === 'area' ? 'source' : 'x';
        Object.defineProperty(target, key, { enumerable: true, get() { assert.fail('getter executed'); } });
        rejects(() => validateDocument(doc));
        rejects(() => clone(doc));
    }
    const cycle = documentWith(); cycle.areas[0].cycle = cycle;
    rejects(() => validateDocument(cycle));
    const sparse = documentWith(); delete sparse.areas[0].points[2];
    rejects(() => validateDocument(sparse));
    const symbol = documentWith(); symbol[Symbol('hidden')] = 1;
    rejects(() => validateDocument(symbol));
    const hidden = documentWith(); Object.defineProperty(hidden, 'hidden', { value: 1 });
    rejects(() => validateDocument(hidden));
    const inherited = documentWith(); Object.setPrototypeOf(inherited.areas[0], { text: 'extra' });
    rejects(() => validateDocument(inherited));
    assert.equal(validateDocument(Object.assign(Object.create(null), documentWith())).width, 1200);
    assert.equal({}.polluted, undefined);
});

test('SAT allows intentional off-canvas convex polygons in either winding, not touching or false AABB overlap', () => {
    for (const points of [rectPoints({ x: -10, y: -10, width: 11, height: 11 }),
        rectPoints({ x: 1199, y: 1199, width: 10, height: 10 }),
        [{ x: -10, y: 0 }, { x: 0, y: -10 }, { x: 10, y: 0 }, { x: 0, y: 10 }],
        rectPoints(RECT).reverse()]) validateDocument(documentWith([1], points));
    for (const points of [rectPoints({ x: -100, y: 20, width: 100, height: 100 }),
        rectPoints({ x: -100, y: -100, width: 100, height: 100 }),
        rectPoints({ x: 1200, y: 10, width: 10, height: 10 }),
        [{ x: -20, y: -40 }, { x: 5, y: -15 }, { x: -15, y: 5 }, { x: -40, y: -20 }],
        [{ x: -20, y: -20 }, { x: 10, y: -10 }, { x: -10, y: 10 }, { x: -30, y: 0 }],
        [{ x: 0, y: 0 }, { x: 100, y: 100 }, { x: 100, y: 0 }, { x: 0, y: 100 }],
        [{ x: 0, y: 0 }, { x: 100, y: 0 }, { x: 50, y: 20 }, { x: 0, y: 100 }],
        [{ x: 0, y: 0 }, { x: 50, y: 0 }, { x: 100, y: 0 }, { x: 0, y: 100 }],
        [{ x: 0, y: 0 }, { x: 100, y: 0 }, { x: 100, y: 0 }, { x: 0, y: 100 }]]) {
        rejects(() => documentWith([1], points));
    }
});

test('isRectangle is boolean, standard positive TL/TR/BR/BL only', () => {
    assert.equal(isRectangle(area()), true);
    assert.equal(isRectangle(area(rectPoints({ x: -10, y: -20, width: 10, height: 20 }))), true);
    for (const value of [null, {}, area(rectPoints(RECT).reverse()),
        area([...rectPoints(RECT).slice(1), rectPoints(RECT)[0]]),
        area([{ x: 5, y: 20 }, { x: 110, y: 20 }, { x: 105, y: 80 }, { x: 10, y: 80 }])]) {
        assert.equal(isRectangle(value), false);
    }
});

test('source picker preserves legacy adjacency but filters against the complete used union', () => {
    for (const [sources, expected] of [
        [[1], [[1]]], [[1, 2], [[1], [1, 2]]],
        [[1, 1, 1], [[1], [1], [1, 2]]], [[1, 2, 3], [[1], [2], [2, 3]]],
        [[1, 2, 1], [[1], [1], [2, 3]]], [[1, 3, 2], [[1], [1, 2], []]],
        [[2, 1], [[1], []]],
    ]) {
        const doc = documentWith(sources);
        assert.deepEqual(doc.areas.map(a => sourceOptions(doc, a.id)), expected);
    }
    rejects(() => documentWith([1, 3]));
    rejects(() => sourceOptions(documentWith(), 'missing'));
});

test('add generates unique IDs, allocates smallest unused only when permitted and never mutates input', () => {
    let doc = freeze(createDocument(1200, 1200));
    for (let index = 1; index <= 4; index++) {
        doc = addArea(freeze(doc), freeze(clone(RECT)));
        assert.equal(doc.areas.at(-1).id, `area-${index}`);
        assert.equal(doc.areas.at(-1).source, index);
        assert.ok(sourceOptions(doc, doc.areas.at(-1).id).includes(index));
    }
    const reused = freeze(documentWith([1, 2, 1]));
    const next = addArea(reused, RECT);
    assert.deepEqual(next.areas.map(a => a.source), [1, 2, 1, 2]);
    assert.ok(sourceOptions(next, next.areas.at(-1).id).includes(2));
    const holes = deleteArea(doc, 'area-2');
    assert.equal(addArea(holes, RECT).areas.at(-1).id, 'area-2');
    rejects(() => addArea(doc, { ...RECT, text: 'not image' }));
    rejects(() => addArea(doc, { x: -100, y: 10, width: 100, height: 100 }));
});

test('all short valid source unions append a picker-permitted default', () => {
    function check(sources) {
        const slots = new Set(sources);
        if (Array.from({ length: slots.size }, (_, i) => i + 1).every(s => slots.has(s))) {
            const doc = documentWith(sources), result = addArea(doc, RECT);
            const last = result.areas.at(-1);
            assert.ok(sourceOptions(result, last.id).includes(last.source));
            assert.equal(last.source, Math.min(slots.size + 1, (sources.at(-1) ?? 0) + 1));
        }
        if (sources.length < 4) for (let source = 1; source <= 3; source++) check([...sources, source]);
    }
    check([]);
});

test('update validates full parent overlap, union, stable ID and unknown fields with no partial mutation', () => {
    const doc = freeze(documentWith([1, 2, 1]));
    const candidate = moveArea(doc.areas[1], 3.6, -2.4);
    candidate.background = true;
    const updated = updateArea(doc, 'area-2', candidate);
    assert.equal(updated.areas[1].background, true);
    candidate.points[0].x = 500;
    assert.equal(updated.areas[1].points[0].x, 14);
    const gap = { ...clone(doc.areas[1]), source: 3 };
    rejects(() => updateArea(doc, gap.id, gap));
    assert.deepEqual(updateArea(doc, 'area-2', { ...clone(doc.areas[1]), source: 1 })
        .areas.map(a => a.source), [1, 1, 1]);
    const outside = moveArea(doc.areas[0], 1500, 0);
    rejects(() => updateArea(doc, outside.id, outside));
    rejects(() => updateArea(doc, 'missing', doc.areas[0]));
    rejects(() => updateArea(doc, 'area-1', { ...clone(doc.areas[0]), id: 'new-id' }));
    rejects(() => updateArea(doc, 'area-1', { ...clone(doc.areas[0]), type: 'text' }));
    assert.deepEqual(doc.areas.map(a => a.source), [1, 2, 1]);
});

test('deletion renumbers ascending used slots and preserves repeated references, IDs, order and background', () => {
    const doc = documentWith([3, 1, 2, 3, 1]);
    doc.areas[3].background = true;
    freeze(doc);
    const result = deleteArea(doc, 'area-3');
    assert.deepEqual(result.areas.map(a => a.source), [2, 1, 2, 1]);
    assert.deepEqual(result.areas.map(a => a.id), ['area-1', 'area-2', 'area-4', 'area-5']);
    assert.equal(result.areas[2].background, true);
    assert.deepEqual(deleteArea(documentWith([1, 1, 2]), 'area-1').areas.map(a => a.source), [1, 2]);
    assert.deepEqual(deleteArea(documentWith(), 'area-1'), createDocument(1200, 1200));
    rejects(() => deleteArea(doc, 'missing'));
});

test('duplicate immediately follows original, shares source/background and falls back at bounds or canvas', () => {
    const doc = documentWith([1, 2, 1]); doc.areas[1].background = true;
    freeze(doc);
    const duplicated = duplicateArea(doc, 'area-2');
    assert.deepEqual(duplicated.areas.map(a => a.id), ['area-1', 'area-2', 'area-4', 'area-3']);
    assert.equal(duplicated.areas[2].source, 2);
    assert.equal(duplicated.areas[2].background, true);
    assert.deepEqual(duplicated.areas[2].points, moveArea(doc.areas[1], 10, 10).points);
    duplicated.areas[2].points[0].x++;
    assert.equal(duplicated.areas[1].points[0].x, 10);
    for (const fixture of [
        { width: 10000, height: 4000, areas: [area(rectPoints({ x: 9900, y: 20, width: 100, height: 50 }))] },
        { width: 100, height: 100, areas: [area(rectPoints({ x: 95, y: 95, width: 5, height: 5 }))] },
    ]) {
        freeze(fixture);
        assert.deepEqual(duplicateArea(fixture, 'area-1').areas[1].points, fixture.areas[0].points);
    }
});

test('reorder uses full list order across background planes; boundary returns a validated clone', () => {
    const doc = documentWith([1, 2, 1]); doc.areas[1].background = true;
    freeze(doc);
    const reordered = reorderArea(doc, 'area-2', -1);
    assert.deepEqual(reordered.areas.map(a => a.id), ['area-2', 'area-1', 'area-3']);
    assert.equal(reordered.areas[0].background, true);
    assert.deepEqual(reorderArea(reordered, 'area-2', 1), doc);
    const boundary = reorderArea(doc, 'area-1', -1);
    assert.deepEqual(boundary, doc); assert.notEqual(boundary, doc);
    assert.deepEqual(reorderArea(doc, 'area-3', 1), doc);
    for (const direction of [0, 2, 0.5, '1', NaN]) rejects(() => reorderArea(doc, 'area-1', direction));
});

test('all rectangle corners round the target once, fix original opposite exactly and reject crossing', () => {
    const original = freeze(area());
    for (let index = 0; index < 4; index++) {
        const [sx, sy] = signs[index], p = original.points[index];
        const target = { x: p.x + sx * 5 + 0.49, y: p.y + sy * 7 + 0.49 };
        const resized = moveCorner(original, index, target, 'rectangle');
        assert.equal(isRectangle(resized), true);
        assert.deepEqual(resized.points[index], { x: Math.round(target.x), y: Math.round(target.y) });
        assert.deepEqual(resized.points[(index + 2) % 4], original.points[(index + 2) % 4]);
        const opposite = original.points[(index + 2) % 4];
        rejects(() => moveCorner(original, index, opposite, 'rectangle'));
        rejects(() => moveCorner(original, index, { x: opposite.x - sx, y: opposite.y - sy }, 'rectangle'));
        rejects(() => moveCorner(original, index, { ...target, x: opposite.x }, 'rectangle'));
    }
});

test('all perspective corners change only target; quantized concavity, degeneracy, bounds and orientation fail', () => {
    const original = freeze(area());
    for (let index = 0; index < 4; index++) {
        const [sx, sy] = signs[index], p = original.points[index];
        const moved = moveCorner(original, index, { x: p.x + sx * 2.6, y: p.y + sy * 3.6 });
        moved.points.forEach((point, i) => {
            if (i !== index) assert.deepEqual(point, original.points[i]);
        });
        assert.deepEqual(moved.points[index], { x: Math.round(p.x + sx * 2.6), y: Math.round(p.y + sy * 3.6) });
        rejects(() => moveCorner(original, index, original.points[(index + 2) % 4]));
    }
    rejects(() => moveCorner(original, 0, { x: 110.49, y: 20.49 }));
    rejects(() => moveCorner(original, 0, { x: 60, y: 70 }));
    rejects(() => moveCorner(original, 0, { x: -10001, y: -10 }));
    rejects(() => moveCorner(original, 0, { x: Infinity, y: 0 }));
    rejects(() => moveCorner(original, 0, { x: 1, y: 1, unknown: 1 }));
    for (const index of [-1, 4, 0.5, '0']) rejects(() => moveCorner(original, index, { x: 1, y: 1 }));
    rejects(() => moveCorner(original, 0, { x: 1, y: 1 }, 'unknown'));
});

test('all rectangle edges move their pair on the allowed axis only, leaving the opposite edge fixed', () => {
    const original = freeze(area());
    for (let index = 0; index < 4; index++) {
        const moved = moveEdge(original, index, { x: 3.6, y: -2.6 }, 'rectangle');
        assert.equal(isRectangle(moved), true);
        moved.points.forEach((p, corner) => {
            const affected = corner === index || corner === (index + 1) % 4;
            assert.deepEqual(p, { x: original.points[corner].x + (affected && index % 2 === 1 ? 4 : 0),
                y: original.points[corner].y + (affected && index % 2 === 0 ? -3 : 0) });
        });
        const axis = index % 2 === 0 ? 'y' : 'x';
        const distance = original.points[(index + 2) % 4][axis] - original.points[index][axis];
        rejects(() => moveEdge(original, index, { x: axis === 'x' ? distance : 0,
            y: axis === 'y' ? distance : 0 }, 'rectangle'));
        rejects(() => moveEdge(original, index, { x: axis === 'x' ? distance * 2 : 0,
            y: axis === 'y' ? distance * 2 : 0 }, 'rectangle'));
    }
});

const SKEW = [{ x: 10, y: 20 }, { x: 110, y: 25 }, { x: 100, y: 90 }, { x: 5, y: 80 }];

test('all perspective edges translate equal deltas, preserve edge vector and keep opposite pair read-only', () => {
    const original = freeze(area(clone(SKEW)));
    for (let index = 0; index < 4; index++) {
        const moved = moveEdge(original, index, { x: 2.6, y: -1.6 }, 'perspective');
        moved.points.forEach((p, corner) => {
            const affected = corner === index || corner === (index + 1) % 4;
            assert.deepEqual(p, { x: original.points[corner].x + (affected ? 3 : 0),
                y: original.points[corner].y + (affected ? -2 : 0) });
        });
        const next = (index + 1) % 4;
        assert.equal(moved.points[next].x - moved.points[index].x,
            original.points[next].x - original.points[index].x);
        assert.equal(moved.points[next].y - moved.points[index].y,
            original.points[next].y - original.points[index].y);
        rejects(() => moveEdge(original, index, { x: 1, y: 1 }, 'rectangle'));
    }
    rejects(() => moveEdge(area(), 0, { x: 0, y: 100 }, 'perspective'));
    rejects(() => moveEdge(original, 4, { x: 0, y: 0 }));
    rejects(() => moveEdge(original, 0, { x: NaN, y: 0 }));
    rejects(() => moveEdge(original, 0, { x: 0, y: 0 }, 'unknown'));
});

test('all rectification anchors preserve anchor/opposite by default; explicit size is a read-only preview', () => {
    const original = freeze(area(clone(SKEW)));
    for (let index = 0; index < 4; index++) {
        const rectified = rectify(original, index);
        assert.equal(isRectangle(rectified), true);
        assert.deepEqual(rectified.points[index], original.points[index]);
        assert.deepEqual(rectified.points[(index + 2) % 4], original.points[(index + 2) % 4]);
        const sized = rectify(original, index, freeze({ width: 30, height: 40 }));
        assert.deepEqual(sized.points[index], original.points[index]);
        assert.deepEqual(sized.points[(index + 2) % 4], {
            x: original.points[index].x - signs[index][0] * 30,
            y: original.points[index].y - signs[index][1] * 40,
        });
        assert.equal(isRectangle(sized), true);
        assert.notEqual(sized.points, original.points);
        assert.deepEqual(rectify(freeze(area()), index).points, rectPoints(RECT));
    }
    assert.deepEqual(original.points, SKEW);
    const noDiagonal = area([{ x: 0, y: 20 }, { x: 20, y: 0 }, { x: 0, y: -20 }, { x: -20, y: 0 }]);
    rejects(() => rectify(noDiagonal, 0));
    assert.equal(isRectangle(rectify(noDiagonal, 0, { width: 20, height: 20 })), true);
    rejects(() => rectify(area(rectPoints(RECT).reverse()), 0));
    for (const size of [{ width: 0, height: 10 }, { width: -1, height: 10 },
        { width: 1.5, height: 10 }, { width: 1, height: Infinity },
        { width: 10001, height: 1 }, { width: 10, height: 10, extra: 1 }, null]) {
        rejects(() => rectify(original, 0, size));
    }
    rejects(() => rectify(original, 4));
});

test('movement quantizes delta once, never clamps, validates bounds; parent rejects nonoverlap', () => {
    const original = freeze(area());
    assert.deepEqual(moveArea(original, -10.6, 2.6).points[0], { x: -1, y: 23 });
    assert.equal(moveArea(original, -20, -30).points[0].x, -10);
    rejects(() => moveArea(original, -20000, 0));
    rejects(() => moveArea(original, 0, NaN));
    rejects(() => moveArea(original, '1', 0));
    const doc = freeze(documentWith());
    const detached = moveArea(original, 1500, 0);
    rejects(() => updateArea(doc, original.id, detached));
});

test('view transforms retain subpixel precision, inverse coordinates and original geometry', () => {
    const doc = freeze(documentWith()), snapshot = clone(doc);
    for (const view of [{ scale: 0.01, x: -123, y: 72 }, { scale: 1, x: 0, y: 0 },
        { scale: 16, x: 50, y: -300 }]) {
        for (const p of [{ x: -10000, y: 10000 }, { x: 1.23, y: -4.56 }]) {
            const back = viewToImage(imageToView(p, view), view);
            assert.ok(Math.abs(back.x - p.x) < 1e-9);
            assert.ok(Math.abs(back.y - p.y) < 1e-9);
        }
        const screen = { x: 345.4, y: 121.2 }, anchor = viewToImage(screen, view);
        for (const factor of [1, 1.5, 1e-20, Number.MAX_VALUE]) {
            const zoomed = zoomAt(view, screen, factor), projected = imageToView(anchor, zoomed);
            assert.ok(zoomed.scale >= 0.01 && zoomed.scale <= 16);
            assert.ok(Math.abs(projected.x - screen.x) < 1e-9);
            assert.ok(Math.abs(projected.y - screen.y) < 1e-9);
        }
    }
    assert.deepEqual(doc, snapshot);
    // Only zoomAt's output is clamped. Coordinate inversion accepts any finite
    // positive camera scale (e.g. a fit-to-window camera supplied by the UI).
    assert.deepEqual(viewToImage({ x: 1, y: 2 }, { scale: 0.001, x: 0, y: 0 }), { x: 1000, y: 2000 });
    assert.deepEqual(imageToView({ x: 1, y: 2 }, { scale: 32, x: 0, y: 0 }), { x: 32, y: 64 });
    assert.deepEqual(viewToImage({ x: 1.23, y: 2.34 }), { x: 1.23, y: 2.34 });
    assert.equal(zoomAt({ scale: 1, x: 0, y: 0 }, { x: 0, y: 0 }, 1e-20).scale, 0.01);
    assert.equal(zoomAt({ scale: 1, x: 0, y: 0 }, { x: 0, y: 0 }, 1e20).scale, 16);
});

test('view APIs reject unsafe/nonfinite inputs and computed overflow', () => {
    for (const view of [null, {}, { scale: 0, x: 0, y: 0 }, { scale: -1, x: 0, y: 0 },
        { scale: Infinity, x: 0, y: 0 }, { scale: 1, x: Infinity, y: 0 },
        { scale: 1, x: 0, y: 0, extra: 1 }]) {
        rejects(() => viewToImage({ x: 0, y: 0 }, view));
        rejects(() => imageToView({ x: 0, y: 0 }, view));
        rejects(() => zoomAt(view, { x: 0, y: 0 }, 2));
    }
    for (const p of [null, {}, { x: '0', y: 0 }, { x: NaN, y: 0 }]) {
        rejects(() => viewToImage(p)); rejects(() => imageToView(p));
    }
    for (const factor of [0, -1, Infinity, NaN, '2']) {
        rejects(() => zoomAt({ scale: 1, x: 0, y: 0 }, { x: 0, y: 0 }, factor));
    }
    rejects(() => imageToView({ x: Number.MAX_VALUE, y: 0 }, { scale: 16, x: 0, y: 0 }));
    rejects(() => viewToImage({ x: Number.MAX_VALUE, y: 0 }, { scale: 0.01, x: 0, y: 0 }));
});

test('exact user fixture roundtrips at 1080×1114; zero-based indices normalize to one-based export', () => {
    const doc = importCsv(CSV, 1080, 1114);
    assert.equal(exportCsv(freeze(doc)), CSV);
    assert.deepEqual(Object.keys(doc), ['width', 'height', 'areas']);
    assert.deepEqual(doc.areas.map(a => a.id), ['area-1', 'area-2', 'area-3']);
    assert.ok(doc.areas.every(a => !Object.hasOwn(a, 'type')));
    const zero = changeField(changeField(changeField(CSV, 1, 0, '0'), 2, 0, '1'), 3, 0, '2');
    assert.equal(exportCsv(importCsv(zero, 1080, 1114)), CSV);
    assert.equal(exportCsv(importCsv(CSV.replaceAll('\n', '\r\n') + '\r\n', 1080, 1114)), CSV);
    assert.equal(exportCsv(importCsv(CSV + '\n', 1080, 1114)), CSV);
    const repeat = changeField(CSV, 3, 1, '1');
    assert.equal(exportCsv(importCsv(repeat, 1080, 1114)), repeat);
    const negative = `${HEADER}\n1,1,-10000,-10000,10000,-10000,10000,10000,-10000,10000,1`;
    assert.equal(exportCsv(importCsv(negative, 1080, 1114)), negative);
});

test('hostile CSV rejects non-image data, wrong columns/header, unsafe values, bad order and gaps', () => {
    for (const csv of ['', HEADER, HEADER + '\n', CSV + '\n\n', '\ufeff' + CSV,
        CSV.replace('TLx', 'tlx'), CSV.replace('Area,Source', 'Source,Area'),
        CSV.replace('\n2,', '\n\n2,'), CSV + ',0', CSV.slice(0, -2),
        CSV.replace('\n', '\r'), CSV.replace('0\n2', 'text\n2'),
        changeField(CSV, 2, 0, '1'), changeField(CSV, 3, 0, '4'),
        changeField(CSV, 1, 0, '2'), changeField(CSV, 2, 1, '3'),
        changeField(CSV, 1, 1, '0'), changeField(CSV, 1, 1, '129'),
        changeField(CSV, 1, 10, '2'), changeField(CSV, 1, 2, '-10001'), null, {}, []]) {
        rejects(() => importCsv(csv, 1080, 1114));
    }
    for (let field = 0; field < 11; field++) {
        for (const value of ['', 'NaN', 'Infinity', 'undefined', '1.5', '1e2', '0x10',
            ' 1', '1 ', '+1', '"1"', '=1+1', '9007199254740993', '9'.repeat(400)]) {
            rejects(() => importCsv(changeField(CSV, 1, field, value), 1080, 1114));
        }
    }
    rejects(() => importCsv(CSV, 100, 100));
    const duplicateId = documentWith([1, 1]); duplicateId.areas[1].id = 'area-1';
    rejects(() => exportCsv(duplicateId));
});

test('CSV accepts 128 areas and exactly 64 KiB, rejects 129 areas and oversized UTF-8', () => {
    const rows = Array.from({ length: 128 }, (_, index) =>
        `${index + 1},${index + 1},10,20,110,20,110,80,10,80,0`);
    const full = [HEADER, ...rows].join('\n');
    assert.equal(exportCsv(importCsv(full, 1200, 1200)), full);
    rejects(() => importCsv(full + '\n129,1,10,20,110,20,110,80,10,80,0', 1200, 1200));
    const single = [HEADER, rows[0]].join('\n');
    const padded = single.replace('\n1,', `\n${'0'.repeat(65536 - single.length)}1,`);
    assert.equal(Buffer.byteLength(padded), 65536);
    assert.equal(importCsv(padded, 1200, 1200).areas.length, 1);
    rejects(() => importCsv(padded + '\n', 1200, 1200));
    rejects(() => importCsv('é'.repeat(40000), 1200, 1200));
});