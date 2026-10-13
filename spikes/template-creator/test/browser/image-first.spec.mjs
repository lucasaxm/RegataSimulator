import { test as base, expect } from '@playwright/test';
import { deflateSync } from 'node:zlib';

const ORIGIN = 'http://127.0.0.1:4173';
const ENGINES = ['konva', 'moveable'];
const WIDTH = 1080, HEIGHT = 1114;
const HEADER = 'Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background';
// Exact user-provided coordinates; no user images or spikes/example files are accessed.
const CSV = `${HEADER}\n1,1,105,840,274,840,274,1018,105,1018,0\n2,2,461,842,631,842,631,1020,461,1020,0\n3,3,800,840,969,840,969,1018,800,1018,0`;
const PHOTOS = [
  { x: 105, y: 840, width: 169, height: 178 },
  { x: 461, y: 842, width: 170, height: 178 },
  { x: 800, y: 840, width: 169, height: 178 },
];
const COLORS = ['#70b9cd', '#e3b56a', '#b298d5'];

// Dependency-free, valid RGBA PNG, generated exclusively in Node memory.
const CRC_TABLE = Uint32Array.from({ length: 256 }, (_, index) => {
  let crc = index;
  for (let bit = 0; bit < 8; bit++) crc = crc & 1 ? 0xedb88320 ^ (crc >>> 1) : crc >>> 1;
  return crc >>> 0;
});
function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) crc = CRC_TABLE[(crc ^ byte) & 255] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const name = Buffer.from(type, 'ascii'), length = Buffer.alloc(4), crc = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  crc.writeUInt32BE(crc32(Buffer.concat([name, data])));
  return Buffer.concat([length, name, data, crc]);
}
function generatedPng() {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(WIDTH, 0); header.writeUInt32BE(HEIGHT, 4);
  header[8] = 8; header[9] = 6; // 8-bit RGBA, no interlace.
  const stride = WIDTH * 4 + 1, pixels = Buffer.alloc(stride * HEIGHT);
  for (let y = 0; y < HEIGHT; y++) for (let x = 0; x < WIDTH; x++) {
    const photo = PHOTOS.findIndex(rect => x >= rect.x && x < rect.x + rect.width
      && y >= rect.y && y < rect.y + rect.height);
    const rgb = photo < 0 ? [236, 240, 244] : [[112, 185, 205], [227, 181, 106], [178, 152, 213]][photo];
    const offset = y * stride + 1 + x * 4;
    pixels[offset] = rgb[0]; pixels[offset + 1] = rgb[1]; pixels[offset + 2] = rgb[2]; pixels[offset + 3] = 255;
  }
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', header), chunk('IDAT', deflateSync(pixels)), chunk('IEND', Buffer.alloc(0))]);
}
const PNG = generatedPng();
const IMAGE_INFO = 'generated-1080x1114.png · 1080 × 1114 px';

function allowedRequest(request) {
  const url = new URL(request.url());
  return url.origin === ORIGIN && ['GET', 'HEAD'].includes(request.method())
    && (url.protocol === 'blob:' || (url.protocol === 'http:' && !url.search
      && ['/', '/app.js', '/styles.css'].includes(url.pathname)));
}
const test = base.extend({
  browserGuard: [async ({ page, context }, use) => {
    const errors = [], forbidden = [];
    page.on('pageerror', error => errors.push(error.message));
    context.on('request', request => {
      if (!allowedRequest(request)) forbidden.push({ url: request.url(), method: request.method() });
    });
    await context.route('**/*', async route => {
      if (allowedRequest(route.request())) await route.continue();
      else await route.abort(); // Block external traffic AND local API/mutation/query requests.
    });
    await use();
    expect.soft(errors, 'No uncaught browser exceptions').toEqual([]);
    expect.soft(forbidden, 'Only local static assets and same-origin image blobs; no uploads or APIs').toEqual([]);
  }, { auto: true }],
});

