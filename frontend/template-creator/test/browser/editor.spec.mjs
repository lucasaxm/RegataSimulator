import { test as base, expect } from '@playwright/test';
import { deflateSync } from 'node:zlib';

const HEADER = 'Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background';
// Coordinates supplied in model.test.mjs; images below are generated, not user media.
const USER_CSV = `${HEADER}\n1,1,105,840,274,840,274,1018,105,1018,0\n2,2,461,842,631,842,631,1020,461,1020,0\n3,3,800,840,969,840,969,1018,800,1018,0`;
const RECT = [[30, 35], [150, 35], [150, 125], [30, 125]];
const SKEW = [[30, 35], [150, 40], [140, 125], [25, 115]];
const csvFor = (points, source = 1, background = 0, position = 1) =>
    [position, source, ...points.flat(), background].join(',');
const oneArea = points => `${HEADER}\n${csvFor(points)}`;
const translated = (points, dx, dy) => points.map(([x, y]) => [x + dx, y + dy]);

function crc32(bytes) {
    let crc = 0xffffffff;
    for (const byte of bytes) {
        crc ^= byte;
        for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
    return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, bytes) {
    const name = Buffer.from(type), size = Buffer.alloc(4), crc = Buffer.alloc(4);
    size.writeUInt32BE(bytes.length);
    crc.writeUInt32BE(crc32(Buffer.concat([name, bytes])));
    return Buffer.concat([size, name, bytes, crc]);
}

function png(width = 200, height = 160, exif, corrupt = false) {
    const header = Buffer.alloc(13);
    header.writeUInt32BE(width, 0); header.writeUInt32BE(height, 4);
    header[8] = 8; header[9] = 6;
    const rows = Buffer.alloc(height * (width * 4 + 1));
    for (let y = 0; y < height; y++) {
        for (let x = 0; x < width; x++) {
            const offset = y * (width * 4 + 1) + 1 + x * 4;
            rows[offset] = 50 + x % 150; rows[offset + 1] = 60 + y % 140;
            rows[offset + 2] = 100; rows[offset + 3] = 255;
        }
    }
    return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
        chunk('IHDR', header), ...(exif ? [chunk('eXIf', exif)] : []),
        chunk('IDAT', corrupt ? Buffer.from('not a zlib stream') : deflateSync(rows)),
        chunk('IEND', Buffer.alloc(0))]);
}

function tiff(orientation = 6, malformed = false) {
    const bytes = Buffer.alloc(26);
    bytes.write('II'); bytes.writeUInt16LE(42, 2); bytes.writeUInt32LE(malformed ? 999 : 8, 4);
    bytes.writeUInt16LE(1, 8); bytes.writeUInt16LE(0x0112, 10);
    bytes.writeUInt16LE(3, 12); bytes.writeUInt32LE(1, 14); bytes.writeUInt16LE(orientation, 18);
    return bytes;
}

// Automatic fixture blocks external traffic and mutations, then reports every
// attempted violation and uncaught application error even when the test fails.
const test = base.extend({
    localOnly: [async ({ page }, use) => {
        const forbidden = [], errors = [];
        page.on('pageerror', error => errors.push(error.message));
        page.on('dialog', dialog => dialog.accept());
        await page.route('**/*', async route => {
            const request = route.request(), url = new URL(request.url());
            if (url.protocol === 'blob:' || url.protocol === 'data:') return route.continue();
            if (url.origin !== 'http://127.0.0.1:4174' || request.method() !== 'GET'
                || !url.pathname.startsWith('/create/editor/')) {
                forbidden.push(`${request.method()} ${request.url()}`);
                return route.abort('blockedbyclient');
            }
            return route.continue();
        });
        await use();
        expect(forbidden, 'Only loopback editor GETs are allowed; no API or external requests').toEqual([]);
        expect(errors, 'Uncaught browser errors').toEqual([]);
    }, { auto: true }]
});

