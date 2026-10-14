import Konva from 'konva';
import { clone, createDocument, validateDocument, addArea, updateArea, deleteArea,
    duplicateArea, reorderArea, isRectangle, sourceOptions, importCsv, exportCsv,
    moveArea, moveCorner, moveEdge, rectify, viewToImage, zoomAt } from './model.mjs';
import { loadLocalImage } from './media.mjs';

const $ = id => document.getElementById(id);
const viewport = $('viewport');
const colors = ['#88d2e4', '#efd187', '#c6b1f1', '#f7ae9b'];
let doc = null, media = null, selectedId = null, tool = 'select', mode = 'rectangle';
let view = { scale: 1, x: 0, y: 0 }, undo = [], redo = [], gesture = null;
let loadVersion = 0, dirty = false, ghost = null, rectCandidate = null;
let documentRevision = 0;
const pointers = new Map();
let suppressUntilRelease = false;
const stage = new Konva.Stage({ container: 'surface', width: 1, height: 1 });
const layer = new Konva.Layer();
stage.add(layer);
// Konva supplies drawing/hit testing, not canonical document state or gesture ownership.
const selected = () => doc?.areas.find(area => area.id === selectedId);
const modeFor = area => isRectangle(area) ? 'rectangle' : 'perspective';
const areaOpacity = area => area.background ? .12 : .2;
const editingDialogOpen = () => $('csv-dialog').open || $('rectify-dialog').open;
const primaryPointer = event => event.button === 0 || event.pointerType === 'touch';
const snapshot = () => ({ doc: doc && clone(doc), selectedId, mode });
function message(text, error = false) {
    $('status').textContent = text;
    $('status').dataset.error = String(error);
}
function guarded(action) {
    try { action(); }
    catch (error) { message(error.message, true); controls(); paint(); }
}
function pushHistory(before) {
    documentRevision++;
    undo.push(before);
    if (undo.length > 80) undo.shift();
    redo = []; dirty = true;
}
function commit(next, id = selectedId, nextMode = null) {
    validateDocument(next);
    const before = snapshot();
    const changed = JSON.stringify(doc) !== JSON.stringify(next);
    doc = next; selectedId = id;
    mode = nextMode ?? (selected() ? modeFor(selected()) : 'rectangle');
    if (changed || before.mode !== mode) pushHistory(before);
    controls(); paint();
}
function restore(value) {
    documentRevision++;
    doc = value.doc; selectedId = value.selectedId; mode = value.mode;
    controls(); paint();
}
function cancelGesture() {
    if (gesture?.before) {
        doc = gesture.before.doc; selectedId = gesture.before.selectedId; mode = gesture.before.mode;
    }
    gesture = null; ghost = null;
    controls(); paint();
}
function chooseTool(next) {
    cancelGesture(); tool = next;
    controls();
    const instructions = {
        draw: 'Arraste sobre a imagem para desenhar uma área. Escape cancela.',
        pan: 'Arraste para mover a tela. Duas pontas ampliam.',
        select: 'Selecione uma área ou desenhe outra.'
    };
    message(instructions[next]);
}
function selectArea(id, open = false) {
    const changed = selectedId !== id;
    selectedId = id;
    if (changed) mode = selected() ? modeFor(selected()) : 'rectangle';
    if (open) panel(true);
    controls(); paint();
}
function panel(open) {
    $('inspector').hidden = !open;
    $('properties').setAttribute('aria-expanded', String(open));
    requestAnimationFrame(() => { resize(); revealSelected(); });
}
function controls() {
    const area = selected();
    $('empty').hidden = Boolean(media);
    $('draw').disabled = !media || doc.areas.length >= 128;
    $('properties').disabled = !area;
    $('undo').disabled = undo.length === 0;
    $('redo').disabled = redo.length === 0;
    for (const name of ['select', 'draw', 'pan']) $(name).setAttribute('aria-pressed', String(tool === name));
    $('area-list').replaceChildren(...(doc?.areas ?? []).map((a, i) =>
        new Option(`${i + 1} · Source ${a.source}${a.background ? ' · atrás' : ''}`, a.id)));
    $('area-list').value = selectedId ?? '';
    const choices = area ? sourceOptions(doc, area.id) : [];
    $('source').replaceChildren(...choices.map(slot => new Option(`Source ${slot}`, String(slot))));
    if (area && !choices.includes(area.source)) {
        const current = new Option(`Source ${area.source} (original)`, String(area.source));
        current.disabled = true; $('source').prepend(current);
    }
    if (area) {
        $('source').value = String(area.source); $('background').checked = area.background;
        $('mode').value = mode;
        const p = area.points[Number($('corner').value)];
        $('x').value = p.x; $('y').value = p.y;
    } else if (!$('inspector').hidden) panel(false);
    for (const id of ['source','mode','background','corner','x','y','rectify','duplicate','delete','backward','forward']) $(id).disabled = !area;
    $('backward').disabled = !area || doc.areas[0]?.id === area.id;
    $('forward').disabled = !area || doc.areas.at(-1)?.id === area.id;
    $('zoom-value').textContent = `${Math.round(view.scale * 100)}%`;
}
function polygon(points, options) {
    return new Konva.Line({ points: points.flatMap(p => [p.x * view.scale + view.x, p.y * view.scale + view.y]), closed: true, ...options });
}
function paint() {
    layer.destroyChildren();
    if (media && doc) {
        layer.add(new Konva.Image({ image: media.display ?? media.image, x: view.x, y: view.y,
            width: doc.width * view.scale, height: doc.height * view.scale, listening: false }));
        const ordered = [...doc.areas.filter(a => a.background), ...doc.areas.filter(a => !a.background)];
        for (const area of ordered) {
            const active = area.id === selectedId, color = colors[(area.source - 1) % colors.length];
            const shape = polygon(area.points, { fill: color, opacity: areaOpacity(area),
                stroke: color, strokeWidth: active ? 3 : 1, hitStrokeWidth: 12, name: 'area', id: area.id });
            layer.add(shape);
            // Labels are guides only, never part of a template/export or a source photo preview.
            const center = area.points.reduce((sum, p) => ({ x: sum.x + p.x / 4, y: sum.y + p.y / 4 }), { x: 0, y: 0 });
            layer.add(new Konva.Text({ x: view.x + center.x * view.scale - 38,
                y: view.y + center.y * view.scale - 8, text: `Source ${area.source}`,
                fontSize: 13, fontStyle: 'bold', fill: '#161616', listening: false }));
            if (active && tool === 'select') {
                area.points.forEach((p, index) => {
                    const next = area.points[(index + 1) % 4];
                    layer.add(new Konva.Rect({ x: view.x + (p.x + next.x) / 2 * view.scale - 5,
                        y: view.y + (p.y + next.y) / 2 * view.scale - 5, width: 10, height: 10,
                        fill: color, stroke: '#fff', strokeWidth: 2, hitStrokeWidth: 16,
                        name: 'edge', id: `${index}` }));
                });
                area.points.forEach((p, index) => layer.add(new Konva.Circle({
                    x: view.x + p.x * view.scale, y: view.y + p.y * view.scale,
                    radius: 7, fill: color, stroke: '#fff', strokeWidth: 2, hitStrokeWidth: 18,
                    name: 'corner', id: `${index}` })));
            }
        }
        if (ghost) layer.add(polygon(ghost, { stroke: '#fff', strokeWidth: 2, dash: [6, 4], fill: '#ffffff22', listening: false }));
    }
    layer.batchDraw();
}
function resize() {
    const box = viewport.getBoundingClientRect();
    stage.size({ width: Math.max(1, box.width), height: Math.max(1, box.height) });
    paint();
}
function fit(includeAreas = false) {
    if (!doc) return;
    const points = [{ x: 0, y: 0 }, { x: doc.width, y: doc.height }, ...(includeAreas ? doc.areas.flatMap(a => a.points) : [])];
    const xs = points.map(p => p.x), ys = points.map(p => p.y);
    const x = Math.min(...xs), y = Math.min(...ys), width = Math.max(...xs) - x, height = Math.max(...ys) - y;
    const scale = Math.min((stage.width() - 36) / width, (stage.height() - 72) / height, 1);
    view = { scale: Math.max(.01, scale), x: (stage.width() - width * scale) / 2 - x * scale,
        y: (stage.height() - height * scale) / 2 - y * scale + 16 };
    controls(); paint();
}
function revealSelected() {
    const area = selected(); if (!area) return;
    const xs = area.points.map(p => view.x + p.x * view.scale), ys = area.points.map(p => view.y + p.y * view.scale);
    const cx = (Math.min(...xs) + Math.max(...xs)) / 2, cy = (Math.min(...ys) + Math.max(...ys)) / 2;
    if (cx < 24 || cx > stage.width() - 24) view.x += stage.width() / 2 - cx;
    if (cy < 50 || cy > stage.height() - 24) view.y += stage.height() / 2 - cy;
    paint();
}
function screen(event) {
    const b = viewport.getBoundingClientRect(); return { x: event.clientX - b.x, y: event.clientY - b.y };
}
function imagePoint(p) { return viewToImage(p, view); }
const distance = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
function pointerDown(event) {
    if (!primaryPointer(event)) return;
    if (editingDialogOpen()) return;
    const p = screen(event); pointers.set(event.pointerId, p);
    viewport.setPointerCapture(event.pointerId); viewport.focus({ preventScroll: true });
    if (pointers.size >= 2) {
        cancelGesture(); suppressUntilRelease = true;
        const [a, b] = [...pointers.values()];
        gesture = { kind: 'pinch', view: { ...view }, center: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }, distance: Math.max(1, distance(a, b)) };
        return;
    }
    if (suppressUntilRelease) return;
    if (tool === 'draw' && media) {
        gesture = { kind: 'draw', start: imagePoint(p), screen: p }; return;
    }
    const hit = stage.getIntersection(p);
    if (tool === 'select' && hit && ['corner','edge','area'].includes(hit.name())) {
        const kind = hit.name();
        if (kind === 'area') selectArea(hit.id());
        const area = selected();
        if (area) gesture = { kind: kind === 'area' ? 'move' : kind, index: Number(hit.id()),
            start: imagePoint(p), original: clone(area), before: snapshot(), pointer: event.pointerId };
    } else gesture = { kind: 'pan', start: p, view: { ...view } };
}
function pointerMove(event) {
    if (!pointers.has(event.pointerId)) return;
    const p = screen(event); pointers.set(event.pointerId, p);
    if (!gesture) return;
    if (gesture.kind === 'pinch') {
        if (pointers.size < 2) return;
        const [a, b] = [...pointers.values()];
        const center = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
        view = zoomAt(gesture.view, gesture.center, distance(a, b) / gesture.distance || 1);
        view.x += center.x - gesture.center.x; view.y += center.y - gesture.center.y;
        controls(); paint(); return;
    }
    if (suppressUntilRelease) return;
    if (gesture.kind === 'pan') {
        view = { ...gesture.view, x: gesture.view.x + p.x - gesture.start.x, y: gesture.view.y + p.y - gesture.start.y };
        paint(); return;
    }
    const target = imagePoint(p);
    if (gesture.kind === 'draw') {
        const a = gesture.start; ghost = [{ x: a.x, y: a.y }, { x: target.x, y: a.y }, target, { x: a.x, y: target.y }];
        paint(); return;
    }
    try {
        const { original, start, index, kind, before } = gesture;
        const delta = { x: target.x - start.x, y: target.y - start.y };
        let candidate;
        if (kind === 'move') candidate = moveArea(original, delta.x, delta.y);
        else if (kind === 'edge') candidate = moveEdge(original, index, delta, mode);
        else candidate = moveCorner(original, index, target, mode);
        doc = updateArea(before.doc, original.id, candidate);
        message('Solte para concluir. Escape cancela.'); controls(); paint();
    } catch (error) { message(error.message, true); }
}
function pointerUp(event) {
    if (!pointers.has(event.pointerId)) return;
    const end = screen(event); pointers.delete(event.pointerId);
    if (suppressUntilRelease) {
        if (pointers.size === 0) { suppressUntilRelease = false; gesture = null; }
        return;
    }
    const live = gesture; gesture = null; ghost = null;
    if (live?.kind === 'draw') {
        if (distance(live.screen, end) >= 4) guarded(() => {
            const to = imagePoint(end), from = live.start;
            const x = Math.round(Math.min(from.x, to.x)), y = Math.round(Math.min(from.y, to.y));
            const width = Math.round(Math.max(from.x, to.x)) - x, height = Math.round(Math.max(from.y, to.y)) - y;
            const next = addArea(doc, { x, y, width, height });
            tool = 'select'; commit(next, next.areas.at(-1).id, 'rectangle'); panel(true);
            message('Área criada. Ajuste os cantos, bordas ou coordenadas.');
        });
    } else if (live?.before && JSON.stringify(doc) !== JSON.stringify(live.before.doc)) {
        pushHistory(live.before); message('Área atualizada.');
    }
    controls(); paint();
}
viewport.addEventListener('pointerdown', pointerDown);
viewport.addEventListener('pointermove', pointerMove);
viewport.addEventListener('pointerup', pointerUp);
viewport.addEventListener('pointercancel', () => { cancelGesture(); suppressUntilRelease = pointers.size > 0; pointers.clear(); suppressUntilRelease = false; });
viewport.addEventListener('lostpointercapture', event => {
    if (pointers.has(event.pointerId)) { pointers.delete(event.pointerId); cancelGesture(); suppressUntilRelease = pointers.size > 0; }
});
viewport.addEventListener('wheel', event => {
    event.preventDefault(); cancelGesture();
    view = zoomAt(view, screen(event), event.deltaY < 0 ? 1.12 : 1 / 1.12); controls(); paint();
}, { passive: false });
window.addEventListener('blur', () => { cancelGesture(); pointers.clear(); suppressUntilRelease = false; });
function zoom(factor) {
    cancelGesture(); view = zoomAt(view, { x: stage.width() / 2, y: stage.height() / 2 }, factor); controls(); paint();
}
for (const name of ['select','draw','pan']) $(name).addEventListener('click', () => chooseTool(name));
$('properties').addEventListener('click', () => panel($('inspector').hidden));
$('close-panel').addEventListener('click', () => { panel(false); $('properties').focus(); });
$('zoom-in').addEventListener('click', () => zoom(1.25));
$('zoom-out').addEventListener('click', () => zoom(.8));
$('zoom-value').addEventListener('click', () => zoom(1 / view.scale));
$('fit').addEventListener('click', () => { cancelGesture(); fit(); });
$('fit-areas').addEventListener('click', () => { cancelGesture(); fit(true); });
$('area-list').addEventListener('change', () => selectArea($('area-list').value));
$('corner').addEventListener('change', controls);
function changeArea(transform, nextMode = mode) {
    guarded(() => { const a = selected(); if (a) commit(updateArea(doc, a.id, transform(clone(a))), a.id, nextMode); });
}
$('source').addEventListener('change', () => changeArea(a => {
    const slot = Number($('source').value);
    if (!sourceOptions(doc, a.id).includes(slot)) throw new Error('Source indisponível para esta área.');
    return { ...a, source: slot };
}));
$('background').addEventListener('change', () => changeArea(a => ({ ...a, background: $('background').checked })));
for (const id of ['x','y']) $(id).addEventListener('change', () => changeArea(a => {
    if (!$('x').value.trim() || !$('y').value.trim()) throw new Error('Informe coordenadas inteiras.');
    const target = { x: Number($('x').value), y: Number($('y').value) };
    if (!Number.isInteger(target.x) || !Number.isInteger(target.y)) throw new Error('Informe coordenadas inteiras.');
    return moveCorner(a, Number($('corner').value), target, mode);
}));
document.querySelectorAll('[data-nudge]').forEach(button => button.addEventListener('click', () => {
    const [x, y] = button.dataset.nudge.split(',').map(Number); changeArea(a => moveArea(a, x, y));
}));
$('duplicate').addEventListener('click', () => guarded(() => {
    const index = doc.areas.findIndex(a => a.id === selectedId), next = duplicateArea(doc, selectedId);
    commit(next, next.areas[index + 1].id);
}));
$('delete').addEventListener('click', () => guarded(() => {
    const next = deleteArea(doc, selectedId); commit(next, next.areas[0]?.id ?? null);
}));
for (const [name, direction] of [['backward',-1],['forward',1]]) $(name).addEventListener('click', () => guarded(() => commit(reorderArea(doc, selectedId, direction), selectedId, mode)));
$('undo').addEventListener('click', () => {
    cancelGesture();
    if (!undo.length) return;
    redo.push(snapshot()); restore(undo.pop()); dirty = true;
});
$('redo').addEventListener('click', () => {
    cancelGesture();
    if (!redo.length) return;
    undo.push(snapshot()); restore(redo.pop()); dirty = true;
});
document.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
        cancelGesture();
        if (tool === 'draw') chooseTool('select');
        return;
    }
    if (event.target.closest('input,textarea,select,button') || editingDialogOpen()) return;
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') {
        event.preventDefault(); $(event.shiftKey ? 'redo' : 'undo').click(); return;
    }
    const delta = { ArrowLeft: [-1,0], ArrowRight: [1,0], ArrowUp: [0,-1], ArrowDown: [0,1] }[event.key];
    if (delta && selected()) { event.preventDefault(); changeArea(a => moveArea(a, delta[0] * (event.shiftKey ? 10 : 1), delta[1] * (event.shiftKey ? 10 : 1))); }
});
async function load(file) {
    if (!file) return;
    if (dirty && !confirm('Trocar a imagem descarta as áreas atuais. Continuar?')) return;
    const version = ++loadVersion; cancelGesture();
    const revision = documentRevision;
    message('Abrindo imagem local…');
    let incoming;
    try {
        incoming = await loadLocalImage(file);
        if (version !== loadVersion) { incoming.dispose(); return; }
        if (revision !== documentRevision || gesture?.before || gesture?.kind === 'draw'
            || editingDialogOpen()) {
            incoming.dispose();
            message('As alterações atuais foram preservadas. Escolha a nova imagem novamente para confirmar a troca.', true);
            return;
        }
        // Retain at most one downsampled display raster; canonical data stays in original pixels.
        const display = document.createElement('canvas'), scale = Math.min(1, 2048 / Math.max(incoming.width, incoming.height));
        display.width = Math.max(1, Math.round(incoming.width * scale)); display.height = Math.max(1, Math.round(incoming.height * scale));
        display.getContext('2d').drawImage(incoming.image, 0, 0, display.width, display.height);
        incoming.display = display;
        cancelGesture(); pointers.clear(); suppressUntilRelease = false;
        media?.dispose(); media = incoming; doc = createDocument(incoming.width, incoming.height);
        selectedId = null; undo = []; redo = []; dirty = false; mode = 'rectangle'; tool = 'select';
        $('filename').textContent = file.name; $('filename').title = `${file.name} · ${doc.width} × ${doc.height} px`;
        controls(); resize(); fit();
        message('Imagem local aberta. Use + Área para começar. Nenhum arquivo foi enviado.');
    } catch (error) {
        incoming?.dispose(); if (version === loadVersion) message(error.message, true);
    }
}
$('image-file').addEventListener('change', event => { const file = event.target.files[0]; event.target.value = ''; void load(file); });
window.addEventListener('beforeunload', event => {
    if (dirty) { event.preventDefault(); event.returnValue = ''; }
});
window.addEventListener('pagehide', event => { if (!event.persisted) { loadVersion++; media?.dispose(); } });
$('csv-open').addEventListener('click', () => {
    cancelGesture(); $('csv-error').textContent = '';
    $('csv').value = doc?.areas.length ? exportCsv(doc) : '';
    $('csv-import').disabled = !doc; $('csv-copy').disabled = !doc?.areas.length; $('csv-download').disabled = !doc?.areas.length;
    $('csv-dialog').showModal();
});
$('csv-import').addEventListener('click', () => {
    try {
        const next = importCsv($('csv').value, doc.width, doc.height);
        if (doc.areas.length && !confirm('Substituir as áreas pelo CSV? A imagem será preservada.')) return;
        commit(next, next.areas[0].id); $('csv-dialog').close(); panel(true);
        message('CSV importado. Coordenadas originais preservadas.');
    } catch (error) { $('csv-error').textContent = error.message; }
});
$('csv-copy').addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(exportCsv(doc)); $('csv-error').textContent = 'CSV copiado.'; }
    catch { $('csv').value = exportCsv(doc); $('csv').focus(); $('csv').select(); $('csv-error').textContent = 'Selecione e copie o CSV; a área de transferência não está disponível.'; }
});
$('csv-download').addEventListener('click', () => {
    const url = URL.createObjectURL(new Blob([exportCsv(doc)], { type: 'text/csv;charset=utf-8' }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'template.csv'; anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
});
function rectPreview() {
    try {
        const width = $('rect-width').value, height = $('rect-height').value;
        const size = width || height ? { width: Number(width), height: Number(height) } : undefined;
        rectCandidate = rectify(selected(), Number($('anchor').value), size);
        updateArea(doc, selectedId, rectCandidate);
        ghost = rectCandidate.points; $('rect-apply').disabled = false;
        $('rect-result').textContent = 'Canto fixo preservado. Aplicar cria uma única ação reversível.';
    } catch (error) { rectCandidate = null; ghost = null; $('rect-apply').disabled = true; $('rect-result').textContent = error.message; }
    paint();
}
function openRectify() {
    cancelGesture(); $('anchor').value = $('corner').value;
    $('rect-width').value = ''; $('rect-height').value = '';
    $('rectify-dialog').showModal(); rectPreview();
}
$('mode').addEventListener('change', () => {
    if ($('mode').value === 'rectangle' && !isRectangle(selected())) { $('mode').value = mode; openRectify(); }
    else {
        const before = snapshot(); mode = $('mode').value;
        if (mode !== before.mode) pushHistory(before);
        controls(); paint();
    }
});
$('rectify').addEventListener('click', openRectify);
for (const id of ['anchor','rect-width','rect-height']) $(id).addEventListener('input', rectPreview);
$('rect-cancel').addEventListener('click', () => $('rectify-dialog').close());
$('rect-apply').addEventListener('click', () => guarded(() => {
    if (!rectCandidate) return;
    commit(updateArea(doc, selectedId, rectCandidate), selectedId, 'rectangle'); $('rectify-dialog').close();
}));
$('rectify-dialog').addEventListener('close', () => { ghost = null; rectCandidate = null; controls(); paint(); $('rectify').focus(); });
function height() { document.documentElement.style.setProperty('--app-height', `${window.visualViewport?.height ?? window.innerHeight}px`); }
height();
new ResizeObserver(() => { cancelGesture(); resize(); revealSelected(); }).observe(viewport);
window.addEventListener('resize', () => { height(); cancelGesture(); });
window.visualViewport?.addEventListener('resize', height);
controls(); resize();