// Evaluations only read diagnostics/DOM or await paint; never mutate state, DOM or adapter hooks.
const snapshot = page => page.evaluate(() => window.regataSpike.snapshot());
const frames = page => page.evaluate(() => new Promise(resolve => {
  requestAnimationFrame(() => requestAnimationFrame(resolve));
}));
const sourceValues = page => page.locator('#source option').evaluateAll(options => options.map(option => Number(option.value)));
const area = (doc, id) => doc.areas.find(item => item.id === id);
const points = ({ x, y, width, height }) => [
  { x, y }, { x: x + width, y }, { x: x + width, y: y + height }, { x, y: y + height },
];
async function switchEngine(page, engine) {
  await page.locator('#engine').selectOption(engine);
  await frames(page);
}
async function selectArea(page, id) {
  await page.locator(`#areas [data-area-id="${id}"]`).click();
  await frames(page);
}
async function edit(page, selector, value) {
  await page.locator(selector).fill(String(value));
  await page.locator(selector).press('Tab');
  await frames(page);
}
async function openDetails(page, selector) {
  const details = page.locator('details').filter({ has: page.locator(selector) });
  if (await details.getAttribute('open') === null) await details.locator('summary').click();
}
async function loadImage(page) {
  const pending = page.waitForEvent('filechooser');
  await page.locator('#template-file').click();
  await (await pending).setFiles({ name: 'generated-1080x1114.png', mimeType: 'image/png', buffer: PNG });
  await expect(page.locator('#image-info')).toHaveText(IMAGE_INFO);
  await expect(page.locator('#image-status')).toHaveAttribute('data-error', 'false');
  await expect(page.locator('#image-status')).toContainText('Imagem local carregada');
  await frames(page);
}
async function importJson(page, doc) {
  await openDetails(page, '#import');
  const pending = page.waitForEvent('filechooser');
  await page.locator('#import').click();
  await (await pending).setFiles({ name: 'generated-geometry.json', mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(doc)) });
}
async function importCsv(page, csv = CSV) {
  await openDetails(page, '#csv-input');
  await page.locator('#csv-input').fill(csv);
  await page.locator('#csv-import').click();
  await frames(page);
}
async function worldPoints(page, from, to = from) {
  // Toolbar/inspector actions scroll the mobile page. Recenter the viewport BEFORE mapping coordinates.
  await page.locator('#viewport').scrollIntoViewIfNeeded();
  await frames(page);
  const bounds = await page.locator('#viewport').boundingBox(), { view } = await snapshot(page);
  expect(bounds).not.toBeNull();
  const convert = point => ({ x: bounds.x + view.x + point.x * view.scale,
    y: bounds.y + view.y + point.y * view.scale });
  const start = convert(from), end = convert(to), size = page.viewportSize();
  for (const point of [start, end]) {
    expect(point.x).toBeGreaterThan(bounds.x);
    expect(point.x).toBeLessThan(bounds.x + bounds.width);
    expect(point.y).toBeGreaterThan(bounds.y);
    expect(point.y).toBeLessThan(bounds.y + bounds.height);
    expect(point.x).toBeGreaterThan(0); expect(point.x).toBeLessThan(size.width);
    expect(point.y).toBeGreaterThan(0); expect(point.y).toBeLessThan(size.height);
  }
  return { start, end, scale: view.scale };
}
const touch = (id, point) => ({ id, x: point.x, y: point.y, radiusX: 1, radiusY: 1, force: 1 });
async function draw({ page, context, isMobile }, type, from, to) {
  const count = (await snapshot(page)).document.areas.length;
  await page.locator(type === 'image' ? '#add-image' : '#add-text').click();
  const { start, end, scale } = await worldPoints(page, from, to);
  if (isMobile) {
    const session = await context.newCDPSession(page);
    try {
      await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [touch(1, start)] });
      for (let step = 1; step <= 8; step++) {
        const point = { x: start.x + (end.x - start.x) * step / 8, y: start.y + (end.y - start.y) * step / 8 };
        await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [touch(1, point)] });
      }
    } finally {
      try { await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] }); }
      finally { await session.detach(); }
    }
  } else {
    await page.mouse.move(start.x, start.y); await page.mouse.down();
    try { await page.mouse.move(end.x, end.y, { steps: 8 }); }
    finally { await page.mouse.up(); }
  }
  await frames(page);
  await expect(page.locator('#areas [data-area-id]')).toHaveCount(count + 1);
  const state = await snapshot(page), created = area(state.document, state.selectedId);
  expect(created.type).toBe(type);
  const expected = points({ x: Math.min(from.x, to.x), y: Math.min(from.y, to.y),
    width: Math.abs(to.x - from.x), height: Math.abs(to.y - from.y) });
  const actual = type === 'image' ? created.points : points(created.rect);
  // Native mobile coordinates may quantize a CSS pixel. CSV/undo assertions below stay exact.
  for (let index = 0; index < 4; index++) for (const coordinate of ['x', 'y']) {
    expect(Number.isInteger(actual[index][coordinate])).toBe(true);
    expect(Math.abs(actual[index][coordinate] - expected[index][coordinate])).toBeLessThanOrEqual(Math.ceil(1 / scale));
  }
  await expect(page.locator('#draw-preview')).toBeHidden();
  await expect(page.locator('#select-tool')).toHaveAttribute('aria-pressed', 'true');
  return created;
}
async function expectPreserved(page, before) {
  const after = await snapshot(page);
  expect(after.document).toEqual(before.document);
  expect(after.templateImage).toEqual(before.templateImage);
  await expect(page.locator('#image-info')).toHaveText(IMAGE_INFO);
}