async function settled(page) {
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function open(page, width = 200, height = 160) {
    await page.goto('/create/editor/');
    await expect(page.locator('#empty')).toBeVisible();
    const chooser = page.waitForEvent('filechooser');
    await page.locator('#image-file').click();
    await (await chooser).setFiles({ name: 'generated.png', mimeType: 'image/png', buffer: png(width, height) });
    await expect(page.locator('#empty')).toBeHidden();
    await expect(page.locator('#filename')).toHaveAttribute('title', `generated.png · ${width} × ${height} px`);
    await expect(page.locator('#status')).toHaveAttribute('data-error', 'false');
    await settled(page);
}

async function readCsv(page) {
    await page.locator('#csv-open').click();
    await expect(page.locator('#csv-dialog')).toBeVisible();
    const value = await page.locator('#csv').inputValue();
    await page.getByRole('button', { name: 'Fechar CSV', exact: true }).click();
    await expect(page.locator('#csv-dialog')).toBeHidden();
    return value;
}

async function importCsv(page, value) {
    await page.locator('#csv-open').click();
    await page.locator('#csv').fill(value);
    await page.locator('#csv-import').click();
    await expect(page.locator('#csv-dialog')).toBeHidden();
    await expect(page.locator('#inspector')).toBeVisible();
    await settled(page);
}

async function fit(page) {
    // Drawing/import opens the inspector. Let its resize finish, then Fit,
    // then measure the viewport, never reuse pre-panel coordinates.
    await settled(page);
    await page.locator('#fit').click();
    await settled(page);
    await expect(page.locator('#zoom-value')).toHaveText('100%');
    const box = await page.locator('#viewport').boundingBox();
    expect(box, 'Visible viewport after Fit').not.toBeNull();
    expect(Math.min((box.width - 36) / 200, (box.height - 72) / 160, 1),
        'Generated 200×160 image must fit at exactly 1:1').toBe(1);
    return ([x, y]) => ({ x: box.x + (box.width - 200) / 2 + x,
        y: box.y + (box.height - 160) / 2 + 16 + y });
}

async function drag(page, project, from, to) {
    const a = project(from), b = project(to);
    await page.mouse.move(a.x, a.y); await page.mouse.down();
    await page.mouse.move(b.x, b.y, { steps: 8 }); await page.mouse.up();
    await settled(page);
}

async function draw(page, from = RECT[0], to = RECT[2]) {
    const project = await fit(page);
    await page.locator('#draw').click();
    await drag(page, project, from, to);
    await expect(page.locator('#inspector')).toBeVisible();
    await fit(page);
}

test('empty defaults and local file picker load without upload', async ({ page }) => {
    await page.goto('/create/editor/');
    await expect(page.locator('#empty')).toBeVisible();
    for (const id of ['draw', 'properties', 'undo', 'redo']) await expect(page.locator(`#${id}`)).toBeDisabled();
    await expect(page.locator('#select')).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('#inspector')).toBeHidden();
    expect(await readCsv(page)).toBe('');
    await open(page);
    await expect(page.locator('#draw')).toBeEnabled();
    expect(await readCsv(page)).toBe('');
    await expect(page.locator('#undo')).toBeDisabled();
});

test('native pointer draw produces exact pixels and opens adjustments', async ({ page }) => {
    await open(page); await draw(page);
    expect(await readCsv(page)).toBe(oneArea(RECT));
    await expect(page.locator('#properties')).toHaveAttribute('aria-expanded', 'true');
    await expect(page.locator('#select')).toHaveAttribute('aria-pressed', 'true');
});

test('reverse drawing normalizes TL TR BR BL exactly', async ({ page }) => {
    await open(page); await draw(page, RECT[2], RECT[0]);
    expect(await readCsv(page)).toBe(oneArea(RECT));
});

test('zero-distance click and Escape cancel pending drawing without history', async ({ page }) => {
    await open(page);
    const project = await fit(page), a = project(RECT[0]), b = project(RECT[2]);
    await page.locator('#draw').click(); await page.mouse.click(a.x, a.y);
    expect(await readCsv(page)).toBe('');
    await page.locator('#draw').click();
    await page.mouse.move(a.x, a.y); await page.mouse.down(); await page.mouse.move(b.x, b.y);
    await page.keyboard.press('Escape'); await page.mouse.up();
    expect(await readCsv(page)).toBe('');
    await expect(page.locator('#undo')).toBeDisabled();
});

