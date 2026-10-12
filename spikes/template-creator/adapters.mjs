import Konva from 'konva';
import Moveable from 'moveable';
import { FONT_FAMILIES, clone, layerOrder, layoutText, moveArea, moveCorner,
    rectPoints, resolveText } from './model.mjs';

// Browser-only previews: no image export, rectification, HTML interpolation or I/O.
const SELECTED = '#bdadff';
const corners = area => area.type === 'image' ? area.points : rectPoints(area.rect);
const sourceOf = (doc, area) => doc.sources.find(source => source.slot === area.source);
const font = (size, family, weight) => `${weight} ${size}px "${family}"`;
const canvas = () => document.createElement('canvas');

function textLayout(doc, area, context) {
    return layoutText(resolveText(area.text, sourceOf(doc, area).description), area.rect,
        area.style, (text, size, family, weight) => {
            context.font = font(size, family, weight);
            return context.measureText(text).width;
        });
}

function template(doc, target = canvas()) {
    target.width = doc.width;
    target.height = doc.height;
    const c = target.getContext('2d');
    c.fillStyle = '#20272e';
    c.fillRect(0, 0, doc.width, doc.height);
    c.fillStyle = doc.scene === 'chat' ? '#1f3037' : '#34404a';
    c.beginPath();
    c.roundRect(40, 100, 820, doc.scene === 'chat' ? 250 : 130, 20);
    c.fill();
    c.fillStyle = '#aab6be';
    c.font = 'bold 28px Arial';
    c.textBaseline = 'top';
    c.fillText(doc.scene === 'chat' ? 'Perfil com mensagem' : 'Fotos e legendas', 40, 40);
    // Fixed synthetic cutouts, not holes inferred from edited area geometry.
    if (doc.scene === 'chat') {
        c.clearRect(85, 150, 110, 110);
        c.clearRect(225, 145, 580, 160);
    } else {
        for (let slot = 0; slot < 3; slot++) c.clearRect(80 + slot * 275, 250, 200, 200);
    }
    return target;
}

function paintCanvas(target, doc, area) {
    const size = area.type === 'image' ? { width: 100, height: 100 } : area.rect;
    // Bound raster allocation; CSS retains the full canonical world dimensions.
    const ratio = Math.min(1, 2048 / size.width, 2048 / size.height);
    target.width = Math.max(1, Math.ceil(size.width * ratio));
    target.height = Math.max(1, Math.ceil(size.height * ratio));
    const c = target.getContext('2d');
    c.scale(ratio, ratio);
    c.textBaseline = 'top';
    if (area.type === 'image') {
        c.fillStyle = sourceOf(doc, area).color;
        c.fillRect(0, 0, 100, 100);
        c.font = 'bold 12px Arial';
        c.fillStyle = '#16232b';
        c.textAlign = 'center';
        c.fillText(`Source ${area.source}`, 50, 44);
        return;
    }
    const { lines, fontSize, lineHeight } = textLayout(doc, area, c);
    const style = area.style;
    const padding = 4 + style.outlineWidth;
    c.font = font(fontSize, FONT_FAMILIES[style.font], style.weight);
    c.textAlign = style.align;
    c.fillStyle = style.fill;
    c.strokeStyle = style.outline;
    c.lineWidth = style.outlineWidth * 2;
    c.lineJoin = 'round';
    let x = area.rect.width / 2;
    if (style.align === 'left') x = padding;
    if (style.align === 'right') x = area.rect.width - padding;
    lines.forEach((line, index) => {
        const y = padding + (lineHeight - fontSize) / 2 + index * lineHeight;
        if (style.outlineWidth) c.strokeText(line, x, y);
        c.fillText(line, x, y);
    });
}

// Closed-form, bounded 8-DOF homography: local 100×100 -> ordered world quad.
function quadTransform(points) {
    const [a, b, c, d] = points;
    const dx = a.x - b.x + c.x - d.x, dy = a.y - b.y + c.y - d.y;
    const bx = b.x - c.x, by = b.y - c.y, dx2 = d.x - c.x, dy2 = d.y - c.y;
    const determinant = bx * dy2 - dx2 * by;
    if (Math.abs(determinant) < 1e-9) throw new Error('Quadrilátero degenerado.');
    const g = (dx * dy2 - dx2 * dy) / determinant;
    const h = (bx * dy - dx * by) / determinant;
    const matrix = [(b.x - a.x + g * b.x) / 100, (b.y - a.y + g * b.y) / 100, 0, g / 100,
        (d.x - a.x + h * d.x) / 100, (d.y - a.y + h * d.y) / 100, 0, h / 100,
        0, 0, 1, 0, a.x, a.y, 0, 1];
    if (!matrix.every(Number.isFinite)) throw new Error('Transformação inválida.');
    return `matrix3d(${matrix.join(',')})`;
}

