import { createServer } from 'node:http';
import { readFile, readdir } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

// An isolated, loopback-only read-only server. No Spring, bot, uploads or credentials.
const root = new URL('./', import.meta.url);
const result = await build({ entryPoints: [fileURLToPath(new URL('app.mjs', root))], bundle: true,
  write: false, metafile: true, format: 'esm', platform: 'browser', legalComments: 'inline' });
const packageRoots = new Set();
for (const input of Object.keys(result.metafile.inputs)) {
  const absolute = resolve(input), marker = `${join('node_modules', '')}/`;
  const index = absolute.lastIndexOf(marker);
  if (index < 0) continue;
  const parts = absolute.slice(index + marker.length).split('/');
  const count = parts[0].startsWith('@') ? 2 : 1;
  packageRoots.add(absolute.slice(0, index + marker.length) + parts.slice(0, count).join('/'));
}
const noticeGroups = await Promise.all([...packageRoots].sort((a, b) => a.localeCompare(b)).map(async directory => {
  const metadata = JSON.parse(await readFile(join(directory, 'package.json'), 'utf8'));
  const names = (await readdir(directory)).filter(name => /^(license|licence|copying|notice)(\.|$)/i.test(name));
  if (!names.length) {
    if (typeof metadata.license !== 'string') throw new Error(`No license evidence: ${metadata.name}`);
    // Keep the evidence gap visible. Metadata is not a fabricated upstream notice or legal clearance.
    return [`${metadata.name}@${metadata.version}\nDeclared license: ${metadata.license}\nNOTICE FILE ABSENT: production redistribution requires upstream notice review.\nRepository: ${metadata.repository?.url ?? 'not supplied'}`];
  }
  return Promise.all(names.map(async name => `${metadata.name}@${metadata.version}\n${await readFile(join(directory, name), 'utf8')}`));
}));
const notices = noticeGroups.flat();
const files = new Map([
  ['/', { type: 'text/html; charset=utf-8', data: await readFile(new URL('index.html', root)) }],
  ['/styles.css', { type: 'text/css; charset=utf-8', data: await readFile(new URL('styles.css', root)) }],
  ['/app.js', { type: 'text/javascript; charset=utf-8', data: result.outputFiles[0].contents }],
  ['/licenses.txt', { type: 'text/plain; charset=utf-8', data: notices.join('\n\n--------------------\n\n') }]
]);
const server = createServer((request, response) => {
  if (!['GET', 'HEAD'].includes(request.method)) { response.writeHead(405).end(); return; }
  let path;
  try { path = new URL(request.url, 'http://127.0.0.1').pathname; }
  catch { response.writeHead(400).end(); return; }
  const file = files.get(path);
  if (!file) { response.writeHead(404).end(); return; }
  response.writeHead(200, { 'Content-Type': file.type, 'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; connect-src 'none'; frame-ancestors 'none'; object-src 'none'" });
  response.end(request.method === 'HEAD' ? undefined : file.data);
});
server.listen(4173, '127.0.0.1', () => console.log('Local spike: http://127.0.0.1:4173 (no bot or upload endpoints)'));
server.on('error', () => { console.error('Could not bind local spike server'); process.exitCode = 1; });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close());