test('unselected area is selected and moved on the first drag', async ({ page }) => {
    await open(page); await draw(page);
    const second = [[160, 40], [190, 40], [190, 100], [160, 100]];
    await draw(page, second[0], second[2]);
    const project = await fit(page);
    await drag(page, project, [85, 75], [94, 82]);
    expect(await readCsv(page)).toBe(`${HEADER}\n${csvFor(translated(RECT, 9, 7))}\n${csvFor(second, 2, 0, 2)}`);
    await expect(page.locator('#area-list')).toHaveValue('area-1');
    await page.locator('#undo').click();
    expect(await readCsv(page)).toBe(`${HEADER}\n${csvFor(RECT)}\n${csvFor(second, 2, 0, 2)}`);
});

test('perspective mode on an axis-aligned rectangle survives interior drag, independent TL move and undo', async ({ page }) => {
    await open(page); await draw(page);
    await page.locator('#mode').selectOption('perspective');
    const project = await fit(page), moved = translated(RECT, 5, 4);
    await drag(page, project, [85, 75], [90, 79]);
    await expect(page.locator('#mode')).toHaveValue('perspective');
    expect(await readCsv(page)).toBe(oneArea(moved));
    const target = [moved[0][0] + 4, moved[0][1] + 5];
    await drag(page, project, moved[0], target);
    expect(await readCsv(page)).toBe(oneArea(moved.map((point, index) => index === 0 ? target : point)));
    await expect(page.locator('#mode')).toHaveValue('perspective');
    await page.locator('#undo').click();
    expect(await readCsv(page)).toBe(oneArea(moved));
    await expect(page.locator('#mode')).toHaveValue('perspective');
    await page.locator('#undo').click();
    expect(await readCsv(page)).toBe(oneArea(RECT));
    await expect(page.locator('#mode')).toHaveValue('perspective');
});

for (let index = 0; index < 4; index++) {
    test(`rectangle corner ${index} fixes opposite and changes paired axes exactly`, async ({ page }) => {
        await open(page); await draw(page);
        const project = await fit(page), [x, y] = RECT[index];
        const sx = index === 0 || index === 3 ? -1 : 1, sy = index < 2 ? -1 : 1;
        await drag(page, project, RECT[index], [x + sx * 6, y + sy * 7]);
        const expected = RECT.map(([px, py], corner) => [
            (corner === index || corner === (index + 3) % 4 && index % 2 === 0
                || corner === (index + 1) % 4 && index % 2 === 1) ? px + sx * 6 : px,
            (corner === index || corner === (index + 1) % 4 && index % 2 === 0
                || corner === (index + 3) % 4 && index % 2 === 1) ? py + sy * 7 : py]);
        expect(await readCsv(page)).toBe(oneArea(expected));
        expect(expected[(index + 2) % 4]).toEqual(RECT[(index + 2) % 4]);
        await page.locator('#undo').click(); expect(await readCsv(page)).toBe(oneArea(RECT));
    });

    test(`rectangle midpoint ${index} moves only its paired edge on allowed axis`, async ({ page }) => {
        await open(page); await draw(page);
        const project = await fit(page), next = (index + 1) % 4;
        const mid = RECT[index].map((value, axis) => (value + RECT[next][axis]) / 2);
        await drag(page, project, mid, [mid[0] + 8, mid[1] - 6]);
        const expected = RECT.map(([x, y], corner) => {
            const affected = corner === index || corner === next;
            return [x + (affected && index % 2 === 1 ? 8 : 0), y + (affected && index % 2 === 0 ? -6 : 0)];
        });
        expect(await readCsv(page)).toBe(oneArea(expected));
        await page.locator('#undo').click(); expect(await readCsv(page)).toBe(oneArea(RECT));
    });

    test(`perspective corner ${index} changes exactly one point`, async ({ page }) => {
        await open(page); await draw(page); await page.locator('#mode').selectOption('perspective');
        const project = await fit(page), target = [RECT[index][0] + 4, RECT[index][1] + 5];
        await drag(page, project, RECT[index], target);
        const expected = RECT.map((point, corner) => corner === index ? target : point);
        expect(await readCsv(page)).toBe(oneArea(expected));
        await page.locator('#undo').click(); expect(await readCsv(page)).toBe(oneArea(RECT));
    });

    test(`perspective midpoint ${index} translates its pair equally and leaves other points exact`, async ({ page }) => {
        await open(page); await importCsv(page, oneArea(SKEW));
        const project = await fit(page), next = (index + 1) % 4;
        const mid = SKEW[index].map((value, axis) => (value + SKEW[next][axis]) / 2);
        await drag(page, project, mid, [mid[0] + 4, mid[1] - 3]);
        const expected = SKEW.map(([x, y], corner) => corner === index || corner === next ? [x + 4, y - 3] : [x, y]);
        expect(await readCsv(page)).toBe(oneArea(expected));
        await page.locator('#undo').click(); expect(await readCsv(page)).toBe(oneArea(SKEW));
    });

    test(`rectangle dialog anchor ${index}: ghost cancel is inert, Apply fixes anchor/opposite, undo exact`, async ({ page }) => {
        await open(page); await importCsv(page, oneArea(SKEW));
        await page.locator('#corner').selectOption(String(index));
        await page.locator('#mode').selectOption('rectangle');
        await expect(page.locator('#rectify-dialog')).toBeVisible();
        await expect(page.locator('#anchor')).toHaveValue(String(index));
        await expect(page.locator('#rect-apply')).toBeEnabled();
        await page.locator('#rect-width').fill('75'); await page.locator('#rect-height').fill('60');
        await expect(page.locator('#rect-apply')).toBeEnabled();
        await page.locator('#rect-cancel').click();
        expect(await readCsv(page)).toBe(oneArea(SKEW));
        await expect(page.locator('#mode')).toHaveValue('perspective');
        await page.locator('#rectify').click(); await page.locator('#rect-apply').click();
        const fixed = SKEW[index], opposite = SKEW[(index + 2) % 4];
        const left = Math.min(fixed[0], opposite[0]), right = Math.max(fixed[0], opposite[0]);
        const top = Math.min(fixed[1], opposite[1]), bottom = Math.max(fixed[1], opposite[1]);
        const expected = [[left, top], [right, top], [right, bottom], [left, bottom]];
        expect(expected[index]).toEqual(fixed); expect(expected[(index + 2) % 4]).toEqual(opposite);
        expect(await readCsv(page)).toBe(oneArea(expected));
        await expect(page.locator('#mode')).toHaveValue('rectangle');
        await page.locator('#undo').click(); expect(await readCsv(page)).toBe(oneArea(SKEW));
        await expect(page.locator('#mode')).toHaveValue('perspective');
    });
}

