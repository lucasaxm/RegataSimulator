'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');
const script = path.resolve('scripts/deploy-release.sh');
const old = 'a'.repeat(40), next = 'b'.repeat(40);

function fixture(options = {}) {
  const base = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'regata-deploy-test-')));
  const root = path.join(base, 'app'), bin = path.join(base, 'fake-bin'), log = path.join(base, 'calls');
  fs.mkdirSync(bin); fs.mkdirSync(root); fs.mkdirSync(path.join(root, 'releases'));
  fs.mkdirSync(path.join(root, 'incoming')); fs.mkdirSync(path.join(root, 'incoming', next));
  fs.writeFileSync(path.join(root, 'incoming', next, 'app.jar'), 'synthetic artifact');
  if (!options.first) {
    fs.mkdirSync(path.join(root, 'releases', old));
    fs.writeFileSync(path.join(root, 'releases', old, 'schema-version'), options.oldSchema || '3');
    fs.symlinkSync('releases/' + old, path.join(root, 'current'));
  }
  const fake = (name, body) => fs.writeFileSync(path.join(bin, name), '#!' + process.execPath + '\n' + body, { mode: 0o700 });
  fake('java', `console.error('openjdk version "${options.java || '21.0.12.1'}"');`);
  fake('timeout', `const r=require('node:child_process').spawnSync(process.argv[3],process.argv.slice(4),{stdio:'inherit',env:process.env});process.exit(r.status??1);`);
  fake('sleep', 'process.exit(0);'); // No real waits or sleep commands in validation.
  fake('mv', `require('node:fs').renameSync(process.argv[3],process.argv[4]);`); // GNU atomic -Tf semantics on macOS test host.
  fake('systemctl', `const f=require('node:fs');f.appendFileSync(${JSON.stringify(log)},process.argv.slice(2).join(' ')+String.fromCharCode(10));
    if(process.argv.includes('show'))console.log(process.argv.includes('--property=MainPID')?${JSON.stringify(options.stuck ? '99' : '0')}: 'inactive');
    process.exit(${options.stopFailure ? "process.argv.includes('stop')?1:0" : '0'});`);
  fake('curl', `const f=require('node:fs');const current=f.existsSync(${JSON.stringify(path.join(root, 'current'))})?f.readlinkSync(${JSON.stringify(path.join(root, 'current'))}):'';
    console.log(JSON.stringify({status:${options.failAll ? "'DOWN'" : options.failNew ? `current==='releases/${next}'?'DOWN':'UP'` : "'UP'"}}));`);
  const checksum = crypto.createHash('sha256').update('synthetic artifact').digest('hex');
  return { base, root, log, run(overrides = {}) {
    return spawnSync('bash', [script, root, next, overrides.checksum || checksum, '3', 'artifact-only-schema-compatible'],
      { encoding: 'utf8', timeout: 20000, env: { ...process.env, PATH: bin + path.delimiter + process.env.PATH, JAVA_BIN: path.join(bin, 'java') } });
  }, clean() { fs.rmSync(base, { recursive: true, force: true }); } };
}

test('deployment shell syntax is valid', () => assert.equal(spawnSync('bash', ['-n', script]).status, 0));
test('successful release stops all writers then atomically selects new artifact', () => {
  const f = fixture(); try {
    const r = f.run(); assert.equal(r.status, 0, r.stderr);
    assert.equal(fs.readlinkSync(path.join(f.root, 'current')), 'releases/' + next);
    assert.equal(fs.readFileSync(path.join(f.root, 'previous'), 'utf8').trim(), 'releases/' + old);
    const calls = fs.readFileSync(f.log, 'utf8'); assert.ok(calls.indexOf('stop') < calls.indexOf('start'));
  } finally { f.clean(); }
});
test('readiness failure restores previous artifact and remains failed', () => {
  const f = fixture({ failNew: true }); try {
    const r = f.run(); assert.equal(r.status, 1, r.stderr);
    assert.equal(fs.readlinkSync(path.join(f.root, 'current')), 'releases/' + old);
    assert.match(r.stderr, /previous artifact restored/);
  } finally { f.clean(); }
});
test('unconfirmed writer stop never switches current', () => {
  const f = fixture({ stuck: true }); try {
    assert.equal(f.run().status, 1); assert.equal(fs.readlinkSync(path.join(f.root, 'current')), 'releases/' + old);
    assert.doesNotMatch(fs.readFileSync(f.log, 'utf8'), /start/);
  } finally { f.clean(); }
});
test('checksum/schema/old-Java refusals have no service effects', () => {
  for (const options of [{ oldSchema: '2' }, { java: '21.0.2' }, { badChecksum: true }]) {
    const f = fixture(options); try {
      assert.equal(f.run(options.badChecksum ? { checksum: '0'.repeat(64) } : {}).status, 2);
      assert.equal(fs.existsSync(f.log), false); assert.equal(fs.readlinkSync(path.join(f.root, 'current')), 'releases/' + old);
    } finally { f.clean(); }
  }
});
test('failed first release leaves writers stopped and no current artifact', () => {
  const f = fixture({ first: true, failAll: true }); try {
    assert.equal(f.run().status, 1); assert.equal(fs.existsSync(path.join(f.root, 'current')), false);
  } finally { f.clean(); }
});
test('service definition supervises cgroup, bounds shutdown and references only operator-owned secret file', () => {
  const unit = fs.readFileSync('scripts/regatasimulator.service', 'utf8');
  for (const field of ['KillMode=control-group', 'TimeoutStopSec=45', 'UMask=0077', 'Restart=on-failure', 'EnvironmentFile=%h/.config/regata/runtime.env']) assert.ok(unit.includes(field));
  assert.equal(unit.includes('subprocess'), false);
});

test('release workflow uses configured pinned host keys, bounded SSH and checked-in security-gated logic', () => {
  const workflow = fs.readFileSync('.github/workflows/release-and-deploy.yml', 'utf8');
  for (const text of ['vars.SERVER_KNOWN_HOSTS', '-n "$SERVER_KNOWN_HOSTS"', 'StrictHostKeyChecking=yes',
    'ConnectTimeout=10', 'dependencyScan', 'scripts/deploy-release.sh', 'verifyMaintenanceJava']) assert.ok(workflow.includes(text), text);
  for (const text of ['ssh-keyscan', 'subprocess', 'nohup', 'pgrep', 'secrets.REGATA_SIMULATOR_ENC_PASSWORD']) assert.equal(workflow.includes(text), false, text);
});