function transformedPoint(matrix, x, y) {
    const w = matrix.m14 * x + matrix.m24 * y + matrix.m44;
    const point = { x: (matrix.m11 * x + matrix.m21 * y + matrix.m41) / w,
        y: (matrix.m12 * x + matrix.m22 * y + matrix.m42) / w };
    if (Math.abs(w) < 1e-9 || !Object.values(point).every(Number.isFinite)) {
        throw new Error('Transformação inválida.');
    }
    return { x: Math.round(point.x), y: Math.round(point.y) };
}

/** cancel() stops native editing/restores local display; the caller restores its
 * document with hooks.cancel(). Native pointer cancellation does both itself.
 * render() deliberately ignores live gestures; hooks.change must return boolean.
 */
export function createAdapter(engine, root, hooks) {
    if (!['konva', 'moveable'].includes(engine)) throw new Error('Editor inválido.');
    let snapshot, live = null, destroyed = false;
    const listeners = new AbortController();
    const ctx = {
        state: () => hooks.getState() ?? snapshot,
        live: () => live,
        begin(id, kind) {
            if (live) return live.original.id === id && live.kind === kind;
            const state = ctx.state();
            const area = state.document.areas.find(item => item.id === id);
            if (destroyed || state.pan || state.clean || !area) return false;
            live = { original: clone(area), accepted: clone(area), kind, view: clone(state.view) };
            hooks.begin(); // Selection may render synchronously: guard is already active.
            if (ctx.state().selectedId !== id) {
                hooks.select(id);
                backend.focus(id);
            }
            return true;
        },
        accept(build) {
            if (!live || ctx.state().pan) return false;
            let next;
            try { next = build(); } catch { return false; }
            if (hooks.change(next) !== true) return false;
            live.accepted = clone(next);
            return true;
        },
        end(kind) {
            if (live?.kind !== kind) return;
            backend.paint(live.accepted); // Reset native transient transforms, even after rejection.
            live = null;
            hooks.end();
            api.render(ctx.state());
        },
        select(id) {
            if (!live && !ctx.state().pan && ctx.state().selectedId !== id) hooks.select(id);
        },
    };
    const backend = engine === 'konva' ? konvaAdapter(root, ctx) : moveableAdapter(root, ctx);
    const api = {
        render(state) {
            if (destroyed) return;
            snapshot = state;
            if (!live) backend.render(state);
        },
        hit(point) { return backend.hit?.(point) ?? false; },
        cancel() {
            const original = live?.original;
            live = null; // stopDrag may synchronously emit an end event.
            backend.stop();
            if (original) backend.paint(original);
        },
        destroy() {
            if (destroyed) return;
            api.cancel();
            destroyed = true;
            listeners.abort();
            backend.destroy();
        },
    };
    const abort = () => {
        if (!live) return;
        api.cancel();
        hooks.cancel();
        api.render(ctx.state());
    };
    root.addEventListener('pointercancel', abort, { capture: true, signal: listeners.signal });
    root.addEventListener('touchstart', event => {
        if (event.touches.length > 1) {
            abort();
            event.stopPropagation(); // Parent capture has priority; prevent native pinch editing.
        }
    }, { capture: true, signal: listeners.signal });
    return api;
}