test('duplicate/delete undo exactly; source pickers, background plane and list order remain independent', async ({ page }) => {
    await open(page); await draw(page);
    await expect(page.locator('#source option')).toHaveText(['Source 1']);
    await page.locator('#duplicate').click();
    const duplicate = translated(RECT, 10, 10);
    const two = `${HEADER}\n${csvFor(RECT)}\n${csvFor(duplicate, 1, 0, 2)}`;
    expect(await readCsv(page)).toBe(two);
    await expect(page.locator('#source option')).toHaveText(['Source 1', 'Source 2']);
    await page.locator('#source').selectOption('2');
    await page.locator('#background').check();
    const planes = `${HEADER}\n${csvFor(RECT)}\n${csvFor(duplicate, 2, 1, 2)}`;
    expect(await readCsv(page)).toBe(planes);
    await page.locator('#backward').click();
    expect(await readCsv(page)).toBe(`${HEADER}\n${csvFor(duplicate, 2, 1)}\n${csvFor(RECT, 1, 0, 2)}`);
    await expect(page.locator('#source')).toHaveValue('2');
    // Playwright's enabled matcher does not model native <option disabled>.
    await expect(page.locator('#source option:checked')).toHaveAttribute('disabled', '');
    await expect(page.locator('#source option:checked')).toHaveText('Source 2 (original)');
    await page.locator('#undo').click(); expect(await readCsv(page)).toBe(planes);
    await page.locator('#delete').click(); expect(await readCsv(page)).toBe(oneArea(RECT));
    await page.locator('#undo').click(); expect(await readCsv(page)).toBe(planes);
    await page.locator('#undo').click(); await page.locator('#undo').click();
    expect(await readCsv(page)).toBe(two);
    await page.locator('#undo').click(); expect(await readCsv(page)).toBe(oneArea(RECT));
});

