import { createAdapter } from './adapters.mjs';
import { loadLocalImage } from './media.mjs';
import { addDrawnArea, clone, createEmptyDocument, createScene, decodeDocument, encodeDocument,
  exportImageCsv, imageToView, importImageCsv, layerOrder, layoutText, moveArea, moveCorner,
  nextImageSource, rectPoints, resolveText, sourceOptions, validateDocument, viewToImage, zoomAt } from './model.mjs';

const $ = id => document.getElementById(id);
const viewport = $('viewport'), surface = $('surface');
const measure = document.createElement('canvas').getContext('2d');
let state = { document: createEmptyDocument(900, 620), selectedId: null, templateImage: null,
  tool: 'select', view: { scale: 1, x: 0, y: 0 }, clean: false, pan: false };
let adapter, transaction = null, renderCount = 0, lastRender = 0;
const history = [], future = [], pointers = new Map();
let cameraGesture = null, suppressUntilRelease = false;
let pendingDraw = null, draft = null, templateMedia = null, templateName = '', loadVersion = 0;
const selected = () => state.document.areas.find(area => area.id === state.selectedId);
const areaPoints = area => area.type === 'image' ? area.points : rectPoints(area.rect);
const canDraw = () => Boolean(state.templateImage) || state.document.scene !== 'custom';