function konvaAdapter(root, ctx) {
    const stage = new Konva.Stage({ container: root, width: 1, height: 1 });
    const sceneLayer = new Konva.Layer(), overlayLayer = new Konva.Layer();
    const world = new Konva.Group(), overlay = new Konva.Group();
    stage.add(sceneLayer, overlayLayer);
    sceneLayer.add(world);
    overlayLayer.add(overlay);
    const measure = canvas().getContext('2d');
    const records = new Map();
    let state, selected, handles = [];
    const draw = () => { sceneLayer.batchDraw(); overlayLayer.batchDraw(); };
    function updateHandles(area) {
        corners(area).forEach((point, index) => handles[index]?.position(point));
    }
    function paint(area) {
        const record = records.get(area.id);
        if (!record) return;
        record.group.position({ x: 0, y: 0 });
        const points = corners(area), color = sourceOf(state.document, area).color;
        const strokeWidth = (area.id === selected ? 2 : 1) / state.view.scale;
        record.line.setAttrs({ points: points.flatMap(point => [point.x, point.y]),
            fill: area.type === 'image' ? color : 'rgba(0,0,0,0)',
            stroke: area.id === selected ? SELECTED : color,
            strokeWidth: state.clean ? 0 : strokeWidth });
        if (area.type === 'image') {
            const x = points.reduce((sum, point) => sum + point.x, 0) / 4;
            const y = points.reduce((sum, point) => sum + point.y, 0) / 4;
            record.content.setAttrs({ x: x - 80, y: y - 10, width: 160,
                text: `Source ${area.source}` });
            record.clip.clipFunc(c => {
                c.beginPath(); c.moveTo(points[0].x, points[0].y);
                points.slice(1).forEach(point => c.lineTo(point.x, point.y)); c.closePath();
            });
        } else {
            const layout = textLayout(state.document, area, measure);
            const padding = 4 + area.style.outlineWidth;
            measure.font = font(layout.fontSize, FONT_FAMILIES[area.style.font], area.style.weight);
            // Native wrap:none still truncates wide lines. Only the outer clip may do so.
            const width = layout.lines.reduce((maximum, line) => Math.max(maximum,
                measure.measureText(line).width + 2 * padding + 0.01), area.rect.width);
            let x = area.rect.x;
            if (area.style.align === 'center') x += (area.rect.width - width) / 2;
            if (area.style.align === 'right') x += area.rect.width - width;
            record.clip.setAttrs({ clipX: area.rect.x, clipY: area.rect.y,
                clipWidth: area.rect.width, clipHeight: area.rect.height });
            record.content.setAttrs({ x, y: area.rect.y, width,
                height: 'auto', text: layout.lines.join('\n'),
                fontFamily: FONT_FAMILIES[area.style.font], fontSize: layout.fontSize,
                fontStyle: area.style.weight, lineHeight: area.style.lineHeight,
                align: area.style.align, padding,
                fill: area.style.fill, stroke: area.style.outline,
                strokeWidth: area.style.outlineWidth * 2, fillAfterStrokeEnabled: true });
        }
        if (area.id === selected) updateHandles(area);
        draw();
    }
    function focus(id) {
        if (selected === id) return;
        selected = id;
        overlay.destroyChildren();
        handles = [];
        for (const record of records.values()) {
            const area = ctx.state().document.areas.find(item => item.id === record.group.id());
            if (area) paint(area);
        }
        const area = ctx.state().document.areas.find(item => item.id === id);
        if (!area || state.clean || state.pan) return;
        handles = corners(area).map((point, index) => {
            const handle = new Konva.Circle({ ...point, id: `${id}-corner-${index}`,
                name: 'corner-handle', radius: 8 / state.view.scale, draggable: true,
                fill: '#9785ee', stroke: '#ffffff', strokeWidth: 2 / state.view.scale,
                hitFunc(c, shape) {
                    c.beginPath(); c.arc(0, 0, 22 / state.view.scale, 0, Math.PI * 2);
                    c.closePath(); c.fillShape(shape);
                } });
            handle.on('dragstart', () => { if (!ctx.begin(id, 'corner')) handle.stopDrag(); });
            handle.on('dragmove', () => {
                const live = ctx.live();
                if (live?.kind !== 'corner') return;
                ctx.accept(() => moveCorner(live.original, index, handle.position()));
                paint(live.accepted); // Never replace the actively dragged circle.
            });
            handle.on('dragend', () => ctx.end('corner'));
            overlay.add(handle);
            return handle;
        });
        draw();
    }
    return {
        focus, paint,
        hit(point) { return Boolean(stage.getIntersection(point)); },
        render(next) {
            state = next;
            const bounds = root.parentElement.getBoundingClientRect();
            stage.size({ width: bounds.width, height: bounds.height });
            for (const group of [world, overlay]) group.setAttrs({ x: state.view.x, y: state.view.y,
                scaleX: state.view.scale, scaleY: state.view.scale });
            world.destroyChildren(); overlay.destroyChildren(); records.clear(); handles = [];
            selected = undefined;
            const templateIndex = state.document.areas.filter(area => area.background).length;
            for (const [index, id] of layerOrder(state.document).entries()) {
                if (index === templateIndex) {
                    world.add(new Konva.Image({ image: template(state.document), listening: false }));
                    continue;
                }
                const area = state.document.areas.find(item => item.id === id);
                const group = new Konva.Group({ id, name: `area ${area.type}-region`,
                    draggable: !state.pan && !state.clean });
                const line = new Konva.Line({ closed: true, lineJoin: 'round' });
                const clip = new Konva.Group({ listening: false });
                const content = new Konva.Text({ wrap: 'none', align: 'center',
                    fill: '#16232b', fontFamily: 'Arial', fontSize: 22, fontStyle: 'bold' });
                clip.add(content); group.add(line, clip); world.add(group);
                records.set(id, { group, line, clip, content });
                group.on('click tap', () => ctx.select(id));
                group.on('dragstart', () => { if (!ctx.begin(id, 'drag')) group.stopDrag(); });
                group.on('dragmove', () => {
                    const live = ctx.live();
                    if (live?.kind !== 'drag') return;
                    ctx.accept(() => moveArea(live.original, group.x(), group.y()));
                    const a = corners(live.accepted)[0], b = corners(live.original)[0];
                    group.position({ x: a.x - b.x, y: a.y - b.y });
                    updateHandles(live.accepted); draw();
                });
                group.on('dragend', () => ctx.end('drag'));
                paint(area);
            }
            focus(state.selectedId);
            draw();
        },
        stop() {
            for (const { group } of records.values()) group.stopDrag();
            handles.forEach(handle => handle.stopDrag());
        },
        destroy() { stage.destroy(); records.clear(); handles = []; },
    };
}