test('exact three-row 1080×1114 CSV survives camera actions, resize and nudge undo', async ({ page }) => {
    await open(page, 1080, 1114); await importCsv(page, USER_CSV);
    expect(await readCsv(page)).toBe(USER_CSV);
    for (const id of ['zoom-in', 'zoom-out', 'zoom-value', 'fit', 'fit-areas']) {
        await page.locator(`#${id}`).click(); expect(await readCsv(page)).toBe(USER_CSV);
    }
    await page.locator('#pan').click();
    const box = await page.locator('#viewport').boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down(); await page.mouse.move(box.x + box.width / 2 + 15, box.y + box.height / 2 + 12);
    await page.mouse.up(); expect(await readCsv(page)).toBe(USER_CSV);
    const original = page.viewportSize();
    await page.setViewportSize({ width: original.width - 20, height: original.height - 30 });
    await settled(page); expect(await readCsv(page)).toBe(USER_CSV);
    await page.setViewportSize(original); await settled(page);
    await page.locator('#viewport').focus(); await page.keyboard.press('ArrowRight');
    expect(await readCsv(page)).toBe(`${HEADER}\n1,1,106,840,275,840,275,1018,106,1018,0\n2,2,461,842,631,842,631,1020,461,1020,0\n3,3,800,840,969,840,969,1018,800,1018,0`);
    await page.locator('#viewport').focus(); await page.keyboard.press('Control+z');
    expect(await readCsv(page)).toBe(USER_CSV);
    await page.locator('#undo').click(); expect(await readCsv(page)).toBe('');
    await page.locator('#redo').click(); expect(await readCsv(page)).toBe(USER_CSV);
});

test('invalid CSV preserves previous document and undo history', async ({ page }) => {
    await open(page); await draw(page);
    for (const invalid of [oneArea(RECT).replace('TLx', 'text'), `${HEADER}\n1,3,30,35,150,35,150,125,30,125,0`,
        `${HEADER}\n1,1,30,35,150,125,150,35,30,125,0`, `${HEADER}\n1,1,200,0,220,0,220,20,200,20,0`]) {
        await page.locator('#csv-open').click(); await page.locator('#csv').fill(invalid);
        await page.locator('#csv-import').click();
        await expect(page.locator('#csv-error')).not.toBeEmpty();
        await expect(page.locator('#csv-dialog')).toBeVisible();
        await page.getByRole('button', { name: 'Fechar CSV', exact: true }).click();
        expect(await readCsv(page)).toBe(oneArea(RECT));
    }
    await page.locator('#undo').click(); expect(await readCsv(page)).toBe('');
});

test('editing the old document during new bitmap validation preserves changes; a fresh confirmed load succeeds', async ({ page }) => {
    await open(page); await draw(page);
    await page.evaluate(() => {
        const nativeDecode = window.createImageBitmap.bind(window);
        let delayNext = true;
        window.createImageBitmap = async (...args) => {
            const delay = delayNext;
            delayNext = false;
            const bitmap = await nativeDecode(...args);
            if (delay) {
                // Test-local barrier: native decoding completes, but validation cannot
                // finish until the test edits the old document and releases this event.
                await new Promise(resolve => {
                    document.addEventListener('test-release-bitmap-validation', () => {
                        delete document.documentElement.dataset.bitmapValidationPending;
                        resolve();
                    }, { once: true });
                    document.documentElement.dataset.bitmapValidationPending = 'true';
                });
            }
            return bitmap;
        };
    });
    const replacement = { name: 'replacement.png', mimeType: 'image/png', buffer: png(240, 180) };
    const firstConfirmation = page.waitForEvent('dialog');
    await page.locator('#image-file').setInputFiles(replacement);
    expect((await firstConfirmation).message()).toBe('Trocar a imagem descarta as áreas atuais. Continuar?');
    await expect(page.locator('html')).toHaveAttribute('data-bitmap-validation-pending', 'true');
    await expect(page.locator('#filename')).toHaveAttribute('title', 'generated.png · 200 × 160 px');
    await page.locator('#viewport').focus(); await expect(page.locator('#viewport')).toBeFocused();
    await page.keyboard.press('ArrowRight');
    await page.evaluate(() => document.dispatchEvent(new Event('test-release-bitmap-validation')));
    await expect(page.locator('#status')).toHaveAttribute('data-error', 'true');
    await expect(page.locator('#status')).toHaveText('As alterações atuais foram preservadas. Escolha a nova imagem novamente para confirmar a troca.');
    await expect(page.locator('#filename')).toHaveAttribute('title', 'generated.png · 200 × 160 px');
    expect(await readCsv(page)).toBe(oneArea(translated(RECT, 1, 0)));
    await expect(page.locator('#undo')).toBeEnabled();
    await page.locator('#undo').click();
    expect(await readCsv(page)).toBe(oneArea(RECT));
    await expect(page.locator('#undo')).toBeEnabled();
    const secondConfirmation = page.waitForEvent('dialog');
    await page.locator('#image-file').setInputFiles(replacement);
    expect((await secondConfirmation).message()).toBe('Trocar a imagem descarta as áreas atuais. Continuar?');
    await expect(page.locator('#filename')).toHaveAttribute('title', 'replacement.png · 240 × 180 px');
    await expect(page.locator('#status')).toHaveAttribute('data-error', 'false');
    await expect(page.locator('html')).not.toHaveAttribute('data-bitmap-validation-pending', 'true');
    expect(await readCsv(page)).toBe('');
    await expect(page.locator('#undo')).toBeDisabled();
    await expect(page.locator('#redo')).toBeDisabled();
    await expect(page.locator('#inspector')).toBeHidden();
});