function status(message, error = false) { $('status').textContent = message; $('status').dataset.error = String(error); }
function imageStatus(message, error = false) { $('image-status').textContent = message; $('image-status').dataset.error = String(error); }
function imageInfo() {
  let message = 'Nenhuma imagem carregada.';
  if (state.templateImage) message = `${templateName} · ${state.document.width} × ${state.document.height} px`;
  else if (state.document.scene !== 'custom') message = `Demo sintética · ${state.document.width} × ${state.document.height} px`;
  $('image-info').textContent = message;
}
function toolControls() {
  viewport.dataset.tool = state.tool;
  for (const [id, tool] of [['select-tool','select'], ['add-image','image'], ['add-text','text']]) {
    $(id).setAttribute('aria-pressed', String(state.tool === tool));
    if (tool !== 'select') $(id).disabled = !canDraw() || state.document.areas.length >= 128;
  }
  $('empty-state').hidden = canDraw();
  $('csv-import').disabled = !canDraw();
}
function cancelDrawing() {
  if (draft) suppressUntilRelease = pointers.size > 0;
  draft = null; pendingDraw = null; state.tool = 'select'; $('draw-preview').hidden = true;
  toolControls();
}
function remember(before) {
  if (JSON.stringify(before) === JSON.stringify(state.document)) return;
  history.push(before);
  if (history.length > 50) history.shift();
  future.length = 0;
}
function updateDocument(candidate, before = state.document, duringGesture = false) {
  try { validateDocument(candidate); state.document = candidate; }
  catch (error) { status(error.message + ' A última posição válida foi mantida.', true); return false; }
  if (!duringGesture) remember(before);
  inspector();
  if (!duringGesture) render();
  return true;
}
function editArea(change) {
  const area = selected(); if (!area) return;
  const before = clone(state.document), next = clone(state.document);
  try { next.areas[next.areas.findIndex(item => item.id === area.id)] = change(clone(area)); }
  catch (error) { status(error.message, true); inspector(); return; }
  if (updateDocument(next, before)) status('Alteração salva no documento local.');
  else inspector();
}
const hooks = {
  getState: () => state,
  select(id) { state.selectedId = id; inspector(); if (!transaction) render(); },
  begin() { transaction ??= clone(state.document); },
  change(area) {
    const next = clone(state.document);
    const index = next.areas.findIndex(item => item.id === area.id);
    if (index < 0) return false;
    next.areas[index] = area;
    return updateDocument(next, state.document, true);
  },
  end() {
    if (transaction) remember(transaction);
    transaction = null; inspector();
  },
  cancel() {
    if (transaction) state.document = transaction;
    transaction = null; inspector();
  }
};
function cancelEditing() { cancelDrawing(); adapter?.cancel(); hooks.cancel(); }
function stopInteraction() {
  cancelEditing(); cameraGesture = null; suppressUntilRelease = pointers.size > 0;
}
function render() {
  const start = performance.now(); adapter?.render(state);
  lastRender = performance.now() - start; renderCount++;
  $('zoom').textContent = `${Math.round(state.view.scale * 100)}%`;
  $('metrics').textContent = `${state.document.width} × ${state.document.height} px · ${$('engine').value} · atualização ${lastRender.toFixed(1)} ms`;
  $('pan').setAttribute('aria-pressed', String(state.pan)); $('clean').setAttribute('aria-pressed', String(state.clean));
  toolControls(); drawPreview();
}
function activateEngine() {
  stopInteraction(); adapter?.destroy(); surface.replaceChildren();
  $('candidate-warning').hidden = $('engine').value !== 'moveable';
  adapter = createAdapter($('engine').value, surface, hooks); render();
}
function fit() {
  const bounds = viewport.getBoundingClientRect(), doc = state.document;
  const scale = Math.min(1, Math.max(1, bounds.width - 50) / doc.width, Math.max(1, bounds.height - 50) / doc.height);
  state.view = { scale, x: (bounds.width - doc.width * scale) / 2, y: (bounds.height - doc.height * scale) / 2 };
  render();
}
function updateInput(id, value) { if (document.activeElement !== $(id)) $(id).value = value; }
function measureText(value, size, family, weight) {
  measure.font = `${weight} ${size}px "${family}"`; return measure.measureText(value).width;
}
function inspector() {
  const doc = state.document, area = selected();
  $('areas').replaceChildren();
  for (const item of doc.areas) {
    const button = document.createElement('button'); button.type = 'button'; button.dataset.areaId = item.id;
    button.textContent = `${item.type === 'text' ? 'Texto' : 'Imagem'} · Source ${item.source} · ${item.background ? 'atrás' : 'frente'}`;
    button.setAttribute('aria-pressed', String(item.id === state.selectedId));
    button.addEventListener('click', () => { cancelEditing(); hooks.select(item.id); }); $('areas').append(button);
  }
  $('undo').disabled = history.length === 0; $('redo').disabled = future.length === 0;
  selectionInspector(area, doc);
  sampleInspector(doc);
  csvInspector(doc); imageInfo(); toolControls(); $('scene').value = doc.scene;
  $('document-summary').textContent = JSON.stringify({ format: doc.format, version: doc.version,
    areas: doc.areas.map(item => ({ id: item.id, type: item.type, source: item.source })), layerOrder: layerOrder(doc) }, null, 2);
}
function selectionInspector(area, doc) {
  for (const id of ['delete', 'backward', 'forward', 'source', 'background']) $(id).disabled = !area;
  $('geometry').disabled = !area;
  $('text-settings').hidden = area?.type !== 'text';
  const label = area?.type === 'text' ? 'Texto' : 'Imagem';
  $('selection-title').textContent = area ? `${label} — ${area.id}` : 'Selecione ou desenhe uma área';
  $('source').replaceChildren(...(area ? sourceOptions(doc, area.id).map(slot => new Option(`Source ${slot}`, slot)) : []));
  if (area) {
    $('source').value = area.source; $('background').checked = area.background;
    const point = areaPoints(area)[Number($('corner').value)]; updateInput('x', point.x); updateInput('y', point.y);
    if (area.type === 'text') textInspector(area, doc);
  } else {
    $('background').checked = false;
    for (const id of ['x', 'y', 'text']) $(id).value = '';
    $('text-result').textContent = ''; $('box-fill-enabled').checked = false; $('box-fill').disabled = true;
  }
}
function textInspector(area, doc) {
  for (const [id, key] of [['text','text'], ['font','font'], ['weight','weight'], ['size','size'], ['min-size','minSize'],
    ['fill','fill'], ['outline','outline'], ['outline-width','outlineWidth'], ['align','align'], ['overflow','overflow'], ['line-height','lineHeight']]) {
    updateInput(id, key === 'text' ? area.text : area.style[key]);
  }
  $('box-fill-enabled').checked = Boolean(area.style.boxFill);
  $('box-fill').disabled = !area.style.boxFill;
  updateInput('box-fill', area.style.boxFill ?? '#000000');
  const resolved = resolveText(area.text, doc.sources.find(source => source.slot === area.source).description);
  const layout = layoutText(resolved, area.rect, area.style, measureText);
  $('text-result').textContent = `${resolved}\n${layout.fontSize}px · ${layout.lines.length} linha(s)${layout.overflow ? ' · Não cabe: aumente a caixa ou reduza o mínimo.' : ' · Cabe na caixa.'}`;
}
function sampleInspector(doc) {
  const slots = doc.sources.map(source => source.slot).join(',');
  if ($('samples').dataset.slots !== slots) {
    $('samples').replaceChildren();
    $('samples').dataset.slots = slots;
    doc.sources.forEach(source => {
      const label = document.createElement('label'); label.textContent = `Source ${source.slot}`;
      const input = document.createElement('input'); input.maxLength = 512; input.dataset.sourceSlot = source.slot;
      input.value = source.description;
      input.addEventListener('change', () => {
        const next = clone(state.document); next.sources.find(item => item.slot === source.slot).description = input.value;
        if (updateDocument(next, clone(state.document))) status('Imagem e texto continuam ligados à mesma source.');
        else input.value = state.document.sources.find(item => item.slot === source.slot).description;
      }); label.append(input); $('samples').append(label);
    });
  }
  for (const input of $('samples').querySelectorAll('input')) {
    if (document.activeElement !== input) input.value = doc.sources.find(source => source.slot === Number(input.dataset.sourceSlot)).description;
  }
}
function csvInspector(doc) {
  const hasText = doc.areas.some(area => area.type === 'text');
  $('csv-copy').disabled = hasText || doc.areas.length === 0;
  let output = 'Desenhe pelo menos uma área de imagem para gerar CSV.';
  if (hasText) output = 'CSV não suporta textos; use JSON experimental.';
  else if (doc.areas.length) output = exportImageCsv(doc);
  $('csv-output').value = output;
}
function on(id, event, handler) { $(id).addEventListener(event, handler); }
on('engine', 'change', activateEngine);
function confirmReplacement() {
  return state.document.areas.length === 0 || window.confirm('Substituir o template e descartar as áreas atuais?');
}
function dropTemplate() {
  const previous = templateMedia;
  templateMedia = null; templateName = ''; state.templateImage = null;
  previous?.dispose();
}
on('scene', 'change', () => {
  const scene = $('scene').value;
  ++loadVersion; $('template-file').value = ''; $('import').value = ''; stopInteraction();
  if (!confirmReplacement()) {
    $('scene').value = state.document.scene; imageStatus('Troca cancelada. O template atual foi mantido.');
    render(); status('Troca cancelada.'); return;
  }
  dropTemplate(); state.document = scene === 'custom' ? createEmptyDocument() : createScene(scene);
  state.selectedId = state.document.areas[0]?.id ?? null; state.pan = false; state.clean = false;
  history.length = 0; future.length = 0;
  inspector(); fit();
  const message = scene === 'custom' ? 'Carregue um PNG ou JPEG para começar.' : 'Demo sintética carregada; nenhum envio ao bot.';
  imageStatus(message); status(message);
});
on('template-file', 'change', async event => {
  const version = ++loadVersion, file = event.target.files[0];
  $('import').value = ''; stopInteraction(); render();
  if (!file || !confirmReplacement()) {
    event.target.value = ''; imageStatus('Carregamento cancelado. O template atual foi mantido.');
    status('Carregamento cancelado.'); return;
  }
  imageStatus('Validando a imagem local…');
  let loaded;
  try {
    loaded = await loadLocalImage(file);
    if (version !== loadVersion) { loaded.dispose(); return; }
    const doc = createEmptyDocument(loaded.width, loaded.height), previous = templateMedia;
    stopInteraction(); templateMedia = loaded; templateName = file.name; state.templateImage = loaded.image;
    state.document = doc; state.selectedId = null; state.pan = false; state.clean = false;
    history.length = 0; future.length = 0;
    inspector(); fit(); previous?.dispose();
    imageStatus('Imagem local carregada. Os bytes não serão enviados nem incluídos no JSON.');
    status('Escolha Desenhar imagem ou Desenhar texto e arraste sobre o template.');
  } catch (error) {
    if (version !== loadVersion) { loaded?.dispose(); return; }
    if (loaded && templateMedia !== loaded) loaded.dispose();
    imageStatus(error.message, true); status(error.message, true);
  } finally { if (version === loadVersion) event.target.value = ''; }
});
on('template-file', 'cancel', () => {
  ++loadVersion; $('template-file').value = ''; $('import').value = ''; stopInteraction(); render();
  imageStatus('Carregamento cancelado. O template atual foi mantido.');
});
on('corner', 'change', inspector);
for (const id of ['x', 'y']) on(id, 'change', () => editArea(area => moveCorner(area, Number($('corner').value), { x: Number($('x').value), y: Number($('y').value) })));
on('source', 'change', () => {
  const area = selected(), source = Number($('source').value); if (!area) return;
  const before = clone(state.document), next = clone(state.document);
  try {
    if (!sourceOptions(next, area.id).includes(source)) throw new Error('Source não disponível para esta área.');
    if (!next.sources.some(item => item.slot === source)) {
      // The adjacency API only offers a new slot when removing this image retains a valid union.
      // Obtain its palette from the model, then retain the original quad, order and metadata.
      const base = clone(next); base.areas = base.areas.filter(item => item.id !== area.id);
      next.sources = addDrawnArea(base, { type: 'image', id: area.id, source,
        rect: { x: 0, y: 0, width: 1, height: 1 } }).sources;
    }
    next.areas.find(item => item.id === area.id).source = source;
    if (updateDocument(next, before)) status('Source vinculada à área.');
  } catch (error) { status(error.message, true); inspector(); }
});
on('background', 'change', () => editArea(area => ({ ...area, background: $('background').checked })));
for (const [id, key, numeric] of [['font','font'], ['weight','weight'], ['size','size',true], ['min-size','minSize',true],
  ['fill','fill'], ['outline','outline'], ['outline-width','outlineWidth',true], ['align','align'], ['overflow','overflow'], ['line-height','lineHeight',true]]) {
  on(id, 'change', () => editArea(area => ({ ...area, style: { ...area.style, [key]: numeric ? Number($(id).value) : $(id).value } })));
}
for (const id of ['box-fill-enabled', 'box-fill']) on(id, 'change', () => {
  if (selected()?.type !== 'text') return;
  editArea(area => ({ ...area, style: { ...area.style,
    boxFill: $('box-fill-enabled').checked ? $('box-fill').value : null } }));
});
on('text', 'change', () => editArea(area => ({ ...area, text: $('text').value })));
on('insert-token', 'click', () => {
  const input = $('text'); input.setRangeText('{{source.description}}', input.selectionStart, input.selectionEnd, 'end');
  editArea(area => ({ ...area, text: input.value }));
});
for (const button of document.querySelectorAll('[data-nudge]')) button.addEventListener('click', () => {
  const [dx, dy] = button.dataset.nudge.split(',').map(Number), area = selected();
  if (!area) return;
  const point = areaPoints(area)[Number($('corner').value)];
  editArea(current => moveCorner(current, Number($('corner').value), { x: point.x + dx, y: point.y + dy }));
});
function uniqueId(type) {
  const ids = new Set(state.document.areas.map(area => area.id));
  let index = 1; while (ids.has(`${type}-${index}`)) index++;
  return `${type}-${index}`;
}
function armDrawing(type) {
  const toggleOff = state.tool === type;
  stopInteraction();
  if (toggleOff || !canDraw()) { render(); status('Selecione uma área para editar.'); return; }
  state.pan = false; state.clean = false; state.tool = type;
  pendingDraw = { type, source: type === 'text'
    ? selected()?.source ?? state.document.areas.findLast(area => area.type === 'image')?.source ?? 1 : null };
  render(); viewport.focus({ preventScroll: true });
  const description = type === 'text' ? `texto ligado à Source ${pendingDraw.source}` : 'uma área de imagem';
  status(`Arraste para desenhar ${description}. Escape cancela.`);
}
on('add-image','click', () => armDrawing('image')); on('add-text','click', () => armDrawing('text'));
on('select-tool','click', () => { stopInteraction(); state.pan = false; render(); status('Selecione uma área para editar.'); });
on('delete', 'click', () => {
  cancelEditing(); if (!selected()) return;
  const before = clone(state.document); let next = clone(state.document);
  next.areas = next.areas.filter(area => area.id !== state.selectedId);
  // Renumber bindings without breaking image/text links; retain unused sample palette entries.
  const used = [...new Set(next.areas.map(area => area.source))].sort((a,b) => a-b);
  const mapping = new Map(used.map((slot,index) => [slot,index+1]));
  const samples = used.map((slot,index) => ({ ...next.sources.find(source => source.slot === slot), slot: index+1 }));
  const unused = next.sources.filter(source => !mapping.has(source.slot)).map((source,index) => ({ ...source, slot: used.length+index+1 }));
  next.sources = [...samples, ...unused]; next.areas.forEach(area => { area.source = mapping.get(area.source); });
  if (!next.areas.length) next = createEmptyDocument(next.width, next.height);
  if (updateDocument(next, before)) {
    state.selectedId = next.areas[0]?.id ?? null;
    if (!next.areas.length && before.scene !== 'custom') dropTemplate();
    inspector(); render(); status('Área excluída.');
  }
});
function reorder(direction) {
  cancelEditing(); const before = clone(state.document), next = clone(state.document);
  const index = next.areas.findIndex(area => area.id === state.selectedId), current = next.areas[index];
  if (!current) return;
  let other = index + direction;
  while (other >= 0 && other < next.areas.length && next.areas[other].background !== current.background) other += direction;
  if (other < 0 || other >= next.areas.length) return;
  [next.areas[index], next.areas[other]] = [next.areas[other], next.areas[index]]; updateDocument(next, before);
}
on('backward','click', () => reorder(-1)); on('forward','click', () => reorder(1));
function travel(from, to) {
  cancelEditing();
  if (!from.length) return;
  to.push(clone(state.document)); state.document = from.pop();
  if (!selected()) state.selectedId = state.document.areas[0]?.id ?? null;
  inspector(); render();
}
on('undo','click', () => travel(history,future)); on('redo','click', () => travel(future,history));
on('pan','click', () => { cancelEditing(); state.pan = !state.pan; render(); });
on('clean','click', () => { cancelEditing(); state.clean = !state.clean; render(); });
on('fit','click', () => { cancelEditing(); fit(); });
function zoom(factor, point) { cancelEditing(); state.view = zoomAt(state.view, point, factor); render(); }
for (const [id,factor] of [['zoom-in',1.25],['zoom-out',.8]]) on(id,'click', () => zoom(factor,{x:viewport.clientWidth/2,y:viewport.clientHeight/2}));
const screenPoint = event => { const rect = viewport.getBoundingClientRect(); return { x: event.clientX-rect.left, y:event.clientY-rect.top }; };
viewport.addEventListener('wheel', event => { event.preventDefault(); zoom(event.deltaY<0?1.1:1/1.1,screenPoint(event)); }, {passive:false});
function drawingRect() {
  const start = { x: Math.round(draft.start.x), y: Math.round(draft.start.y) };
  const end = { x: Math.round(draft.end.x), y: Math.round(draft.end.y) };
  return { x: Math.min(start.x, end.x), y: Math.min(start.y, end.y),
    width: Math.abs(end.x - start.x), height: Math.abs(end.y - start.y) };
}
function drawPreview() {
  const preview = $('draw-preview'); preview.hidden = !draft;
  if (!draft) return;
  const rect = drawingRect(), point = imageToView(rect, state.view);
  preview.dataset.type = draft.type;
  Object.assign(preview.style, { left: `${point.x}px`, top: `${point.y}px`,
    width: `${rect.width * state.view.scale}px`, height: `${rect.height * state.view.scale}px` });
}
function finishDrawing(event) {
  draft.end = viewToImage(screenPoint(event), state.view);
  const rect = drawingRect(), input = draft;
  const distance = Math.hypot(input.end.x - input.start.x, input.end.y - input.start.y) * state.view.scale;
  draft = null; cancelDrawing();
  if (distance < 4 || rect.width < 1 || rect.height < 1) {
    status('Desenho muito pequeno. Escolha a ferramenta e arraste para definir uma área.'); return;
  }
  try {
    const id = uniqueId(input.type), before = clone(state.document);
    const next = addDrawnArea(state.document, { type: input.type, id, rect,
      source: input.type === 'image' ? nextImageSource(state.document) : input.source });
    if (updateDocument(next, before)) {
      state.selectedId = id; inspector(); render(); status('Área criada. Ajuste os cantos ou as propriedades.');
    }
  } catch (error) { status(error.message + ' Nenhuma área foi criada.', true); }
}
function pinchData() {
  const [a,b] = [...pointers.values()]; return { x:(a.x+b.x)/2,y:(a.y+b.y)/2,distance:Math.max(1,Math.hypot(a.x-b.x,a.y-b.y)) };
}
function beginDrawing(event) {
  event.preventDefault(); event.stopImmediatePropagation();
  if (!canDraw() || event.isPrimary === false || !pendingDraw) return;
  const point = viewToImage(screenPoint(event), state.view), doc = state.document;
  if (point.x < 0 || point.y < 0 || point.x >= doc.width || point.y >= doc.height) {
    status('Comece o desenho dentro da imagem.'); return;
  }
  viewport.setPointerCapture(event.pointerId);
  draft = { ...pendingDraw, pointerId: event.pointerId, start: point, end: point };
  drawPreview();
}
viewport.addEventListener('pointerdown', event => {
  if (event.button !== 0) {
    if (state.tool !== 'select') { event.preventDefault(); event.stopImmediatePropagation(); }
    return;
  }
  pointers.set(event.pointerId, screenPoint(event));
  if (pointers.size >= 2) {
    cancelEditing(); suppressUntilRelease = true; cameraGesture = { type:'pinch', data:pinchData(), view:clone(state.view) };
    for (const id of pointers.keys()) { try { viewport.setPointerCapture(id); } catch { /* A released pointer is harmless. */ } }
    event.preventDefault(); event.stopImmediatePropagation(); render(); return;
  }
  if (suppressUntilRelease) { event.preventDefault(); event.stopImmediatePropagation(); return; }
  if (state.tool !== 'select') { beginDrawing(event); return; }
  const isEmpty = $('engine').value === 'konva' ? !adapter.hit(screenPoint(event)) : !event.target.closest('.dom-area,.moveable-control-box');
  if (state.pan || isEmpty || state.clean) {
    cancelEditing(); cameraGesture = {type:'pan',point:screenPoint(event),view:clone(state.view)};
    viewport.setPointerCapture(event.pointerId); event.preventDefault(); event.stopImmediatePropagation();
  }
}, {capture:true});
viewport.addEventListener('pointermove', event => {
  if (!pointers.has(event.pointerId)) return;
  pointers.set(event.pointerId, screenPoint(event));
  if (draft?.pointerId === event.pointerId && !suppressUntilRelease) {
    draft.end = viewToImage(screenPoint(event), state.view);
    event.preventDefault(); event.stopImmediatePropagation(); drawPreview();
  } else if (pointers.size>=2 && cameraGesture?.type==='pinch') {
    const current=pinchData(), initial=cameraGesture.data;
    const next=zoomAt(cameraGesture.view,{x:initial.x,y:initial.y},current.distance/initial.distance);
    state.view={...next,x:next.x+current.x-initial.x,y:next.y+current.y-initial.y};
    event.preventDefault(); event.stopImmediatePropagation(); render();
  } else if (cameraGesture?.type==='pan') {
    const current=screenPoint(event), initial=cameraGesture.point;
    state.view={...cameraGesture.view,x:cameraGesture.view.x+current.x-initial.x,y:cameraGesture.view.y+current.y-initial.y};
    event.preventDefault(); event.stopImmediatePropagation(); render();
  } else if (suppressUntilRelease) { event.preventDefault(); event.stopImmediatePropagation(); }
}, {capture:true});
function releasePointer(event) {
  const drawing = draft?.pointerId === event.pointerId;
  const armed = state.tool !== 'select';
  const camera = !!cameraGesture || suppressUntilRelease;
  if (event.type === 'pointercancel') { stopInteraction(); render(); }
  else if (drawing && !suppressUntilRelease && pointers.size === 1) finishDrawing(event);
  pointers.delete(event.pointerId);
  if (pointers.size < 2) cameraGesture = null;
  if (pointers.size === 0) suppressUntilRelease = false;
  if (viewport.hasPointerCapture(event.pointerId)) viewport.releasePointerCapture(event.pointerId);
  if (camera || drawing || armed) { event.preventDefault(); event.stopImmediatePropagation(); }
}
for (const type of ['pointerup','pointercancel']) viewport.addEventListener(type,releasePointer,{capture:true});
for (const type of ['pointerup','pointercancel']) window.addEventListener(type, event => {
  if (!viewport.contains(event.target) && pointers.has(event.pointerId)) releasePointer(event);
}, {capture:true});
viewport.addEventListener('mousedown', event => {
  if (state.tool !== 'select' || draft || suppressUntilRelease || cameraGesture) { event.preventDefault(); event.stopImmediatePropagation(); }
}, {capture:true});
viewport.addEventListener('touchstart', event => { if (state.tool !== 'select' || draft || event.touches.length>1 || suppressUntilRelease || cameraGesture) { event.preventDefault(); event.stopImmediatePropagation(); } },{capture:true,passive:false});
viewport.addEventListener('touchmove', event => { if (state.tool !== 'select' || draft || suppressUntilRelease || cameraGesture) { event.preventDefault(); event.stopImmediatePropagation(); } },{capture:true,passive:false});
viewport.addEventListener('touchend', event => {
  if (suppressUntilRelease || cameraGesture) { event.preventDefault(); event.stopImmediatePropagation(); }
},{capture:true,passive:false});
viewport.addEventListener('keydown', event => {
  if (event.target!==viewport) return;
  const delta={ArrowLeft:[-1,0],ArrowRight:[1,0],ArrowUp:[0,-1],ArrowDown:[0,1]}[event.key];
  if (delta) { event.preventDefault(); editArea(area => moveArea(area,delta[0]*(event.shiftKey?10:1),delta[1]*(event.shiftKey?10:1))); }
});
window.addEventListener('keydown', event => {
  if (event.key !== 'Escape') return;
  event.preventDefault(); stopInteraction(); render(); status('Desenho ou edição cancelado.');
}, {capture:true});
window.addEventListener('blur', () => { pointers.clear(); stopInteraction(); suppressUntilRelease=false; render(); });
window.addEventListener('beforeunload', () => { ++loadVersion; templateMedia?.dispose(); });
new ResizeObserver(() => { stopInteraction(); render(); }).observe(viewport);
on('csv-import','click', () => {
  stopInteraction(); render();
  if (!canDraw()) { status('Carregue uma imagem ou escolha uma demo antes de importar CSV.', true); return; }
  try {
    const before = clone(state.document), next = importImageCsv($('csv-input').value, before.width, before.height);
    if (!state.templateImage) next.scene = before.scene; // Retain only an explicitly selected synthetic demo.
    next.sources = next.sources.map(source => clone(before.sources.find(item => item.slot === source.slot) ?? source));
    if (updateDocument(next, before)) {
      state.selectedId = next.areas[0]?.id ?? null; inspector(); render();
      status('CSV importado. Coordenadas preservadas; a imagem não foi alterada.');
    }
  } catch (error) { status(error.message + ' O documento anterior foi mantido.', true); }
});
on('csv-copy','click', async () => {
  try {
    const csv = exportImageCsv(state.document);
    $('csv-output').value = csv;
    try {
      if (!navigator.clipboard?.writeText) throw new Error('Clipboard indisponível.');
      await navigator.clipboard.writeText(csv); status('CSV copiado. Nenhum envio ao bot.');
    } catch {
      $('csv-output').focus(); $('csv-output').select();
      status('Cópia automática indisponível. O CSV está selecionado: copie manualmente.');
    }
  } catch (error) { status(error.message, true); }
});
on('export','click', () => {
  try {
    const url=URL.createObjectURL(new Blob([encodeDocument(state.document)],{type:'application/json'}));
    const a=document.createElement('a'); a.href=url;a.download='regata-spike.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
    status('JSON experimental exportado, sem a imagem. Não envie este formato ao bot.');
  } catch (error) { status(error.message,true); }
});
on('import','change', async event => {
  const version = ++loadVersion, file=event.target.files[0];
  $('template-file').value = ''; stopInteraction(); render();
  if (!file) { event.target.value = ''; return; }
  try {
    if(file.size>128*1024)throw new Error('Documento maior que 128 KiB.');
    const doc=decodeDocument(await file.text());
    if (version !== loadVersion) return;
    if (doc.scene === 'custom' && (!state.templateImage
        || doc.width !== state.templateImage.naturalWidth || doc.height !== state.templateImage.naturalHeight)) {
      throw new Error('O JSON não contém a imagem. Carregue antes o template com as mesmas dimensões.');
    }
    stopInteraction(); const before=clone(state.document);
    if(updateDocument(doc,before)) {
      if (doc.scene !== 'custom' && state.templateImage) { dropTemplate(); history.length = 0; future.length = 0; }
      state.selectedId=doc.areas[0]?.id ?? null;
      inspector();fit();imageStatus(doc.scene === 'custom' ? 'Geometria JSON importada sobre a imagem local; confira se é o mesmo template.' : 'Demo sintética importada; nenhuma imagem incluída no JSON.');
      status('Documento experimental importado. JSON não inclui a imagem.');
    }
  } catch(error){if (version === loadVersion) status(error.message,true);}
  finally{if (version === loadVersion) event.target.value='';}
});
on('import','cancel', () => { ++loadVersion; $('import').value = ''; });

// Read-only diagnostics for local browser assertions, never a production API.
window.regataSpike = Object.freeze({ snapshot:()=>({ ...state, document:clone(state.document), view:clone(state.view),
    templateImage:state.templateImage ? { width:state.templateImage.naturalWidth, height:state.templateImage.naturalHeight } : null }),
  metrics:()=>({renderCount,lastRender}),
  capabilities:()=>({engine:$('engine').value,localOnly:true,textBoxes:'rectangular',nativePreview:false,imageExport:false}) });
inspector();activateEngine();fit();