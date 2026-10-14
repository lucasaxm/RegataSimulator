// Local browser input only: no URLs supplied by callers, uploads or encoded copies.
const MAX_BYTES = 20 * 1024 * 1024;
const MAX_DIMENSION = 10000;
const MAX_PIXELS = 40000000;
const PNG_SIGNATURE = [137, 80, 78, 71, 13, 10, 26, 10];

function dimensions(width, height) {
  if (!Number.isInteger(width) || !Number.isInteger(height) || width <= 0 || height <= 0
      || width > MAX_DIMENSION || height > MAX_DIMENSION || width * height > MAX_PIXELS) {
    throw new Error('A imagem deve ter até 10.000 px por lado e 40 milhões de pixels.');
  }
}

function localUrlDisposer(url) {
  let disposed = false;
  return () => {
    if (disposed) return;
    disposed = true; URL.revokeObjectURL(url);
  };
}

function pngDimensions(header) {
  if (header.length < 33 || new DataView(header.buffer).getUint32(8) !== 13
      || String.fromCodePoint(...header.slice(12, 16)) !== 'IHDR') {
    throw new Error('Cabeçalho PNG inválido ou incompleto.');
  }
  const data = new DataView(header.buffer);
  const declared = { width: data.getUint32(16), height: data.getUint32(20) };
  dimensions(declared.width, declared.height); // Reject oversized PNG before any decoder allocation.
  return declared;
}

export async function loadLocalImage(file) {
  if (!(file instanceof Blob) || file.size === 0) throw new Error('Selecione um PNG ou JPEG não vazio.');
  if (file.size > MAX_BYTES) throw new Error('A imagem deve ter no máximo 20 MiB.');
  const mime = file.type.toLowerCase();
  if (mime && !['image/png', 'image/jpeg'].includes(mime)) {
    throw new Error('Formato não permitido. Use PNG ou JPEG; SVG e PDF não são aceitos.');
  }
  let header;
  try { header = new Uint8Array(await file.slice(0, 33).arrayBuffer()); }
  catch { throw new Error('Não foi possível ler a imagem. Selecione o arquivo novamente.'); }
  const png = PNG_SIGNATURE.every((byte, index) => header[index] === byte);
  const jpeg = header.length >= 3 && header[0] === 255 && header[1] === 216 && header[2] === 255;
  if ((!png && !jpeg) || (mime && mime !== (png ? 'image/png' : 'image/jpeg'))) {
    throw new Error('O conteúdo do arquivo não corresponde a um PNG ou JPEG válido.');
  }
  const declared = png ? pngDimensions(header) : null;
  // JPEG dimensions are checked after the browser decodes; this is not a predecode JPEG memory bound.
  const url = URL.createObjectURL(file), image = new Image();
  const dispose = localUrlDisposer(url);
  try {
    await new Promise((resolve, reject) => {
      image.onload = resolve;
      image.onerror = () => reject(new Error('Não foi possível decodificar a imagem. Use outro PNG ou JPEG.'));
      image.src = url;
    });
    const width = image.naturalWidth, height = image.naturalHeight;
    dimensions(width, height);
    if (declared && (width !== declared.width || height !== declared.height)) {
      throw new Error('As dimensões decodificadas não correspondem ao cabeçalho PNG.');
    }
    return { image, url, width, height, dispose };
  } catch (error) {
    dispose();
    throw error;
  } finally {
    image.onload = null; image.onerror = null;
  }
}