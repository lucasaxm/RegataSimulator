import { test as base, expect } from '@playwright/test';

const ORIGIN = 'http://127.0.0.1:4173';
const ENGINES = ['konva', 'moveable'];
const staticAssets = new Set(['/', '/app.js', '/styles.css']);
function allowedRequest(request) {
  const url = new URL(request.url());
  return url.origin === ORIGIN && ['GET', 'HEAD'].includes(request.method())
    && (url.protocol === 'blob:' || (url.protocol === 'http:'
      && staticAssets.has(url.pathname) && !url.search));
}
const test = base.extend({
  browserGuard: [async ({ page, context }, use) => {
    const errors = [], forbidden = [], requests = [];
    page.on('pageerror', error => errors.push(error.message));
    context.on('request', request => { if (!allowedRequest(request)) requests.push(request.url()); });
    await context.route('**/*', async route => {
      if (!allowedRequest(route.request())) {
        forbidden.push(route.request().url());
        await route.abort();
      } else await route.continue();
    });
    await use();
    expect.soft(errors, 'No uncaught browser exceptions').toEqual([]);
    expect.soft(forbidden, 'No external network access or backend calls').toEqual([]);
    expect.soft(requests, 'Only local static assets and same-origin image blobs').toEqual([]);
  }, { auto: true }],
});

// These are the only page evaluations: read-only JSON diagnostics and two paint frames.
const snapshot = page => page.evaluate(() => window.regataSpike.snapshot());
const frames = page => page.evaluate(() => new Promise(resolve => {
  requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
}));
const area = (document, id) => document.areas.find(item => item.id === id);
const otherEngine = engine => ENGINES.find(value => value !== engine);
const union = document => [...new Set(document.areas.map(item => item.source))].sort((a, b) => a - b);

async function switchEngine(page, engine) {
  await page.locator('#engine').selectOption(engine);
  await frames(page); // Konva batches canvas/hit-canvas painting.
}
async function selectArea(page, id) {
  await page.locator(`#areas [data-area-id="${id}"]`).click();
  await frames(page);
}
async function edit(page, selector, value) {
  await page.locator(selector).fill(String(value));
  await page.locator(selector).press('Tab'); // Commit the actual change listener.
  await frames(page);
}
async function viewportBox(page) {
  await page.locator('#viewport').scrollIntoViewIfNeeded();
  await frames(page);
  const bounds = await page.locator('#viewport').boundingBox();
  expect(bounds).not.toBeNull();
  return bounds;
}
async function worldPoint(page, point) {
  // Inspector controls can scroll the mobile canvas completely offscreen.
  const bounds = await viewportBox(page), { view } = await snapshot(page);
  return { x: bounds.x + view.x + point.x * view.scale,
    y: bounds.y + view.y + point.y * view.scale };
}
async function drag(page, start, dx, dy) {
  await page.mouse.move(start.x, start.y);
  await page.mouse.down();
  await page.mouse.move(start.x + dx, start.y + dy, { steps: 8 });
  await page.mouse.up();
  await frames(page);
}
async function dragWorld(page, point, dx, dy) {
  const start = await worldPoint(page, point), { view } = await snapshot(page);
  // Both adapters use viewport + camera * canonical world coordinates, including corners.
  await drag(page, start, dx * view.scale, dy * view.scale);
  return view.scale;
}
async function drawImage(page, from, to) {
  await page.locator('#add-image').click();
  const start = await worldPoint(page, from), end = await worldPoint(page, to);
  await drag(page, start, end.x - start.x, end.y - start.y);
}
const sourceValues = page => page.locator('#source option').evaluateAll(options => options.map(option => Number(option.value)));
function expectQuantizedPoint(actual, expected, bound) {
  // Desktop/mobile pointers can quantize by one screen CSS pixel, not one original pixel, at the fitted camera scale.
  for (const coordinate of ['x', 'y']) {
    expect(Number.isFinite(actual[coordinate])).toBe(true);
    expect(Number.isInteger(actual[coordinate])).toBe(true);
    expect(Math.abs(actual[coordinate] - expected[coordinate])).toBeLessThanOrEqual(bound);
  }
}
async function importJSON(page, content) {
  const details = page.locator('details').filter({ has: page.locator('#import') });
  if (await details.getAttribute('open') === null) await details.locator('summary').click();
  const chooser = page.waitForEvent('filechooser');
  await page.locator('#import').click();
  await (await chooser).setFiles({ name: 'synthetic.json', mimeType: 'application/json',
    buffer: Buffer.from(typeof content === 'string' ? content : JSON.stringify(content)) });
  await expect(page.locator('#import')).toHaveValue(''); // Async File.text() has finished.
  await frames(page);
}
async function summary(page) {
  return JSON.parse(await page.locator('#document-summary').textContent());
}

