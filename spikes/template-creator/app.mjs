import { createAdapter } from './adapters.mjs';
import { clone, createScene, decodeDocument, encodeDocument, layerOrder, layoutText,
  moveArea, moveCorner, rectPoints, resolveText, validateDocument, zoomAt } from './model.mjs';

const $ = id => document.getElementById(id);
const viewport = $('viewport'), surface = $('surface');
const measure = document.createElement('canvas').getContext('2d');
let state = { document: createScene(), selectedId: 'image-1', view: { scale: 1, x: 0, y: 0 }, clean: false, pan: false };
let adapter, transaction = null, idCounter = 10, renderCount = 0, lastRender = 0;
const history = [], future = [], pointers = new Map();
let cameraGesture = null, suppressUntilRelease = false;
const selected = () => state.document.areas.find(area => area.id === state.selectedId);
const areaPoints = area => area.type === 'image' ? area.points : rectPoints(area.rect);

function status(message, error = false) { $('status').textContent = message; $('status').dataset.error = String(error); }
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
function cancelEditing() { adapter?.cancel(); hooks.cancel(); }
function render() {
  const start = performance.now(); adapter?.render(state);
  lastRender = performance.now() - start; renderCount++;
  $('zoom').textContent = `${Math.round(state.view.scale * 100)}%`;
  $('metrics').textContent = `${state.document.width} × ${state.document.height} px · ${$('engine').value} · atualização ${lastRender.toFixed(1)} ms`;
  $('pan').setAttribute('aria-pressed', String(state.pan)); $('clean').setAttribute('aria-pressed', String(state.clean));
}
function activateEngine() {
  cancelEditing(); adapter?.destroy(); surface.replaceChildren();
  $('candidate-warning').hidden = $('engine').value !== 'moveable';
  adapter = createAdapter($('engine').value, surface, hooks); render();
}
function fit() {
  const bounds = viewport.getBoundingClientRect(), doc = state.document;
  const scale = Math.max(.15, Math.min(1, (bounds.width - 50) / doc.width, (bounds.height - 50) / doc.height));
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
  $('delete').disabled = doc.areas.length <= 1;
  $('text-settings').hidden = area?.type !== 'text';
  if (!area) return;
  $('selection-title').textContent = `${area.type === 'text' ? 'Texto' : 'Imagem'} — ${area.id}`;
  if ($('source').options.length !== doc.sources.length) {
    $('source').replaceChildren(...doc.sources.map(source => new Option(`Source ${source.slot}`, source.slot)));
  }
  updateInput('source', area.source); $('background').checked = area.background;
  const point = areaPoints(area)[Number($('corner').value)]; updateInput('x', point.x); updateInput('y', point.y);
  if (area.type === 'text') textInspector(area, doc);
  sampleInspector(doc);
  $('document-summary').textContent = JSON.stringify({ format: doc.format, version: doc.version,
    areas: doc.areas.map(item => ({ id: item.id, type: item.type, source: item.source })), layerOrder: layerOrder(doc) }, null, 2);
}
function textInspector(area, doc) {
  for (const [id, key] of [['text','text'], ['font','font'], ['weight','weight'], ['size','size'], ['min-size','minSize'],
    ['fill','fill'], ['outline','outline'], ['outline-width','outlineWidth'], ['align','align'], ['overflow','overflow'], ['line-height','lineHeight']]) {
    updateInput(id, key === 'text' ? area.text : area.style[key]);
  }
  const resolved = resolveText(area.text, doc.sources.find(source => source.slot === area.source).description);
  const layout = layoutText(resolved, area.rect, area.style, measureText);
  $('text-result').textContent = `${resolved}\n${layout.fontSize}px · ${layout.lines.length} linha(s)${layout.overflow ? ' · Não cabe: aumente a caixa ou reduza o mínimo.' : ' · Cabe na caixa.'}`;
}
function sampleInspector(doc) {
  if (!$('samples').children.length || $('samples').children.length !== doc.sources.length) {
    $('samples').replaceChildren();
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
function on(id, event, handler) { $(id).addEventListener(event, handler); }
on('engine', 'change', activateEngine);
on('scene', 'change', () => {
  cancelEditing(); state.document = createScene($('scene').value); state.selectedId = 'image-1'; history.length = 0; future.length = 0;
  $('samples').replaceChildren(); inspector(); fit(); status('Exemplo sintético carregado; nenhum envio ao bot.');
});
on('corner', 'change', inspector);
for (const id of ['x', 'y']) on(id, 'change', () => editArea(area => moveCorner(area, Number($('corner').value), { x: Number($('x').value), y: Number($('y').value) })));
on('source', 'change', () => editArea(area => ({ ...area, source: Number($('source').value) })));
on('background', 'change', () => editArea(area => ({ ...area, background: $('background').checked })));
for (const [id, key, numeric] of [['font','font'], ['weight','weight'], ['size','size',true], ['min-size','minSize',true],
  ['fill','fill'], ['outline','outline'], ['outline-width','outlineWidth',true], ['align','align'], ['overflow','overflow'], ['line-height','lineHeight',true]]) {
  on(id, 'change', () => editArea(area => ({ ...area, style: { ...area.style, [key]: numeric ? Number($(id).value) : $(id).value } })));
}
on('text', 'change', () => editArea(area => ({ ...area, text: $('text').value })));
on('insert-token', 'click', () => {
  const input = $('text'); input.setRangeText('{{source.description}}', input.selectionStart, input.selectionEnd, 'end');
  editArea(area => ({ ...area, text: input.value }));
});
for (const button of document.querySelectorAll('[data-nudge]')) button.addEventListener('click', () => {
  const [dx, dy] = button.dataset.nudge.split(',').map(Number), area = selected();
  const point = areaPoints(area)[Number($('corner').value)];
  editArea(current => moveCorner(current, Number($('corner').value), { x: point.x + dx, y: point.y + dy }));
});
function add(type) {
  cancelEditing(); const next = clone(state.document), base = clone(selected());
  let area;
  if (type === 'image') area = { id: `image-${++idCounter}`, type, source: base.source, background: false,
    points: rectPoints({ x: 100, y: 180, width: 200, height: 200 }) };
  else area = { id: `text-${++idCounter}`, type, source: base.source, background: false,
    rect: { x: 200, y: 160, width: 450, height: 130 }, text: '{{source.description}}',
    style: { font: 'sans', size: 34, minSize: 14, fill: '#ffffff', outline: '#000000', outlineWidth: 0,
      align: 'center', lineHeight: 1.15, weight: 'normal', overflow: 'shrink' } };
  next.areas.push(area); const before = clone(state.document);
  if (updateDocument(next, before)) { state.selectedId = area.id; inspector(); render(); }
}
on('add-image','click', () => add('image')); on('add-text','click', () => add('text'));
on('delete', 'click', () => {
  cancelEditing(); const before = clone(state.document), next = clone(state.document);
  next.areas = next.areas.filter(area => area.id !== state.selectedId);
  // Renumber bindings without breaking image/text links; retain unused sample palette entries.
  const used = [...new Set(next.areas.map(area => area.source))].sort((a,b) => a-b);
  const mapping = new Map(used.map((slot,index) => [slot,index+1]));
  const samples = used.map((slot,index) => ({ ...next.sources.find(source => source.slot === slot), slot: index+1 }));
  const unused = next.sources.filter(source => !mapping.has(source.slot)).map((source,index) => ({ ...source, slot: used.length+index+1 }));
  next.sources = [...samples, ...unused]; next.areas.forEach(area => { area.source = mapping.get(area.source); });
  if (updateDocument(next, before)) { state.selectedId = next.areas[0].id; $('samples').replaceChildren(); inspector(); render(); }
});
function reorder(direction) {
  cancelEditing(); const before = clone(state.document), next = clone(state.document);
  const index = next.areas.findIndex(area => area.id === state.selectedId), current = next.areas[index];
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
  if (!selected()) state.selectedId = state.document.areas[0].id;
  $('samples').replaceChildren(); inspector(); render();
}
on('undo','click', () => travel(history,future)); on('redo','click', () => travel(future,history));
on('pan','click', () => { cancelEditing(); state.pan = !state.pan; render(); });
on('clean','click', () => { cancelEditing(); state.clean = !state.clean; render(); });
on('fit','click', () => { cancelEditing(); fit(); });
function zoom(factor, point) { cancelEditing(); state.view = zoomAt(state.view, point, factor); render(); }
for (const [id,factor] of [['zoom-in',1.25],['zoom-out',.8]]) on(id,'click', () => zoom(factor,{x:viewport.clientWidth/2,y:viewport.clientHeight/2}));
const screenPoint = event => { const rect = viewport.getBoundingClientRect(); return { x: event.clientX-rect.left, y:event.clientY-rect.top }; };
viewport.addEventListener('wheel', event => { event.preventDefault(); zoom(event.deltaY<0?1.1:1/1.1,screenPoint(event)); }, {passive:false});
function pinchData() {
  const [a,b] = [...pointers.values()]; return { x:(a.x+b.x)/2,y:(a.y+b.y)/2,distance:Math.max(1,Math.hypot(a.x-b.x,a.y-b.y)) };
}
viewport.addEventListener('pointerdown', event => {
  pointers.set(event.pointerId, screenPoint(event));
  if (pointers.size >= 2) {
    cancelEditing(); suppressUntilRelease = true; cameraGesture = { type:'pinch', data:pinchData(), view:clone(state.view) };
    event.preventDefault(); event.stopImmediatePropagation(); render(); return;
  }
  if (suppressUntilRelease) { event.preventDefault(); event.stopImmediatePropagation(); return; }
  const isEmpty = $('engine').value === 'konva' ? !adapter.hit(screenPoint(event)) : !event.target.closest('.dom-area,.moveable-control-box');
  if (state.pan || isEmpty || state.clean) {
    cancelEditing(); cameraGesture = {type:'pan',point:screenPoint(event),view:clone(state.view)};
    viewport.setPointerCapture(event.pointerId); event.preventDefault(); event.stopImmediatePropagation();
  }
}, {capture:true});
viewport.addEventListener('pointermove', event => {
  if (!pointers.has(event.pointerId)) return;
  pointers.set(event.pointerId, screenPoint(event));
  if (pointers.size>=2 && cameraGesture?.type==='pinch') {
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
  pointers.delete(event.pointerId);
  const camera = !!cameraGesture || suppressUntilRelease;
  if (pointers.size < 2) cameraGesture = null;
  if (pointers.size === 0) suppressUntilRelease = false;
  if (event.type==='pointercancel') { cancelEditing(); render(); }
  if (camera) { event.preventDefault(); event.stopImmediatePropagation(); }
}
for (const type of ['pointerup','pointercancel']) viewport.addEventListener(type,releasePointer,{capture:true});
viewport.addEventListener('touchstart', event => { if (event.touches.length>1 || suppressUntilRelease || cameraGesture) { event.preventDefault(); event.stopImmediatePropagation(); } },{capture:true,passive:false});
viewport.addEventListener('touchmove', event => { if (suppressUntilRelease || cameraGesture) { event.preventDefault(); event.stopImmediatePropagation(); } },{capture:true,passive:false});
viewport.addEventListener('touchend', event => {
  if (suppressUntilRelease || cameraGesture) { event.preventDefault(); event.stopImmediatePropagation(); }
},{capture:true,passive:false});
viewport.addEventListener('keydown', event => {
  if (event.target!==viewport) return;
  const delta={ArrowLeft:[-1,0],ArrowRight:[1,0],ArrowUp:[0,-1],ArrowDown:[0,1]}[event.key];
  if (delta) { event.preventDefault(); editArea(area => moveArea(area,delta[0]*(event.shiftKey?10:1),delta[1]*(event.shiftKey?10:1))); }
});
window.addEventListener('blur', () => { pointers.clear(); cameraGesture=null; suppressUntilRelease=false; cancelEditing(); render(); });
new ResizeObserver(() => { cancelEditing(); render(); }).observe(viewport);
on('export','click', () => {
  try {
    const url=URL.createObjectURL(new Blob([encodeDocument(state.document)],{type:'application/json'}));
    const a=document.createElement('a'); a.href=url;a.download='regata-spike.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
    status('JSON experimental exportado. Não envie este formato ao bot.');
  } catch (error) { status(error.message,true); }
});
on('import','change', async event => {
  const file=event.target.files[0]; if (!file) return;
  try {
    if(file.size>128*1024)throw new Error('Documento maior que 128 KiB.');
    const doc=decodeDocument(await file.text());
    cancelEditing();const before=clone(state.document);state.selectedId=doc.areas[0].id;
    if(updateDocument(doc,before)){$('scene').value=doc.scene;$('samples').replaceChildren();inspector();fit();status('Documento experimental importado.');}
  } catch(error){status(error.message,true);} finally{event.target.value='';}
});

// Read-only diagnostics for local browser assertions, never a production API.
window.regataSpike = Object.freeze({ snapshot:()=>clone(state), metrics:()=>({renderCount,lastRender}),
  capabilities:()=>({engine:$('engine').value,localOnly:true,textBoxes:'rectangular',nativePreview:false}) });
inspector();activateEngine();fit();