const badImages = [
    ['corrupt PNG', 'corrupt.png', 'image/png', png(200, 160, undefined, true), 'Não foi possível decodificar a imagem. Use outro PNG ou JPEG.'],
    ['SVG', 'refused.svg', 'image/svg+xml', Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="200" height="160"/>'), 'Formato não permitido. Use PNG ou JPEG; SVG e PDF não são aceitos.'],
    ['rotated EXIF', 'rotated.png', 'image/png', png(200, 160, tiff()), 'A imagem tem orientação EXIF não suportada. Gire/normalize e salve uma cópia sem orientação EXIF antes de marcar as áreas.'],
    ['post-IDAT EXIF flip', 'flipped.png', 'image/png', Buffer.concat([png().subarray(0, -12), chunk('eXIf', tiff(3)), chunk('IEND', Buffer.alloc(0))]), 'A imagem tem orientação EXIF não suportada. Gire/normalize e salve uma cópia sem orientação EXIF antes de marcar as áreas.'],
    ['malformed EXIF', 'bad-exif.png', 'image/png', png(200, 160, tiff(1, true)), 'Cabeçalho da imagem inválido, incompleto ou além de 1 MiB. Exporte outro PNG ou JPEG.']
];
for (const [label, name, mimeType, buffer, error] of badImages) {
    test(`${label} is refused without replacing old image, geometry or history`, async ({ page }) => {
        await open(page); await draw(page);
        await page.locator('#image-file').setInputFiles({ name, mimeType, buffer });
        await expect(page.locator('#status')).not.toHaveText('Abrindo imagem local…');
        // Soft assertions still fail the test, but expose all preservation losses.
        await expect.soft(page.locator('#status')).toHaveAttribute('data-error', 'true');
        await expect.soft(page.locator('#status')).toHaveText(error);
        await expect.soft(page.locator('#filename')).toHaveAttribute('title', 'generated.png · 200 × 160 px');
        expect.soft(await readCsv(page)).toBe(oneArea(RECT));
        await expect.soft(page.locator('#undo')).toBeEnabled();
        await page.locator('#viewport').focus(); await page.keyboard.press('Control+z');
        expect.soft(await readCsv(page)).toBe('');
    });
}

test('closed-keyboard canvas stays ≥55% on mobile with inspector open; inspector scrolls, not body', async ({ page }) => {
    await open(page); await draw(page);
    await page.locator('#viewport').focus();
    const metrics = await page.evaluate(() => {
        const viewport = document.querySelector('#viewport').getBoundingClientRect();
        const panel = document.querySelector('.panel-content');
        return { width: innerWidth, height: innerHeight, visualHeight: visualViewport.height,
            canvasHeight: viewport.height, panelHeight: panel.clientHeight, panelScrollHeight: panel.scrollHeight,
            bodyHeight: document.body.scrollHeight, scrollY };
    });
    expect(metrics.visualHeight, 'No virtual keyboard is open').toBe(metrics.height);
    if (metrics.width <= 700) {
        expect(metrics.canvasHeight / metrics.visualHeight,
            `Canvas ratio with inspector open: ${JSON.stringify(metrics)}`).toBeGreaterThanOrEqual(0.55);
        expect(metrics.panelScrollHeight, 'Mobile inspector must have its own scrollable content')
            .toBeGreaterThan(metrics.panelHeight);
    }
    expect(metrics.bodyHeight, 'Body must not overflow viewport').toBeLessThanOrEqual(metrics.height);
    const panel = page.locator('.panel-content');
    await page.locator('#delete').scrollIntoViewIfNeeded();
    await page.locator('#source').scrollIntoViewIfNeeded();
    const before = await panel.evaluate(element => element.scrollTop);
    const box = await panel.boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.wheel(0, 600);
    if (metrics.panelScrollHeight > metrics.panelHeight) {
        await expect.poll(() => panel.evaluate(element => element.scrollTop)).toBeGreaterThan(before);
    }
    expect(await page.evaluate(() => scrollY)).toBe(0);
    expect(await readCsv(page)).toBe(oneArea(RECT));
});

test('viewport focus keyboard nudges exactly; input focus never nudges; CtrlZ restores exact CSV', async ({ page }) => {
    await open(page); await draw(page);
    await page.locator('#viewport').focus(); await expect(page.locator('#viewport')).toBeFocused();
    await page.keyboard.press('ArrowRight'); await page.keyboard.press('Shift+ArrowDown');
    expect(await readCsv(page)).toBe(oneArea(translated(RECT, 1, 10)));
    await page.locator('#viewport').focus(); await page.keyboard.press('Control+z');
    expect(await readCsv(page)).toBe(oneArea(translated(RECT, 1, 0)));
    await page.locator('#viewport').focus(); await page.keyboard.press('Control+z');
    expect(await readCsv(page)).toBe(oneArea(RECT));
    await page.locator('#x').focus(); await page.keyboard.press('ArrowRight');
    expect(await readCsv(page)).toBe(oneArea(RECT));
});

async function touchSession(page) {
    const session = await page.context().newCDPSession(page);
    await session.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    return session;
}

test('native CDP second finger cancels draw; remaining finger cannot commit; new gesture works', async ({ page }) => {
    await open(page);
    const project = await fit(page), a = project([40, 45]), b = project([100, 90]), c = project([150, 100]);
    const session = await touchSession(page);
    const finger = (id, point) => ({ id, ...point, radiusX: 1, radiusY: 1, force: 1 });
    await page.locator('#draw').click();
    await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [finger(1, a)] });
    await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [finger(1, b)] });
    await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [finger(1, b), finger(2, c)] });
    await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [finger(1, b)] });
    await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [finger(1, c)] });
    await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    expect(await readCsv(page)).toBe(''); await expect(page.locator('#undo')).toBeDisabled();
    await session.detach();
    await draw(page); expect(await readCsv(page)).toBe(oneArea(RECT));
});

