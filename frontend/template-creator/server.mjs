import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { buildEditor, outputDirectory } from './build.mjs';

// Rebuild before binding: never serve stale resources or source/user files.
const manifest = await buildEditor();
const files = new Map();
for (const name of [...Object.keys(manifest.files), 'manifest.json']) {
    files.set(`/create/editor/${name}`, await readFile(path.join(outputDirectory, name)));
}
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8', '.txt': 'text/plain; charset=utf-8', '.json': 'application/json' };
const server = createServer((request, response) => {
    response.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; connect-src 'none'; font-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Cache-Control', 'no-store');
    if (request.headers.host !== '127.0.0.1:4174') {
        response.writeHead(403).end();
        return;
    }
    if (!['GET', 'HEAD'].includes(request.method)) {
        response.setHeader('Allow', 'GET, HEAD');
        response.writeHead(405).end();
        return;
    }
    // No decoding/normalization: encoded traversal, query strings and arbitrary directories fail closed.
    const route = ['/create/editor', '/create/editor/'].includes(request.url)
        ? '/create/editor/index.html' : request.url;
    const bytes = files.get(route);
    if (!bytes) {
        response.writeHead(404).end();
        return;
    }
    response.setHeader('Content-Type', types[path.extname(route)]);
    response.setHeader('Content-Length', bytes.length);
    response.writeHead(200).end(request.method === 'HEAD' ? undefined : bytes);
});
server.requestTimeout = 10_000;
server.headersTimeout = 10_000;
server.listen(4174, '127.0.0.1', () => console.log('Editor: http://127.0.0.1:4174/create/editor/'));
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => server.close());