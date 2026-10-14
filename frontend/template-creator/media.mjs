// Local Blob input only: no HTTP, uploads, re-encoding or canonicalization.
// Header checks bound declared dimensions, NOT all browser decoder allocations.
// JPEG metadata/SOF must fit in the first 1 MiB, before the first scan. EXIF
// orientation other than 1 is refused: normalize externally before placing areas;
// browser-rotated originals would not share the server's CSV coordinate space.
const MAX_BYTES = 20 * 1024 * 1024;
const HEADER_BYTES = 1024 * 1024;
const PNG = [137, 80, 78, 71, 13, 10, 26, 10];
const SOF = new Set([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf]);
const HEADER_ERROR = 'Cabeçalho da imagem inválido, incompleto ou além de 1 MiB. Exporte outro PNG ou JPEG.';
const ORIENTATION_ERROR = 'A imagem tem orientação EXIF não suportada. Gire/normalize e salve uma cópia sem orientação EXIF antes de marcar as áreas.';
const DECODE_ERROR = 'Não foi possível decodificar a imagem. Use outro PNG ou JPEG.';

class ImageFailure extends Error { }

function requireThat(condition, message = HEADER_ERROR) {
    if (!condition) throw new ImageFailure(message);
}

function dimensions(width, height) {
    requireThat(Number.isInteger(width) && Number.isInteger(height) && width > 0 && height > 0
        && width <= 10000 && height <= 10000 && width * height <= 40000000,
    'A imagem deve ter até 10.000 px por lado e 40 milhões de pixels.');
}

// TIFF offsets are relative to this bounded APP1/eXIf payload, never the file.
function tiffReader(bytes) {
    requireThat(bytes.length >= 8);
    const little = bytes[0] === 0x49 && bytes[1] === 0x49;
    requireThat(little || (bytes[0] === 0x4d && bytes[1] === 0x4d));
    const data = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    function range(offset, length) {
        requireThat(Number.isSafeInteger(offset) && Number.isSafeInteger(length)
            && offset >= 0 && length >= 0 && offset <= bytes.length - length);
    }
    function u16(offset) { range(offset, 2); return data.getUint16(offset, little); }
    function u32(offset) { range(offset, 4); return data.getUint32(offset, little); }
    requireThat(u16(2) === 42);
    return { range, u16, u32 };
}

function exifEntry(reader, entry) {
    const { range, u16, u32 } = reader;
    const sizes = [0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4];
    const tag = u16(entry), type = u16(entry + 2), countValues = u32(entry + 4);
    requireThat(type >= 1 && type < sizes.length);
    const size = countValues * sizes[type];
    const valueOffset = size <= 4 ? entry + 8 : u32(entry + 8);
    if (size > 4) requireThat(valueOffset >= 8);
    range(valueOffset, size);
    if (tag !== 0x0112) return undefined;
    requireThat(type === 3 && countValues === 1);
    const orientation = u16(valueOffset);
    requireThat(orientation >= 1 && orientation <= 8);
    requireThat(orientation === 1, ORIENTATION_ERROR);
    return orientation;
}

function exifOrientation(bytes) {
    const reader = tiffReader(bytes);
    const { range, u16, u32 } = reader;
    let offset = u32(4), orientation, entries = 0;
    const seen = new Set();
    requireThat(offset >= 8);
    while (offset !== 0) {
        requireThat(offset >= 8 && !seen.has(offset) && seen.size < 16);
        seen.add(offset);
        const count = u16(offset);
        requireThat((entries += count) <= 1024);
        range(offset + 2, count * 12 + 4);
        for (let index = 0; index < count; index++) {
            const candidate = exifEntry(reader, offset + 2 + index * 12);
            if (candidate !== undefined) {
                requireThat(orientation === undefined);
                orientation = candidate;
            }
        }
        offset = u32(offset + 2 + count * 12);
    }
    return orientation ?? 1;
}

function inspectPng(bytes) {
    const data = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    requireThat(bytes.length >= 33 && data.getUint32(8) === 13
        && String.fromCodePoint(...bytes.subarray(12, 16)) === 'IHDR');
    const width = data.getUint32(16), height = data.getUint32(20);
    dimensions(width, height);
    // Ancillary headers before IDAT are bounded too, including PNG eXIf. This
    // is not a full PNG validator; decoding remains responsible for CRC/pixels.
    let offset = 33;
    while (offset < bytes.length) {
        requireThat(offset + 8 <= bytes.length);
        const size = data.getUint32(offset);
        const type = String.fromCodePoint(...bytes.subarray(offset + 4, offset + 8));
        if (type === 'IDAT') break;
        requireThat(size <= bytes.length - offset - 12);
        if (type === 'eXIf') exifOrientation(bytes.subarray(offset + 8, offset + 8 + size));
        offset += size + 12;
        if (type === 'IEND') break;
    }
    return { mime: 'image/png', width, height, orientation: 1 };
}