for (const engine of ENGINES) test.describe(`image-first ${engine}`, () => {
  test.beforeEach(async ({ page }) => {
    page.on('dialog', dialog => dialog.accept()); // Replacement is explicitly approved in these cases.
    await page.goto('/');
    await expect(page.locator('#scene')).toHaveValue('custom');
    await expect(page.locator('#areas [data-area-id]')).toHaveCount(0);
    await switchEngine(page, engine);
  });

  test('default custom document is empty and drawing waits for a local image', async ({ page }) => {
    const initial = await snapshot(page);
    expect(initial.document).toEqual({ format: 'regata-creator-spike', version: 1,
      width: 900, height: 620, scene: 'custom', sources: [], areas: [] });
    expect(initial.templateImage).toBeNull(); expect(initial.selectedId).toBeNull();
    await expect(page.locator('#image-info')).toHaveText('Nenhuma imagem carregada.');
    await expect(page.locator('#empty-state')).toBeVisible();
    await expect(page.locator('#empty-state')).toContainText('Carregue um PNG ou JPEG');
    for (const id of ['add-image', 'add-text', 'csv-import', 'csv-copy', 'delete', 'undo', 'redo']) {
      await expect(page.locator(`#${id}`)).toBeDisabled();
    }
    expect(await sourceValues(page)).toEqual([]);
    await switchEngine(page, ENGINES.find(value => value !== engine));
    expect((await snapshot(page)).document).toEqual(initial.document);
  });

  test('real PNG file picker loads 1080 × 1114 without creating areas or exporting image bytes', async ({ page }) => {
    await loadImage(page);
    const loaded = await snapshot(page);
    expect(loaded.document).toEqual({ format: 'regata-creator-spike', version: 1,
      width: WIDTH, height: HEIGHT, scene: 'custom', sources: [], areas: [] });
    expect(loaded.templateImage).toEqual({ width: WIDTH, height: HEIGHT });
    expect(loaded.selectedId).toBeNull();
    await expect(page.locator('#empty-state')).toBeHidden();
    await expect(page.locator('#areas [data-area-id]')).toHaveCount(0);
    await expect(page.locator('#add-image')).toBeEnabled();
    await expect(page.locator('#add-text')).toBeEnabled();
    await expect(page.locator('#csv-import')).toBeEnabled();
    await expect(page.locator('#image-status')).toContainText('bytes não serão enviados nem incluídos no JSON');
    await openDetails(page, '#export');
    const pending = page.waitForEvent('download');
    await page.locator('#export').click();
    const download = await pending, stream = await download.createReadStream(), chunks = [];
    expect(stream).not.toBeNull();
    for await (const bytes of stream) chunks.push(bytes);
    expect(JSON.parse(Buffer.concat(chunks).toString('utf8'))).toEqual(loaded.document);
    await expect(page.locator('#status')).toContainText('sem a imagem');
  });

  test('drawn image sources progress 1/2/3, permit reuse of 2, then allocate the next used-reference gap', async ({ page, context, isMobile }) => {
    const inputs = { page, context, isMobile };
    await loadImage(page);
    for (const [index, x] of [105, 461, 800].entries()) {
      const image = await draw(inputs, 'image', { x, y: 100 }, { x: x + 169, y: 278 });
      expect(image.source).toBe(index + 1);
      expect(await sourceValues(page)).toEqual(index === 0 ? [1] : index === 1 ? [1, 2] : [2, 3]);
    }
    const three = (await snapshot(page)).document;
    expect(three.sources).toEqual(COLORS.map((color, index) => ({ slot: index + 1,
      description: `Source ${index + 1}`, color })));
    await page.locator('#source').selectOption('2');
    expect((await snapshot(page)).document.areas.map(item => item.source)).toEqual([1, 2, 2]);
    expect(await sourceValues(page)).toEqual([2, 3]);
    const fourth = await draw(inputs, 'image', { x: 320, y: 370 }, { x: 490, y: 550 });
    expect(fourth.source).toBe(3); // Unused palette Source 3 is reused; palette size is not the next slot.
    expect((await snapshot(page)).document.areas.map(item => item.source)).toEqual([1, 2, 2, 3]);
    expect(await sourceValues(page)).toEqual([2, 3]);
  });

  test('caption below the photos inherits selection, binds existing sources, and retains box fill and text', async ({ page, context, isMobile }, testInfo) => {
    const inputs = { page, context, isMobile };
    await loadImage(page);
    await importCsv(page);
    await selectArea(page, 'image-1');
    const text = await draw(inputs, 'text', { x: 90, y: 1025 }, { x: 300, y: 1100 });
    expect(text.source).toBe(1); expect(text.text).toBe('{{source.description}}');
    expect(text.rect.y).toBeGreaterThan(area((await snapshot(page)).document, 'image-1').points[2].y);
    expect(await sourceValues(page)).toEqual([1, 2, 3]);
    await expect(page.locator('#text-result')).toContainText('Source 1');
    await page.locator('#source').selectOption('3');
    await expect(page.locator('#text-result')).toContainText('Source 3');
    await edit(page, '#samples input[data-source-slot="3"]', 'Terceira legenda');
    await expect(page.locator('#text-result')).toContainText('Terceira legenda');
    expect(area((await snapshot(page)).document, 'image-3').source).toBe(3);
    await expect(page.locator('#box-fill-enabled')).not.toBeChecked();
    await expect(page.locator('#box-fill')).toBeDisabled();
    await expect(page.locator('#box-fill')).toHaveValue('#000000');
    await page.locator('#box-fill-enabled').check();
    await expect(page.locator('#box-fill')).toBeEnabled();
    const filled = await snapshot(page);
    expect(area(filled.document, text.id).style.boxFill).toBe('#000000');
    expect(area(filled.document, text.id).background).toBe(false); // Box paint is not native layer ordering.
    await openDetails(page, '#csv-output');
    await expect(page.locator('#csv-copy')).toBeDisabled();
    await expect(page.locator('#csv-output')).toHaveValue('CSV não suporta textos; use JSON experimental.');
    expect((await snapshot(page)).document).toEqual(filled.document); // CSV never silently discards text.
    await page.locator('#viewport').scrollIntoViewIfNeeded(); await frames(page);
    await testInfo.attach(`caption-placement-${engine}`, { body: await page.locator('#viewport').screenshot(),
      contentType: 'image/png' }); // Evidence only, not a native-rendering/pixel-parity claim.
    for (const target of [ENGINES.find(value => value !== engine), engine]) {
      await switchEngine(page, target);
      expect((await snapshot(page)).document).toEqual(filled.document);
    }
    // Color-picker dialogs are native OS UI. Import a valid generated JSON geometry fixture instead.
    const hex = structuredClone(filled.document);
    area(hex, text.id).style.boxFill = '#123abc';
    await importJson(page, hex);
    await expect(page.locator('#status')).toContainText('Documento experimental importado');
    await selectArea(page, text.id);
    await expect(page.locator('#box-fill')).toHaveValue('#123abc');
    expect((await snapshot(page)).document).toEqual(hex);
    await expect(page.locator('#image-info')).toHaveText(IMAGE_INFO);
    await page.locator('#box-fill-enabled').uncheck();
    expect(area((await snapshot(page)).document, text.id).style.boxFill).toBeNull();
    await expect(page.locator('#box-fill')).toBeDisabled();
  });

  test('exact three-row CSV replaces drawn geometry; undo, camera, resize and engine changes never round it', async ({ page, context, isMobile }) => {
    const inputs = { page, context, isMobile };
    await loadImage(page);
    await draw(inputs, 'image', { x: 105, y: 100 }, { x: 274, y: 278 });
    const before = await snapshot(page);
    await importCsv(page);
    await expect(page.locator('#status')).toContainText('Coordenadas preservadas');
    await expect(page.locator('#csv-output')).toHaveValue(CSV);
    await expect(page.locator('#csv-copy')).toBeEnabled(); // Assert output directly; no clipboard permissions.
    const imported = await snapshot(page);
    expect(imported.view).toEqual(before.view);
    expect(imported.document).toEqual({ format: 'regata-creator-spike', version: 1,
      width: WIDTH, height: HEIGHT, scene: 'custom',
      sources: COLORS.map((color, index) => ({ slot: index + 1, description: `Source ${index + 1}`, color })),
      areas: PHOTOS.map((rect, index) => ({ id: `image-${index + 1}`, type: 'image',
        source: index + 1, background: false, points: points(rect) })) });
    await page.locator('#undo').click();
    expect((await snapshot(page)).document).toEqual(before.document);
    await page.locator('#redo').click();
    expect((await snapshot(page)).document).toEqual(imported.document);
    await page.locator('#zoom-in').click();
    expect((await snapshot(page)).view.scale).not.toBe(imported.view.scale);
    const size = page.viewportSize();
    await page.setViewportSize({ width: size.width - 30, height: size.height - 50 });
    await frames(page);
    await page.locator('#zoom-out').click();
    for (const target of [ENGINES.find(value => value !== engine), engine]) {
      await switchEngine(page, target);
      await page.locator('#fit').click();
      await expectPreserved(page, imported);
      await expect(page.locator('#csv-output')).toHaveValue(CSV);
    }
  });

  test('deleting the last area permits an empty custom document and undo restores it exactly', async ({ page, context, isMobile }) => {
    const inputs = { page, context, isMobile };
    await loadImage(page);
    await draw(inputs, 'image', { x: 105, y: 100 }, { x: 274, y: 278 });
    const before = await snapshot(page);
    await page.locator('#delete').click();
    const empty = await snapshot(page);
    expect(empty.document).toEqual({ format: 'regata-creator-spike', version: 1,
      width: WIDTH, height: HEIGHT, scene: 'custom', sources: [], areas: [] });
    expect(empty.templateImage).toEqual(before.templateImage); expect(empty.selectedId).toBeNull();
    await expect(page.locator('#image-info')).toHaveText(IMAGE_INFO);
    await expect(page.locator('#empty-state')).toBeHidden();
    await expect(page.locator('#delete')).toBeDisabled();
    await expect(page.locator('#add-image')).toBeEnabled();
    await expect(page.locator('#add-text')).toBeEnabled();
    await expect(page.locator('#csv-copy')).toBeDisabled();
    await page.locator('#undo').click();
    await expectPreserved(page, before);
    await page.locator('#redo').click();
    expect((await snapshot(page)).document).toEqual(empty.document);
  });

  test('SVG disguised as PNG, corrupt JPEG, and empty/canceled choice preserve the loaded image and geometry', async ({ page, context, isMobile }) => {
    const inputs = { page, context, isMobile };
    await loadImage(page);
    await draw(inputs, 'image', { x: 105, y: 100 }, { x: 274, y: 278 });
    const before = await snapshot(page);
    const invalid = [
      { file: { name: 'disguised.png', mimeType: 'image/png', buffer: Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"/>') },
        message: 'não corresponde a um PNG ou JPEG válido' },
      { file: { name: 'corrupt.jpg', mimeType: 'image/jpeg', buffer: Buffer.from([255, 216, 255, 224, 0, 2, 255, 217]) },
        message: 'Não foi possível decodificar a imagem' },
    ];
    for (const { file, message } of invalid) {
      await page.locator('#template-file').setInputFiles(file);
      await expect(page.locator('#image-status')).toHaveAttribute('data-error', 'true');
      await expect(page.locator('#image-status')).toContainText(message);
      await expect(page.locator('#status')).toHaveAttribute('data-error', 'true');
      await expectPreserved(page, before);
    }
    await page.locator('#template-file').setInputFiles([]); // Native file-input empty selection, no fake cancel event.
    await expect(page.locator('#image-status')).toContainText('Carregamento cancelado');
    await expect(page.locator('#image-status')).toHaveAttribute('data-error', 'false');
    await expect(page.locator('#status')).toHaveAttribute('data-error', 'false');
    await expectPreserved(page, before);
  });

  test('bad CSV header, coordinate bounds, and mismatched custom JSON dimensions preserve the loaded document', async ({ page }) => {
    await loadImage(page); await importCsv(page);
    const before = await snapshot(page);
    for (const csv of [CSV.replace('TLx', 'tlx'), CSV.replace('105,840', '10001,840'),
      `${HEADER}\n1,1,1080,100,1180,100,1180,200,1080,200,0`]) {
      await importCsv(page, csv);
      await expect(page.locator('#status')).toHaveAttribute('data-error', 'true');
      await expect(page.locator('#status')).toContainText('documento anterior foi mantido');
      await expectPreserved(page, before);
      await expect(page.locator('#csv-output')).toHaveValue(CSV);
    }
    const mismatch = structuredClone(before.document); mismatch.width = WIDTH - 1;
    await importJson(page, mismatch);
    await expect(page.locator('#status')).toHaveAttribute('data-error', 'true');
    await expect(page.locator('#status')).toContainText('Carregue antes o template com as mesmas dimensões');
    await expectPreserved(page, before);
    await expect(page.locator('#csv-output')).toHaveValue(CSV);
  });

  test('reverse image and text drags normalize min/max corners without changing existing geometry', async ({ page, context, isMobile }) => {
    const inputs = { page, context, isMobile };
    await loadImage(page);
    const image = await draw(inputs, 'image', { x: 274, y: 278 }, { x: 105, y: 100 });
    expect(image.points[0].x).toBeLessThan(image.points[2].x);
    expect(image.points[0].y).toBeLessThan(image.points[2].y);
    const before = (await snapshot(page)).document;
    const text = await draw(inputs, 'text', { x: 300, y: 650 }, { x: 90, y: 575 });
    expect(text.source).toBe(1); expect(text.rect.width).toBeGreaterThan(0); expect(text.rect.height).toBeGreaterThan(0);
    expect(area((await snapshot(page)).document, image.id)).toEqual(image);
    await page.locator('#undo').click();
    expect((await snapshot(page)).document).toEqual(before);
  });

  test('zero-size clicks and Escape during image/text drawing create no area or undo entry', async ({ page, isMobile }) => {
    await loadImage(page);
    const before = await snapshot(page);
    for (const tool of ['#add-image', '#add-text']) {
      await page.locator(tool).click();
      const { start } = await worldPoints(page, { x: 105, y: 100 });
      if (isMobile) await page.touchscreen.tap(start.x, start.y);
      else await page.mouse.click(start.x, start.y);
      await expect(page.locator('#status')).toContainText('Desenho muito pequeno');
      await expectPreserved(page, before);
      await page.locator(tool).click();
      const gesture = await worldPoints(page, { x: 105, y: 100 }, { x: 274, y: 278 });
      await page.mouse.move(gesture.start.x, gesture.start.y); await page.mouse.down();
      try {
        await page.mouse.move(gesture.end.x, gesture.end.y, { steps: 8 });
        await expect(page.locator('#draw-preview')).toBeVisible();
        await page.keyboard.press('Escape');
      } finally { await page.mouse.up(); }
      await frames(page);
      await expect(page.locator('#status')).toContainText('cancelado');
      await expect(page.locator('#draw-preview')).toBeHidden();
      await expect(page.locator('#select-tool')).toHaveAttribute('aria-pressed', 'true');
      await expect(page.locator('#undo')).toBeDisabled();
      await expectPreserved(page, before);
    }
  });

  test('native second finger cancels image/text drafts; remaining pointer cannot commit them', async ({ page, context, isMobile }, testInfo) => {
    const inputs = { page, context, isMobile };
    await loadImage(page);
    const before = await snapshot(page), session = await context.newCDPSession(page);
    let touching = false;
    const dispatch = async (type, touchPoints) => {
      await session.send('Input.dispatchTouchEvent', { type, touchPoints });
      touching = touchPoints.length > 0;
    };
    try {
      // Real Chromium input dispatch, not synthetic DOM events; not a physical-device claim.
      await session.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 2 });
      for (const tool of ['#add-image', '#add-text']) {
        await page.locator('#fit').click();
        await page.locator(tool).click();
        const { start, end } = await worldPoints(page, { x: 105, y: 100 }, { x: 274, y: 278 });
        const second = (await worldPoints(page, { x: 550, y: 350 })).start;
        await dispatch('touchStart', [touch(1, start)]);
        await dispatch('touchMove', [touch(1, end)]);
        await expect(page.locator('#draw-preview')).toBeVisible();
        await dispatch('touchStart', [touch(1, end), touch(2, second)]);
        await expect(page.locator('#draw-preview')).toBeHidden();
        const camera = (await snapshot(page)).view;
        const spread = { x: second.x + 20, y: second.y + 10 };
        await dispatch('touchMove', [touch(1, end), touch(2, spread)]); await frames(page);
        const pinched = (await snapshot(page)).view;
        expect(pinched.scale).toBeGreaterThan(camera.scale);
        await dispatch('touchEnd', [touch(1, end)]);
        await dispatch('touchMove', [touch(1, { x: end.x + 10, y: end.y + 8 })]);
        await frames(page);
        expect((await snapshot(page)).view).toEqual(pinched);
        await dispatch('touchEnd', []); await frames(page);
        await expectPreserved(page, before);
        await expect(page.locator('#undo')).toBeDisabled();
        await expect(page.locator('#select-tool')).toHaveAttribute('aria-pressed', 'true');
      }
    } finally {
      try {
        if (touching) await dispatch('touchEnd', []);
        if (!testInfo.project.use.hasTouch) {
          await session.send('Emulation.setTouchEmulationEnabled', { enabled: false });
        }
      } finally { await session.detach(); }
    }
    await page.locator('#fit').click();
    await draw(inputs, 'image', { x: 105, y: 100 }, { x: 274, y: 278 });
  });
});