test('pointercancel during pending multi-touch prevents any remaining pointer from committing', async ({ page }) => {
    await open(page);
    const project = await fit(page), a = project([40, 45]), b = project([100, 90]), c = project([150, 100]);
    const session = await touchSession(page);
    const finger = (id, point) => ({ id, ...point, radiusX: 1, radiusY: 1, force: 1 });
    await page.locator('#draw').click();
    await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [finger(1, a)] });
    await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [finger(1, b)] });
    await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [finger(1, b), finger(2, c)] });
    // Only the cancellation event is injected; all down/move/up events are native.
    // Query native capture ownership only, without app/model state or new globals.
    await page.evaluate(() => {
        const viewport = document.querySelector('#viewport');
        const captured = Array.from({ length: 32 }, (_, index) => index + 1)
            .filter(id => viewport.hasPointerCapture(id));
        if (captured.length !== 2) throw new Error(`Expected two native captured touch pointers, got ${captured}`);
        viewport.dispatchEvent(new PointerEvent('pointercancel', {
            bubbles: true, pointerType: 'touch', pointerId: captured[0], isPrimary: true
        }));
    });
    await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [finger(1, b)] });
    await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [finger(1, c)] });
    await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    expect(await readCsv(page)).toBe(''); await expect(page.locator('#undo')).toBeDisabled();
    await session.detach();
    await draw(page); expect(await readCsv(page)).toBe(oneArea(RECT));
});