function moveableAdapter(root, ctx) {
    const world = document.createElement('div');
    world.className = 'dom-world';
    world.style.pointerEvents = 'none';
    const backdrop = canvas();
    backdrop.className = 'dom-template';
    root.append(world);
    const records = new Map(), listeners = new AbortController();
    // Controls live in an unscaled container: zoom is UI magnification, NOT camera scale.
    const editor = new Moveable(root, { container: root, rootContainer: root, zoom: 1,
        origin: false, pinchable: false, keepRatio: false, throttleDrag: 0, throttleResize: 0,
        checkResizableError: false, renderDirections: ['nw', 'ne', 'se', 'sw'] });
    let state, selected, pending = null;
    function releasePending(token = pending) {
        if (!token || pending !== token) return;
        pending = null;
        if (!token.started && ctx.live() === token.live && token.live?.kind === 'drag') {
            ctx.end('drag');
        }
    }
    function decorate(area) {
        const target = records.get(area.id)?.target;
        if (!target) return;
        const color = area.id === selected ? SELECTED : sourceOf(state.document, area).color;
        const width = (area.id === selected ? 2 : 1) / state.view.scale;
        target.style.outline = state.clean ? 'none' : `${width}px solid ${color}`;
        target.classList.toggle('selected', area.id === selected && !state.clean);
    }
    function paint(area) {
        const record = records.get(area.id);
        if (!record) return;
        const { target, image } = record;
        const size = area.type === 'image' ? { width: 100, height: 100 } : area.rect;
        Object.assign(target.style, { left: '0px', top: '0px', transformOrigin: '0 0',
            width: `${size.width}px`, height: `${size.height}px`,
            transform: area.type === 'image' ? quadTransform(area.points)
                : `translate(${area.rect.x}px,${area.rect.y}px)`,
            cursor: state.pan || state.clean ? 'default' : 'move' });
        decorate(area);
        paintCanvas(image, state.document, area);
    }
    function focus(id) {
        selected = id;
        ctx.state().document.areas.forEach(decorate);
        const area = ctx.state().document.areas.find(item => item.id === id);
        const target = state.clean || state.pan ? null : records.get(id)?.target ?? null;
        editor.target = target;
        editor.draggable = Boolean(target);
        editor.warpable = Boolean(target && area?.type === 'image');
        editor.resizable = Boolean(target && area?.type === 'text');
        editor.updateRect();
    }
    function start(event, kind) {
        if (!ctx.begin(event.target.dataset.areaId, kind)) { event.stop(); return false; }
        return true;
    }
    editor.on('dragStart', event => {
        if (!start(event, 'drag')) return false;
        event.set([0, 0]);
        ctx.live().pointer = [event.clientX, event.clientY];
        if (pending?.target === event.target && pending.live === ctx.live()) pending.started = true;
    });
    editor.on('drag', event => {
        const live = ctx.live();
        if (live?.kind !== 'drag') return;
        // Client delta / captured scale also works for a perspective target; no double inverse.
        ctx.accept(() => moveArea(live.original,
            (event.clientX - live.pointer[0]) / live.view.scale,
            (event.clientY - live.pointer[1]) / live.view.scale));
        paint(live.accepted);
    });
    editor.on('warpStart', event => {
        if (!start(event, 'warp')) return false;
        event.setTransform(event.target.style.transform, 0);
    });
    editor.on('warp', event => {
        const live = ctx.live();
        if (live?.kind !== 'warp') return;
        event.target.style.transform = event.transform;
        ctx.accept(() => {
            const matrix = new DOMMatrix(event.transform);
            const area = clone(live.original);
            area.points = [[0, 0], [100, 0], [100, 100], [0, 100]]
                .map(([x, y]) => transformedPoint(matrix, x, y));
            return area;
        });
        paint(live.accepted); // Reject => restore the last accepted, quantized homography.
    });
    editor.on('resizeStart', event => {
        if (!start(event, 'resize')) return false;
        event.set([ctx.live().original.rect.width, ctx.live().original.rect.height]);
        event.setMin([1, 1]); event.setOrigin(['0px', '0px']);
        event.dragStart?.set([ctx.live().original.rect.x, ctx.live().original.rect.y]);
    });
    editor.on('resize', event => {
        const live = ctx.live();
        if (live?.kind !== 'resize') return;
        ctx.accept(() => {
            const area = clone(live.original);
            const position = transformedPoint(new DOMMatrix(event.drag.transform), 0, 0);
            // Moveable already inverts the parent CSS camera; width/height are world pixels.
            area.rect = { ...position, width: Math.round(event.width), height: Math.round(event.height) };
            return area;
        });
        paint(live.accepted);
    });
    for (const kind of ['drag', 'warp', 'resize']) editor.on(`${kind}End`, () => ctx.end(kind));
    // Start through the native engine for ANY area, including first-drag selection.
    // Capture and transaction guard precede hooks.select; no detached active targets.
    const press = event => {
        const target = event.target.closest?.('[data-area-id]');
        const current = ctx.state();
        if (!target || !world.contains(target) || (event.button !== undefined && event.button !== 0)
            || event.touches?.length > 1 || current.pan || ctx.live()) return;
        const id = target.dataset.areaId;
        if (current.clean) { ctx.select(id); return; }
        const token = current.selectedId !== id ? { target, live: null, started: false } : null;
        if (token) {
            pending = token;
            // Register BEFORE begin/focus changes the asynchronously committed native target.
            editor.waitToChangeTarget().then(() => {
                if (pending !== token) return;
                const live = ctx.live(), current = ctx.state();
                if (!live || live !== token.live || live.original.id !== id || live.kind !== 'drag'
                    || current.selectedId !== id || current.pan || current.clean
                    || records.get(id)?.target !== target || !world.contains(target)) {
                    releasePending(token);
                    return;
                }
                editor.dragStart(event, target);
                releasePending(token); // A rejected native start must also close the edit guard.
            }).catch(() => {
                if (pending !== token) return;
                pending = null;
                // Contain both promise/native failures and teardown errors; never start later.
                try {
                    try { editor.stopDrag(); } finally {
                        if (ctx.live() === token.live && token.live?.kind === 'drag') ctx.end('drag');
                    }
                } catch { /* Teardown failure must not become an unhandled rejection. */ }
            });
        }
        if (!ctx.begin(id, 'drag')) { releasePending(token); return; }
        if (token) token.live = ctx.live();
        event.stopPropagation();
        if (!token) editor.dragStart(event, target);
    };
    for (const type of ['mousedown', 'touchstart']) {
        root.addEventListener(type, press, { capture: true, signal: listeners.signal });
    }
    for (const type of ['mouseup', 'touchend']) {
        window.addEventListener(type, () => releasePending(), { capture: true, signal: listeners.signal });
    }
    return {
        focus, paint,
        render(next) {
            state = next;
            world.style.transform = `translate(${state.view.x}px,${state.view.y}px) scale(${state.view.scale})`;
            world.style.width = `${state.document.width}px`; world.style.height = `${state.document.height}px`;
            template(state.document, backdrop);
            for (const [id, record] of records) {
                if (!state.document.areas.some(area => area.id === id)) { record.target.remove(); records.delete(id); }
            }
            const templateIndex = state.document.areas.filter(area => area.background).length;
            for (const [index, id] of layerOrder(state.document).entries()) {
                if (index === templateIndex) { world.append(backdrop); continue; }
                const area = state.document.areas.find(item => item.id === id);
                if (!records.has(id)) {
                    const target = document.createElement('div'), image = canvas();
                    target.className = `dom-area ${area.type}-region`;
                    target.dataset.areaId = id; target.style.pointerEvents = 'auto';
                    target.append(image); records.set(id, { target, image });
                }
                world.append(records.get(id).target); paint(area);
            }
            focus(state.selectedId);
        },
        stop() { pending = null; editor.stopDrag(); },
        destroy() { pending = null; listeners.abort(); editor.destroy(); world.remove(); records.clear(); },
    };
}