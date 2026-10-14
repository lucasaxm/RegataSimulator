import { createHash } from 'node:crypto';
import { lstat, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

export const frontend = path.dirname(fileURLToPath(import.meta.url));
export const repository = path.resolve(frontend, '../..');
export const outputDirectory = path.join(repository, 'build/generated-resources/creator/static/create/editor');
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

async function guardOutput() {
    const relative = path.relative(repository, outputDirectory);
    if (relative !== 'build/generated-resources/creator/static/create/editor') {
        throw new Error('Refusing cleanup outside the owned editor output');
    }
    let current = repository;
    const paths = relative.split(path.sep).map(component => {
        current = path.join(current, component);
        return current;
    });
    await Promise.all(paths.map(async directory => {
        const stat = await lstat(directory).catch(error => {
            if (error.code === 'ENOENT') return null;
            throw error;
        });
        if (stat && (!stat.isDirectory() || stat.isSymbolicLink())) {
            throw new Error('Refusing non-directory or symlink in editor output path');
        }
    }));
}

async function reviewedRuntime(metafile) {
    if (Object.values(metafile.outputs).some(output => output.imports.some(item => item.external))) {
        throw new Error('Editor bundle must not contain external runtime imports');
    }
    const bundledInputs = [...new Set(Object.values(metafile.outputs).flatMap(output =>
        Object.entries(output.inputs).filter(([, item]) => item.bytesInOutput > 0).map(([input]) => input)))];
    const packageNames = new Set();
    for (const input of bundledInputs) {
        const components = input.replaceAll('\\', '/').split('/');
        const index = components.lastIndexOf('node_modules');
        if (index < 0) continue;
        const name = components[index + 1].startsWith('@')
            ? components.slice(index + 1, index + 3).join('/') : components[index + 1];
        packageNames.add(name);
    }
    if (packageNames.size !== 1 || !packageNames.has('konva')) {
        throw new Error('Only the reviewed Konva runtime graph is permitted; review new packages explicitly');
    }
    const packageRoot = path.join(frontend, 'node_modules/konva');
    const [packageJson, license] = await Promise.all([
        readFile(path.join(packageRoot, 'package.json'), 'utf8'), readFile(path.join(packageRoot, 'LICENSE'), 'utf8')
    ]);
    const metadata = JSON.parse(packageJson);
    if (metadata.name !== 'konva' || metadata.license !== 'MIT' || metadata.version !== '10.7.1') {
        throw new Error('Unreviewed runtime version/license');
    }
    if (!license.includes('Permission is hereby granted') || !license.includes('Copyright')) {
        throw new Error('Missing or incomplete packaged MIT notice');
    }
    return {
        runtimeDependencies: [{ name: metadata.name, version: metadata.version, license: metadata.license,
            notice: 'licenses.txt', noticeSha256: digest(license),
            bundledInputs: bundledInputs.filter(input => input.replaceAll('\\', '/').includes('node_modules/konva/')) }],
        notices: `===== konva@${metadata.version} — packaged LICENSE =====\n${license}`
    };
}

function generatedAssets(outputFiles) {
    const generated = new Map();
    for (const file of outputFiles) {
        const name = path.basename(file.path);
        if (path.dirname(file.path) !== outputDirectory || !/^editor-[A-Z0-9]+\.(js|css)$/.test(name)) {
            throw new Error('Unexpected editor output');
        }
        if (generated.has(name)) throw new Error('Duplicate editor output');
        generated.set(name, file.contents);
    }
    const scripts = [...generated.keys()].filter(name => name.endsWith('.js'));
    const styles = [...generated.keys()].filter(name => name.endsWith('.css'));
    if (scripts.length !== 1 || styles.length !== 1) throw new Error('Expected one JS and one CSS asset');
    return { generated, assets: { script: scripts[0], style: styles[0] } };
}

export async function buildEditor() {
    await guardOutput();
    await rm(outputDirectory, { recursive: true, force: true });
    try {
        const template = await readFile(path.join(frontend, 'index.html'), 'utf8');
        for (const placeholder of ['__EDITOR_SCRIPT__', '__EDITOR_STYLE__']) {
            if (template.split(placeholder).length !== 2) {
                throw new Error(`HTML requires exactly one ${placeholder}`);
            }
        }
        // Load the tool inside the cleanup boundary: a broken install cannot leave old assets behind.
        const { build } = await import('esbuild');
        const result = await build({
            absWorkingDir: frontend,
            entryPoints: [{ in: 'app.mjs', out: 'editor' }, { in: 'style.css', out: 'editor' }],
            outdir: outputDirectory,
            entryNames: '[name]-[hash]',
            bundle: true,
            format: 'esm',
            platform: 'browser',
            target: ['es2022'],
            minify: true,
            sourcemap: false,
            legalComments: 'none',
            metafile: true,
            write: false
        });
        const { runtimeDependencies, notices } = await reviewedRuntime(result.metafile);
        const { generated, assets } = generatedAssets(result.outputFiles);
        const html = template.replace('__EDITOR_SCRIPT__', `/create/editor/${assets.script}`)
            .replace('__EDITOR_STYLE__', `/create/editor/${assets.style}`);
        if (/__(?:EDITOR_SCRIPT|EDITOR_STYLE)__/.test(html)
            || /(?:src|href)\s*=\s*["']\s*(?:https?:|\/\/)/i.test(html)) {
            throw new Error('Editor HTML must use self-hosted assets only');
        }
        generated.set('index.html', Buffer.from(html));
        generated.set('licenses.txt', Buffer.from(notices));
        const lock = JSON.parse(await readFile(path.join(frontend, 'package-lock.json'), 'utf8'));
        const securityInventory = Object.entries(lock.packages).filter(([name]) => name).map(([location, item]) => ({
            location, version: item.version, integrity: item.integrity, dev: Boolean(item.dev),
            optional: Boolean(item.optional), license: item.license ?? 'UPSTREAM_REVIEW_REQUIRED'
        }));
        const manifest = {
            schemaVersion: 1, assets,
            files: Object.fromEntries([...generated].map(([name, bytes]) => [name, { sha256: digest(bytes) }])),
            runtimeDependencies, securityInventory,
            securityGate: 'npm audit --audit-level=low (includes build/test dependencies; not legal clearance)',
            buildToolLicenseReview: 'NOT_ASSESSED: development tools are not shipped; review redistribution separately'
        };
        generated.set('manifest.json', Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`));
        await guardOutput();
        await mkdir(outputDirectory, { recursive: true });
        // Settle every write before cleanup on failure, so no late writer recreates stale output.
        const writes = await Promise.allSettled([...generated].map(([name, bytes]) =>
            writeFile(path.join(outputDirectory, name), bytes)));
        const failed = writes.find(result => result.status === 'rejected');
        if (failed) throw failed.reason;
        console.log(`Built self-hosted editor: ${assets.script}, ${assets.style}`);
        return manifest;
    } catch (error) {
        await guardOutput();
        await rm(outputDirectory, { recursive: true, force: true });
        throw error;
    }
}

async function checkSources() {
    const files = ['app.mjs', 'model.mjs', 'media.mjs', 'build.mjs', 'server.mjs',
        'playwright.config.mjs', 'verify-package.mjs'];
    const tests = await readdir(path.join(frontend, 'test'), { recursive: true });
    files.push(...tests.filter(name => /\.(mjs|cjs|js)$/.test(name)).map(name => path.join('test', name)));
    for (const file of files) {
        const result = spawnSync(process.execPath, ['--check', path.join(frontend, file)], { stdio: 'inherit' });
        if (result.error) throw result.error;
        if (result.status !== 0) throw new Error(`Syntax check failed: ${file}`);
    }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    if (process.argv.length > 3 || (process.argv[2] && process.argv[2] !== '--check')) {
        throw new Error('Usage: node build.mjs [--check]');
    }
    if (process.argv[2] === '--check') await checkSources();
    else await buildEditor();
}