for (const engine of ENGINES) test.describe(engine, () => {
  test.beforeEach(async ({ page }) => {
    page.on('dialog', dialog => dialog.accept()); // Explicit demo/JSON replacement confirmations.
    await page.goto('/'); // Fresh page/context for every test; never reuse a prior document.
    await page.locator('#scene').selectOption('captions');
    await expect(page.locator('#areas [data-area-id]')).toHaveCount(6);
    await switchEngine(page, engine);
  });

  test('engine switching leaves the entire document exactly unchanged; only local assets are read', async ({ page }) => {
    const initial = (await snapshot(page)).document;
    for (const target of [otherEngine(engine), engine]) {
      await switchEngine(page, target);
      expect((await snapshot(page)).document).toEqual(initial);
    }
  });

  test('zoom, camera pan and viewport resize preserve canonical geometry', async ({ page }) => {
    const initial = await snapshot(page);
    await page.locator('#zoom-in').click();
    const zoomed = await snapshot(page);
    expect(zoomed.view.scale).toBeCloseTo(initial.view.scale * 1.25, 8);
    await page.locator('#pan').click();
    const bounds = await viewportBox(page), beforePan = await snapshot(page);
    await drag(page, { x: bounds.x + 20, y: bounds.y + 20 }, 60, 40);
    const panned = await snapshot(page);
    expect(panned.view.x).toBeCloseTo(beforePan.view.x + 60, 5);
    expect(panned.view.y).toBeCloseTo(beforePan.view.y + 40, 5);
    await page.locator('#pan').click();
    const size = page.viewportSize();
    await page.setViewportSize({ width: size.width - 30, height: size.height - 50 });
    await viewportBox(page);
    expect((await snapshot(page)).document).toEqual(initial.document);
    await page.locator('#fit').click();
    expect((await snapshot(page)).document).toEqual(initial.document);
  });

  test('real corner drag moves 20/10 and undo restores the exact document', async ({ page }) => {
    const initial = (await snapshot(page)).document, image = area(initial, 'image-1');
    const cornerScale = await dragWorld(page, image.points[0], 20, 10);
    const expectedCorner = structuredClone(initial);
    area(expectedCorner, image.id).points[0] = { x: image.points[0].x + 20, y: image.points[0].y + 10 };
    const cornerDocument = (await snapshot(page)).document;
    const actual = area(cornerDocument, image.id).points[0];
    expectQuantizedPoint(actual, area(expectedCorner, image.id).points[0], Math.ceil(1 / cornerScale));
    area(expectedCorner, image.id).points[0] = { x: actual.x, y: actual.y };
    expect(cornerDocument).not.toEqual(initial);
    expect(cornerDocument).toEqual(expectedCorner);
    await page.locator('#undo').click();
    expect((await snapshot(page)).document).toEqual(initial);
  });

  test('first drag selects and moves an unselected area 30/20 (known Moveable target race)', async ({ page }) => {
    // Retained by user choice as a failing comparison candidate, not desired behavior.
    // Unexpected success fails the runner: remove this annotation when the race is fixed.
    test.fail(engine === 'moveable', 'Moveable 0.53.0 adapter loses Gesto state on first-drag target switching.');
    const initial = (await snapshot(page)).document;
    expect((await snapshot(page)).selectedId).not.toBe('image-2');
    const unselected = area(initial, 'image-2');
    // Interior hit, safely away from the selected area's 44 CSS-pixel corner hitboxes.
    const moveScale = await dragWorld(page, { x: unselected.points[0].x + 100, y: unselected.points[0].y + 100 }, 30, 20);
    const expectedMove = structuredClone(initial);
    area(expectedMove, unselected.id).points = unselected.points.map(point => ({ x: point.x + 30, y: point.y + 20 }));
    const moved = await snapshot(page);
    expect(moved.selectedId).toBe(unselected.id);
    const actualPoints = area(moved.document, unselected.id).points;
    for (let index = 0; index < unselected.points.length; index++) {
      expectQuantizedPoint(actualPoints[index], area(expectedMove, unselected.id).points[index], Math.ceil(1 / moveScale));
    }
    const dx = actualPoints[0].x - unselected.points[0].x, dy = actualPoints[0].y - unselected.points[0].y;
    expect(dx).toBeGreaterThan(0);
    expect(dy).toBeGreaterThan(0); // Selection alone is not a successful first drag.
    area(expectedMove, unselected.id).points = unselected.points.map(point => ({ x: point.x + dx, y: point.y + dy }));
    expect(actualPoints).toEqual(area(expectedMove, unselected.id).points);
    expect(moved.document).toEqual(expectedMove);
    await page.locator('#undo').click();
    expect((await snapshot(page)).document).toEqual(initial);
  });

  test('text top-left drag resizes by -10/-10 without skew and undo restores it', async ({ page }) => {
    await selectArea(page, 'text-1');
    const initial = (await snapshot(page)).document, { rect } = area(initial, 'text-1');
    const resizeScale = await dragWorld(page, rect, -10, -10);
    const expected = structuredClone(initial);
    area(expected, 'text-1').rect = { x: rect.x - 10, y: rect.y - 10,
      width: rect.width + 10, height: rect.height + 10 };
    const resized = (await snapshot(page)).document;
    const actual = area(resized, 'text-1').rect;
    expectQuantizedPoint(actual, area(expected, 'text-1').rect, Math.ceil(1 / resizeScale));
    // An axis-aligned rect must retain integer dimensions and its exact opposite corner.
    for (const dimension of ['width', 'height']) {
      expect(Number.isFinite(actual[dimension])).toBe(true);
      expect(Number.isInteger(actual[dimension])).toBe(true);
      expect(actual[dimension]).toBeGreaterThan(0);
    }
    expect(actual.x + actual.width).toBe(rect.x + rect.width);
    expect(actual.y + actual.height).toBe(rect.y + rect.height);
    area(expected, 'text-1').rect = { x: actual.x, y: actual.y,
      width: actual.width, height: actual.height };
    expect(resized).not.toEqual(initial);
    expect(resized).toEqual(expected);
    await page.locator('#corner').selectOption('2');
    await expect(page.locator('#x')).toHaveValue(String(rect.x + rect.width));
    await expect(page.locator('#y')).toHaveValue(String(rect.y + rect.height));
    await page.locator('#undo').click();
    expect((await snapshot(page)).document).toEqual(initial);
  });

  test('numeric off-canvas corners and nudges are pixel-exact; zero-width text is rejected without an exception', async ({ page }) => {
    const initial = (await snapshot(page)).document, expected = structuredClone(initial);
    await edit(page, '#x', -30);
    const offCanvas = (await snapshot(page)).document;
    area(expected, 'image-1').points[0] = { x: -30, y: 250 };
    expect(offCanvas).toEqual(expected);
    expect(area(offCanvas, 'image-1').points.every(point => Number.isInteger(point.x) && Number.isInteger(point.y))).toBe(true);
    // Original-pixel precision comes from numeric/nudge controls, not a sub-CSS-pixel mouse promise.
    // Every desktop/mobile project and both engines must move exactly one original pixel here.
    await page.locator('.nudges button[data-nudge="1,0"]').click();
    await frames(page);
    area(expected, 'image-1').points[0] = { x: -29, y: 250 };
    const nudged = (await snapshot(page)).document;
    await expect(page.locator('#x')).toHaveValue('-29');
    await expect(page.locator('#y')).toHaveValue('250');
    expect(nudged).toEqual(expected);
    await switchEngine(page, otherEngine(engine));
    expect((await snapshot(page)).document).toEqual(nudged);
    await switchEngine(page, engine);
    expect((await snapshot(page)).document).toEqual(nudged);
    await selectArea(page, 'text-1');
    await page.locator('#corner').selectOption('0');
    const valid = (await snapshot(page)).document, { rect } = area(valid, 'text-1');
    await edit(page, '#x', rect.x + rect.width);
    await expect(page.locator('#status')).toHaveAttribute('data-error', 'true');
    await expect(page.locator('#status')).toContainText('última posição válida');
    expect((await snapshot(page)).document).toEqual(valid);
  });

  test('known-slot descriptions update tokens live; HTML, nested braces and static text stay literal', async ({ page }) => {
    await selectArea(page, 'text-1');
    await page.locator('#source').selectOption('2');
    await edit(page, '#text', 'Olá ');
    await page.locator('#insert-token').click();
    expect(area((await snapshot(page)).document, 'text-1').text).toBe('Olá {{source.description}}');
    await edit(page, '#text', 'Olá {{source.description}} / {{ source.description }}');
    const literal = '<b>Nome</b> {{unexpected}}';
    await edit(page, '#samples input[data-source-slot="2"]', literal);
    await expect(page.locator('#text-result')).toContainText(`Olá ${literal} / ${literal}`);
    const document = (await snapshot(page)).document;
    expect(area(document, 'text-1').source).toBe(2);
    expect(area(document, 'image-2').source).toBe(2);
    expect(document.sources.find(source => source.slot === 2).description).toBe(literal);
    await edit(page, '#text', '<b>Nome</b>');
    await edit(page, '#samples input[data-source-slot="2"]', 'Outro nome');
    await expect(page.locator('#text-result')).toContainText('<b>Nome</b>');
    await expect(page.locator('#text-result')).not.toContainText('Outro nome');
    await expect(page.locator('#surface b, #text-result b')).toHaveCount(0);
    await edit(page, '#text', 'Texto simples sem token');
    await expect(page.locator('#text-result')).toContainText('Texto simples sem token');
  });

  test('fonts, colors, outline, alignment and line-height persist across engine changes', async ({ page }) => {
    // Native OS color-picker dialogs are not DOM. Seed colors through the real JSON file picker,
    // then edit every other style control in the DOM; never set state/adapter hooks from tests.
    const fixture = (await snapshot(page)).document;
    Object.assign(area(fixture, 'text-1').style, { fill: '#123abc', outline: '#fedcba' });
    await importJSON(page, fixture);
    await selectArea(page, 'text-1');
    for (const font of ['sans', 'serif', 'mono']) {
      await page.locator('#font').selectOption(font);
      expect(area((await snapshot(page)).document, 'text-1').style.font).toBe(font);
    }
    await page.locator('#weight').selectOption('bold');
    await edit(page, '#size', 52);
    await edit(page, '#min-size', 20);
    await edit(page, '#outline-width', 3);
    await page.locator('#align').selectOption('right');
    await page.locator('#overflow').selectOption('clip');
    await edit(page, '#line-height', 1.5);
    await expect(page.locator('#fill')).toHaveValue('#123abc');
    await expect(page.locator('#outline')).toHaveValue('#fedcba');
    const styled = (await snapshot(page)).document;
    expect(area(styled, 'text-1').style).toEqual({ font: 'mono', size: 52, minSize: 20,
      fill: '#123abc', outline: '#fedcba', outlineWidth: 3, align: 'right',
      lineHeight: 1.5, weight: 'bold', overflow: 'clip' });
    for (const target of [otherEngine(engine), engine]) {
      await switchEngine(page, target);
      expect((await snapshot(page)).document).toEqual(styled);
    }
  });

  test('512-character Unicode is retained and minimum-size overflow is visibly reported', async ({ page }) => {
    await selectArea(page, 'text-1');
    const long = '🚣é'.repeat(170) + '汉字'; // Exactly 512 UTF-16 units; synthetic only.
    expect(long.length).toBe(512);
    await edit(page, '#samples input[data-source-slot="1"]', long);
    await edit(page, '#text', '{{source.description}}');
    await edit(page, '#min-size', 34);
    await expect(page.locator('#text-result')).toBeVisible();
    await expect(page.locator('#text-result')).toContainText(long);
    await expect(page.locator('#text-result')).toContainText('34px');
    await expect(page.locator('#text-result')).toContainText('Não cabe');
    expect((await snapshot(page)).document.sources[0].description).toBe(long);
    await edit(page, '#text', long);
    await expect(page.locator('#text')).toHaveValue(long);
    expect(area((await snapshot(page)).document, 'text-1').text).toBe(long);
    await switchEngine(page, otherEngine(engine));
    await expect(page.locator('#text-result')).toContainText(long);
    await expect(page.locator('#text-result')).toContainText('Não cabe');
    // Stored/resolved text is never truncated; intentional canvas clipping is not pixel parity.
    expect(area((await snapshot(page)).document, 'text-1').text).toBe(long);
  });

  test('unsupported source.id token leaves the last valid document intact', async ({ page }) => {
    await selectArea(page, 'text-1');
    await edit(page, '#text', 'Válido {{source.description}}');
    const valid = (await snapshot(page)).document;
    await edit(page, '#text', '{{source.id}}');
    await expect(page.locator('#status')).toHaveAttribute('data-error', 'true');
    await expect(page.locator('#status')).toContainText('Texto inválido');
    expect((await snapshot(page)).document).toEqual(valid);
    await expect(page.locator('#text-result')).toContainText('Válido Ana Lima');
  });

  test('reorder keeps B-template-F; shared source union and binding survive image deletion', async ({ page }) => {
    await selectArea(page, 'image-2');
    await page.locator('#background').check();
    await selectArea(page, 'text-3');
    await page.locator('#backward').click();
    expect((await summary(page)).layerOrder).toEqual([
      'image-2', 'template', 'image-1', 'text-1', 'text-2', 'text-3', 'image-3',
    ]);
    await page.locator('#forward').click();
    expect((await summary(page)).layerOrder).toEqual([
      'image-2', 'template', 'image-1', 'text-1', 'text-2', 'image-3', 'text-3',
    ]);
    await page.locator('#scene').selectOption('chat');
    let document = (await snapshot(page)).document;
    expect(document.areas.map(item => [item.type, item.source])).toEqual([['image', 1], ['text', 1]]);
    expect(union(document)).toEqual([1]);
    await page.locator('#delete').click(); // Selected image-1: a text-only slot remains valid.
    document = (await snapshot(page)).document;
    expect(document.areas.map(item => [item.type, item.source])).toEqual([['text', 1]]);
    expect(union(document)).toEqual([1]);
    await expect(page.locator('#delete')).toBeEnabled(); // Remaining text is selected and deletable.
    expect(await sourceValues(page)).toEqual([1]); // Unused palette slots 2/3 are not bindings.
    await page.locator('#undo').click();
    await selectArea(page, 'text-1');
    expect(await sourceValues(page)).toEqual([1]);
    await drawImage(page, { x: 320, y: 360 }, { x: 490, y: 480 });
    const secondImage = (await snapshot(page)).document.areas.find(item => item.type === 'image' && item.source === 2);
    expect(secondImage).toBeDefined();
    await selectArea(page, 'text-1');
    expect(await sourceValues(page)).toEqual([1, 2]); // Palette slot 3 is still unused.
    await page.locator('#source').selectOption('2');
    await edit(page, '#samples input[data-source-slot="2"]', 'Segundo vínculo');
    expect(union((await snapshot(page)).document)).toEqual([1, 2]);
    await selectArea(page, 'image-1');
    await page.locator('#delete').click();
    document = (await snapshot(page)).document;
    expect(document.areas.map(item => [item.id, item.source])).toEqual([['text-1', 1], [secondImage.id, 1]]);
    expect(document.sources[0]).toMatchObject({ slot: 1, description: 'Segundo vínculo' });
    await expect(page.locator('#text-result')).toContainText('Segundo vínculo entrou no grupo');
    expect(await sourceValues(page)).toEqual([1]);
    await selectArea(page, secondImage.id);
    await page.locator('#delete').click();
    document = (await snapshot(page)).document;
    expect(document.areas.map(item => [item.type, item.source])).toEqual([['text', 1]]);
    expect(document.sources[0]).toMatchObject({ slot: 1, description: 'Segundo vínculo' });
    await expect(page.locator('#text-result')).toContainText('Segundo vínculo entrou no grupo');
    await switchEngine(page, otherEngine(engine));
    expect((await snapshot(page)).document).toEqual(document);
  });

  test('JSON download/file-picker round trip rejects unknown and prototype keys', async ({ page }) => {
    await edit(page, '#x', 65);
    const initial = (await snapshot(page)).document;
    const details = page.locator('details').filter({ has: page.locator('#export') });
    await details.locator('summary').click();
    const pending = page.waitForEvent('download');
    await page.locator('#export').click();
    const download = await pending;
    expect(download.suggestedFilename()).toBe('regata-spike.json');
    const stream = await download.createReadStream(), chunks = [];
    expect(stream).not.toBeNull();
    for await (const chunk of stream) chunks.push(chunk);
    const json = Buffer.concat(chunks).toString('utf8');
    expect(JSON.parse(json)).toEqual(initial);
    await page.locator('#scene').selectOption('chat');
    await importJSON(page, json);
    await expect(page.locator('#status')).toContainText('Documento experimental importado');
    expect((await snapshot(page)).document).toEqual(initial);
    const nestedUnknown = structuredClone(initial);
    nestedUnknown.sources[0].unexpected = 'not allowed';
    for (const malicious of [JSON.stringify({ ...initial, unexpected: true }),
      JSON.stringify(nestedUnknown), json.replace('{', '{"__proto__":{"polluted":true},')]) {
      await importJSON(page, malicious);
      await expect(page.locator('#status')).toHaveAttribute('data-error', 'true');
      await expect(page.locator('#status')).toContainText('Documento inválido');
      expect((await snapshot(page)).document).toEqual(initial);
    }
  });

  test('CDP pinch cancels a live corner edit and remaining pointer release cannot resume it', async ({ page, context }, testInfo) => {
    // Chromium touch emulation only: not a claim of real Android/iOS verification.
    const session = await context.newCDPSession(page);
    const touch = (id, point) => ({ id, x: point.x, y: point.y, radiusX: 1, radiusY: 1, force: 1 });
    let touching = false;
    const dispatch = async (type, points) => {
      await session.send('Input.dispatchTouchEvent', { type, touchPoints: points });
      touching = points.length > 0;
    };
    try {
      await session.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 2 });
      const start = await worldPoint(page, area((await snapshot(page)).document, 'image-1').points[0]);
      const initial = await snapshot(page), bounds = await viewportBox(page);
      const first = { x: start.x + 5, y: start.y };
      await dispatch('touchStart', [touch(1, start)]);
      await dispatch('touchMove', [touch(1, first)]);
      await frames(page);
      expect((await snapshot(page)).document, 'First finger must really edit a native corner').not.toEqual(initial.document);
      const second = { x: first.x + 100, y: first.y + 60 };
      await dispatch('touchStart', [touch(1, first), touch(2, second)]);
      await frames(page);
      expect((await snapshot(page)).document).toEqual(initial.document);
      const midpoint = { x: (first.x + second.x) / 2, y: (first.y + second.y) / 2 };
      const spread = point => ({ x: midpoint.x + 18 + (point.x - midpoint.x) * 1.3,
        y: midpoint.y + 14 + (point.y - midpoint.y) * 1.3 });
      const a = spread(first), b = spread(second);
      await dispatch('touchMove', [touch(1, a), touch(2, b)]);
      await frames(page);
      const pinched = await snapshot(page), local = { x: midpoint.x - bounds.x, y: midpoint.y - bounds.y };
      expect(pinched.document).toEqual(initial.document);
      expect(pinched.view.scale).toBeGreaterThan(initial.view.scale);
      // CDP touch coordinates may be quantized at the device's scale factor.
      expect(Math.abs(pinched.view.scale - initial.view.scale * 1.3)).toBeLessThan(initial.view.scale * .02);
      expect(Math.abs(pinched.view.x - (local.x + 18 - (local.x - initial.view.x) * 1.3))).toBeLessThan(2);
      expect(Math.abs(pinched.view.y - (local.y + 14 - (local.y - initial.view.y) * 1.3))).toBeLessThan(2);
      await dispatch('touchEnd', [touch(1, a)]); // Release the second finger first.
      await dispatch('touchMove', [touch(1, { x: a.x + 12, y: a.y + 8 })]);
      await frames(page);
      expect((await snapshot(page)).document).toEqual(initial.document);
      expect((await snapshot(page)).view).toEqual(pinched.view);
      await dispatch('touchEnd', []);
      await page.mouse.up(); // A later pointer end must not commit the canceled shape edit.
      await frames(page);
      expect((await snapshot(page)).document).toEqual(initial.document);
      await expect(page.locator('#undo')).toBeDisabled();
    } finally {
      try {
        if (touching) await dispatch('touchEnd', []);
        if (!testInfo.project.use.hasTouch) {
          await session.send('Emulation.setTouchEmulationEnabled', { enabled: false });
        }
      } finally {
        await session.detach();
      }
    }
  });
});