function jpegSegment(bytes, offset) {
    const data = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    requireThat(bytes[offset++] === 0xff);
    while (offset < bytes.length && bytes[offset] === 0xff) offset++;
    requireThat(offset < bytes.length);
    const marker = bytes[offset++];
    if (marker === 0xd9 || marker === 0x01) return { marker, end: offset };
    requireThat(marker !== 0 && marker !== 0xd8 && !(marker >= 0xd0 && marker <= 0xd7)
        && offset + 2 <= bytes.length);
    const length = data.getUint16(offset);
    requireThat(length >= 2 && length <= bytes.length - offset);
    return { marker, length, start: offset + 2, end: offset + length };
}

function jpegExif(bytes, { marker, start, end, length }) {
    if (marker !== 0xe1) return;
    if (String.fromCodePoint(...bytes.subarray(start, Math.min(start + 4, end))) !== 'Exif') return;
    requireThat(length >= 8 && bytes[start + 4] === 0 && bytes[start + 5] === 0);
    exifOrientation(bytes.subarray(start + 6, end));
}

function jpegDimensions(bytes, { start, length }) {
    requireThat(length >= 11);
    const components = bytes[start + 5];
    requireThat(components >= 1 && components <= 4 && length === 8 + 3 * components);
    const data = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const width = data.getUint16(start + 3), height = data.getUint16(start + 1);
    dimensions(width, height);
    return { width, height };
}

function inspectJpeg(bytes) {
    let offset = 2, declared;
    while (offset < bytes.length) {
        const part = jpegSegment(bytes, offset);
        jpegExif(bytes, part);
        if (SOF.has(part.marker)) {
            const next = jpegDimensions(bytes, part);
            requireThat(!declared || (next.width === declared.width && next.height === declared.height));
            declared = next;
        }
        if (part.marker === 0xda) {
            requireThat(part.length >= 8 && bytes[part.start] >= 1 && bytes[part.start] <= 4
                && part.length === 6 + 2 * bytes[part.start]);
        }
        if (part.marker === 0xd9 || part.marker === 0xda) {
            requireThat(declared !== undefined);
            return { mime: 'image/jpeg', ...declared, orientation: 1 };
        }
        offset = part.end;
    }
    throw new ImageFailure(HEADER_ERROR);
}

// Result: {mime,width,height,orientation:1}. Unknown/truncated headers fail closed.
export function inspectHeader(bytes) {
    requireThat(bytes instanceof Uint8Array && bytes.byteLength > 0 && bytes.byteLength <= HEADER_BYTES);
    if (PNG.every((byte, index) => bytes[index] === byte)) return inspectPng(bytes);
    if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
        return inspectJpeg(bytes);
    }
    throw new ImageFailure('O conteúdo do arquivo não corresponde a um PNG ou JPEG válido.');
}

function cancelled(error) {
    return error?.name === 'AbortError'
        ? new ImageFailure('Seleção de imagem cancelada. Selecione o arquivo novamente.') : null;
}

// PNG permits eXIf after the compressed pixels. Walk chunk headers without
// reading IDAT payloads; do not let same-size flips bypass orientation checks.
async function inspectPngMetadata(file, head) {
    let offset = 33, inspected = 0, chunks = 0, seenExif = false;
    async function read(start, length) {
        requireThat(length >= 0 && start >= 0 && length <= file.size - start
            && (inspected += length) <= HEADER_BYTES);
        if (start + length <= head.length) return head.subarray(start, start + length);
        const bytes = new Uint8Array(await file.slice(start, start + length).arrayBuffer());
        requireThat(bytes.length === length);
        return bytes;
    }
    try {
        while (offset < file.size) {
            requireThat(++chunks <= 2048);
            const header = await read(offset, 8);
            const size = new DataView(header.buffer, header.byteOffset, 8).getUint32(0);
            const type = String.fromCodePoint(...header.subarray(4));
            requireThat(size <= file.size - offset - 12);
            if (type === 'eXIf') {
                requireThat(!seenExif);
                seenExif = true;
                exifOrientation(await read(offset + 8, size));
            }
            offset += size + 12;
            if (type === 'IEND') {
                requireThat(size === 0 && offset === file.size);
                return;
            }
        }
        throw new ImageFailure(HEADER_ERROR);
    } catch (error) {
        throw error instanceof ImageFailure ? error : cancelled(error)
            ?? new ImageFailure('Não foi possível ler a imagem. Selecione o arquivo novamente.');
    }
}

