import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { chmod, mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const alphabetical = (left, right) => left.localeCompare(right);
const prefix = 'BOOT-INF/classes/static/create/editor/';
const libs = path.join(repository, 'build/libs');
const candidates = (await readdir(libs)).filter(name => name.endsWith('.jar') && !name.endsWith('-plain.jar'));
assert.equal(candidates.length, 1, 'Expected exactly one existing bootJar; run ./gradlew bootJar first');
const artifact = path.join(libs, candidates[0]);
const jar = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/jar') : 'jar';
function run(args, cwd) {
    const result = spawnSync(jar, args, { cwd, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
    if (result.error) throw result.error;
    assert.equal(result.status, 0, `JDK jar failed: ${result.stderr}`);
    return result.stdout;
}
const entries = run(['tf', artifact]).trim().split(/\r?\n/);
assert(entries.includes(`${prefix}manifest.json`), 'bootJar lacks editor manifest');
const editorEntries = entries.filter(entry => entry.startsWith(prefix) && !entry.endsWith('/'));
assert.equal(new Set(editorEntries).size, editorEntries.length, 'Duplicate editor ZIP entries');
assert(editorEntries.every(entry => /^BOOT-INF\/classes\/static\/create\/editor\/(?:index\.html|licenses\.txt|manifest\.json|editor-[A-Z0-9]+\.(?:js|css))$/.test(entry)), 'Unexpected/experimental editor resources');
const temporary = await mkdtemp(path.join(os.tmpdir(), 'regata-creator-package-'));
await chmod(temporary, 0o700);
try {
    run(['xf', artifact, ...editorEntries], temporary);
    const read = name => readFile(path.join(temporary, prefix, name));
    const manifest = JSON.parse(await read('manifest.json'));
    assert.equal(manifest.schemaVersion, 1);
    assert.match(manifest.assets.script, /^editor-[A-Z0-9]+\.js$/);
    assert.match(manifest.assets.style, /^editor-[A-Z0-9]+\.css$/);
    const expected = ['index.html', 'licenses.txt', manifest.assets.script, manifest.assets.style].sort(alphabetical);
    assert.deepEqual(Object.keys(manifest.files).sort(alphabetical), expected);
    assert.deepEqual(editorEntries.map(entry => entry.slice(prefix.length)).sort(alphabetical),
        [...expected, 'manifest.json'].sort(alphabetical));
    await Promise.all(expected.map(async name => {
        assert.equal(createHash('sha256').update(await read(name)).digest('hex'), manifest.files[name].sha256,
            `Packaged asset digest mismatch: ${name}`);
    }));
    const html = (await read('index.html')).toString();
    assert(html.includes(`/create/editor/${manifest.assets.script}`));
    assert(html.includes(`/create/editor/${manifest.assets.style}`));
    assert(html.includes('href="/create/legacy"'), 'Missing legacy fallback link');
    assert(!html.includes('href="/create"'), 'Legacy fallback must not reopen the default editor');
    assert(!html.includes('__EDITOR_'));
    const remote = [...html.matchAll(/(src|href)\s*=\s*["']\s*((?:https?:|\/\/)[^"']*)["']/gi)];
    assert.deepEqual(remote.map(match => [match[1], match[2]]),
        [['src', 'https://telegram.org/js/telegram-web-app.js']], 'Only the official Telegram SDK may be remote');
    assert.deepEqual(manifest.hostedIntegrationScripts.map(item => item.url),
        ['https://telegram.org/js/telegram-web-app.js']);
    assert.deepEqual(manifest.runtimeDependencies.map(item => [item.name, item.version, item.license]),
        [['konva', '10.7.1', 'MIT']]);
    assert(manifest.runtimeDependencies[0].bundledInputs.length > 0);
    const licenses = (await read('licenses.txt')).toString();
    const marker = '===== konva@10.7.1 — packaged LICENSE =====\n';
    assert(licenses.startsWith(marker), 'Missing packaged Konva notice');
    const upstream = licenses.slice(marker.length);
    assert(upstream.includes('Copyright') && upstream.includes('Permission is hereby granted'));
    assert.equal(createHash('sha256').update(upstream).digest('hex'), manifest.runtimeDependencies[0].noticeSha256,
        'Full upstream LICENSE digest must survive packaging');
    assert(!/moveable|croact/i.test(JSON.stringify(manifest)), 'Experimental runtime must not ship');
    assert(!/moveable|croact/i.test((await read(manifest.assets.script)).toString()), 'Experimental bundle code');
    console.log(`Verified editor assets, hashes and Konva notice in ${candidates[0]}`);
} finally {
    await rm(temporary, { recursive: true, force: true });
}