export async function loadLocalImage(file) {
    if (file === null || file === undefined) throw new Error('Seleção de imagem cancelada.');
    requireThat(file instanceof Blob && file.size > 0, 'Selecione um arquivo local PNG ou JPEG não vazio.');
    requireThat(file.size <= MAX_BYTES, 'A imagem deve ter no máximo 20 MiB.');
    const mime = file.type.toLowerCase();
    requireThat(mime === '' || ['image/png', 'image/jpeg'].includes(mime),
        'Formato não permitido. Use PNG ou JPEG; SVG e PDF não são aceitos.');
    let bytes;
    try { bytes = new Uint8Array(await file.slice(0, Math.min(HEADER_BYTES, file.size)).arrayBuffer()); }
    catch (error) { throw cancelled(error) ?? new Error('Não foi possível ler a imagem. Selecione o arquivo novamente.'); }
    const declared = inspectHeader(bytes);
    requireThat(!mime || mime === declared.mime, 'O tipo informado não corresponde ao conteúdo PNG/JPEG.');
    if (declared.mime === 'image/png') await inspectPngMetadata(file, bytes);
    requireThat(typeof createImageBitmap === 'function',
        'Este navegador não suporta a verificação dos pixels da imagem. Use um navegador atualizado com suporte a createImageBitmap.');
    let url, image, timer;
    let disposed = false;
    const dispose = () => {
        if (disposed) return;
        disposed = true;
        if (url !== undefined) URL.revokeObjectURL(url);
    };
    try {
        url = URL.createObjectURL(file);
        requireThat(typeof url === 'string' && url.startsWith('blob:'), 'Não foi possível abrir a imagem local com segurança.');
        image = new Image();
        await new Promise((resolve, reject) => {
            let settled = false;
            const fail = error => {
                if (settled) return;
                settled = true;
                reject(error);
            };
            image.onload = async () => {
                if (settled) return;
                let bitmap;
                try {
                    const width = image.naturalWidth, height = image.naturalHeight;
                    dimensions(width, height);
                    requireThat(width === declared.width && height === declared.height,
                        'As dimensões decodificadas não correspondem ao cabeçalho da imagem.');
                    // onload (and HTMLImageElement.decode) can accept corrupt pixels.
                    // Require a full bitmap decode under the same deadline. Close
                    // the temporary bitmap before returning only the HTML image.
                    try { bitmap = await createImageBitmap(image); }
                    catch (error) { throw cancelled(error) ?? new ImageFailure(DECODE_ERROR); }
                    try {
                        if (settled) return;
                        dimensions(bitmap.width, bitmap.height);
                        requireThat(bitmap.width === declared.width && bitmap.height === declared.height
                            && bitmap.width === image.naturalWidth && bitmap.height === image.naturalHeight,
                            'As dimensões decodificadas não correspondem ao cabeçalho da imagem.');
                    } finally {
                        // Also closes a bitmap arriving after timeout or cancellation.
                        bitmap.close();
                    }
                    settled = true;
                    resolve();
                } catch (error) {
                    fail(error instanceof ImageFailure ? error : new ImageFailure(DECODE_ERROR));
                }
            };
            image.onerror = () => fail(new ImageFailure(DECODE_ERROR));
            image.onabort = () => fail(new ImageFailure('Seleção de imagem cancelada.'));
            timer = setTimeout(() => fail(new ImageFailure('A decodificação demorou demais. Use outra imagem.')), 15000);
            image.src = url;
        });
        const width = image.naturalWidth, height = image.naturalHeight;
        dimensions(width, height);
        requireThat(width === declared.width && height === declared.height,
            'As dimensões decodificadas não correspondem ao cabeçalho da imagem.');
        return { image, url, width, height, dispose };
    } catch (error) {
        dispose();
        if (image) image.src = '';
        throw cancelled(error) ?? (error instanceof ImageFailure ? error : new Error('Não foi possível abrir a imagem local.'));
    } finally {
        clearTimeout(timer);
        if (image) { image.onload = null; image.onerror = null; image.onabort = null; }
    }
}