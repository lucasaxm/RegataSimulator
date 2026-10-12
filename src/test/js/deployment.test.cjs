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
  const home = path.join(base, 'home'), unitDir = path.join(home, '.config/systemd/user');
  fs.mkdirSync(unitDir, { recursive: true }); fs.mkdirSync(path.join(home, '.config/regata'), { recursive: true });
  const unitFile = path.join(unitDir, 'regatasimulator.service'), hostDir = unitFile + '.d';
  const oldUnit = '[Unit]\nDescription=preserved legacy unit\n[Service]\nExecStart=/legacy/java -jar /legacy/app.jar\n';
  fs.writeFileSync(unitFile, oldUnit); fs.mkdirSync(hostDir);
  const hostSettings = '[Service]\nMemoryMax=448M\nMemorySwapMax=64M\nEnvironment=REGATA_JAVA_HEAP_MIN=64m\nEnvironment=REGATA_JAVA_HEAP_MAX=256m\n';
  fs.writeFileSync(path.join(hostDir, 'host.conf'), hostSettings);
  fs.writeFileSync(path.join(home, '.config/regata/runtime.env'), 'DO_NOT_CHANGE=private-fixture\n');
  fs.mkdirSync(bin); fs.mkdirSync(root); fs.mkdirSync(path.join(root, 'releases'));
  fs.mkdirSync(path.join(root, 'incoming')); fs.mkdirSync(path.join(root, 'incoming', next));
  fs.writeFileSync(path.join(root, 'incoming', next, 'app.jar'), 'synthetic artifact');
  const unitSource = fs.readFileSync('scripts/regatasimulator.service');
  const launcherSource = fs.readFileSync('scripts/run-service.sh');
  fs.writeFileSync(path.join(root, 'incoming', next, 'regatasimulator.service'), unitSource);
  fs.writeFileSync(path.join(root, 'incoming', next, 'run-service.sh'), launcherSource);
  if (!options.first) {
    fs.mkdirSync(path.join(root, 'releases', old));
    fs.writeFileSync(path.join(root, 'releases', old, 'app.jar'), 'old artifact');
    fs.writeFileSync(path.join(root, 'releases', old, 'schema-version'), options.oldSchema || '3');
    fs.symlinkSync('releases/' + old, path.join(root, 'current'));
  }
  const fake = (name, body) => fs.writeFileSync(path.join(bin, name), '#!' + process.execPath + '\n' + body, { mode: 0o700 });
  fake('java', `console.error('openjdk version "${options.java || '21.0.12.1'}"');`);
  fake('timeout', `const r=require('node:child_process').spawnSync(process.argv[3],process.argv.slice(4),{stdio:'inherit',env:process.env});process.exit(r.status??1);`);
  fake('sleep', 'process.exit(0);'); // No real waits or sleep commands in validation.
  fake('flock', `process.exit(${options.lockFailure ? 1 : 0});`);
  if (options.restoreCopyFailure) fake('cp', `if(process.argv[2].endsWith('/previous-unit.service'))process.exit(1);
    process.exit(require('node:child_process').spawnSync('/bin/cp',process.argv.slice(2),{stdio:'inherit'}).status??1);`);
  fake('systemd-analyze', `require('node:fs').appendFileSync(${JSON.stringify(log)},'verify'+String.fromCharCode(10));process.exit(${options.verifyFailure ? 1 : 0});`);
  fake('mv', `const f=require('node:fs');if(process.argv[4]===${JSON.stringify(unitFile)}&&${!!options.installFailure}) {
    const marker=${JSON.stringify(path.join(base, 'install-failed'))};if(!f.existsSync(marker)){f.writeFileSync(marker,'once');process.exit(1);}
  }f.renameSync(process.argv[3],process.argv[4]);`); // GNU atomic -Tf semantics on macOS test host.
  fake('systemctl', `const f=require('node:fs');f.appendFileSync(${JSON.stringify(log)},process.argv.slice(2).join(' ')+String.fromCharCode(10));
    const running=${JSON.stringify(path.join(base, 'running'))};
    if(process.argv.includes('stop')) {
      if(f.existsSync(running))f.unlinkSync(running);
      if(${!!options.runtimeChange})f.appendFileSync(${JSON.stringify(path.join(home, '.config/regata/runtime.env'))},'OPERATOR_CHANGED=true');
      if(${!!options.invalidDropin})f.appendFileSync(${JSON.stringify(path.join(hostDir, 'host.conf'))},'ExecStart=/operator/change');
    }
    if(process.argv.includes('start')) {
      if(${!!options.startFailure}){const marker=${JSON.stringify(path.join(base, 'start-failed'))};if(!f.existsSync(marker)){f.writeFileSync(marker,'once');process.exit(1);}}
      if(!${!!options.inactiveAfterStart})f.writeFileSync(running,'active');
    }
    if(process.argv.includes('show')) {
      if(process.argv.includes('--property=Environment'))console.log('REGATA_JAVA_BIN='+${JSON.stringify(path.join(bin, 'java'))});
      else if(process.argv.includes('--property=DropInPaths'))console.log(${JSON.stringify(options.unmanagedDropin ? '/etc/unmanaged.conf' : path.join(hostDir, 'host.conf'))});
      else console.log(process.argv.includes('--property=MainPID')?${options.stuck ? "'99'" : "f.existsSync(running)?'42':'0'"}: f.existsSync(running)?'active':'inactive');
    }
    if(process.argv.includes('daemon-reload')&&${!!options.reloadFailure}) {
      const marker=${JSON.stringify(path.join(base, 'reload-failed'))};if(!f.existsSync(marker)){f.writeFileSync(marker,'failed once');process.exit(1);}
    }
    process.exit(${options.stopFailure ? "process.argv.includes('stop')?1:0" : '0'});`);
  fake('curl', `const f=require('node:fs');const current=f.existsSync(${JSON.stringify(path.join(root, 'current'))})?f.readlinkSync(${JSON.stringify(path.join(root, 'current'))}):'';
    const counter=${JSON.stringify(path.join(base, 'readiness-calls'))};const calls=f.existsSync(counter)?Number(f.readFileSync(counter,'utf8'))+1:1;f.writeFileSync(counter,String(calls));
    console.log(JSON.stringify({status:${options.failAll ? "'DOWN'" : options.failNew ? `current==='releases/${next}'?'DOWN':'UP'` : options.readyAfter ? `calls>${options.readyAfter}?'UP':'DOWN'` : "'UP'"}}));`);
  const checksum = crypto.createHash('sha256').update('synthetic artifact').digest('hex');
  const unitChecksum = crypto.createHash('sha256').update(unitSource).digest('hex');
  const launcherChecksum = crypto.createHash('sha256').update(launcherSource).digest('hex');
  return { base, root, log, home, unitFile, oldUnit, hostDir, hostSettings, run(overrides = {}) {
    return spawnSync('bash', [script, root, next, overrides.checksum || checksum, '3', 'artifact-and-unit-schema-compatible',
      overrides.unitChecksum || unitChecksum, overrides.launcherChecksum || launcherChecksum],
      { encoding: 'utf8', timeout: 60000, env: { ...process.env, HOME: home, PATH: bin + path.delimiter + process.env.PATH, JAVA_BIN: overrides.javaBin || path.join(bin, 'java'), READINESS_ATTEMPTS: overrides.readinessAttempts || (options.failNew || options.failAll || options.inactiveAfterStart ? '3' : '30') } });
  }, clean() { fs.rmSync(base, { recursive: true, force: true }); } };
}

test('deployment shell syntax is valid', () => assert.equal(spawnSync('bash', ['-n', script]).status, 0));
test('successful release stops all writers then atomically selects new artifact', () => {
  const f = fixture(); try {
    const r = f.run(); assert.equal(r.status, 0, r.stderr);
    assert.equal(fs.readlinkSync(path.join(f.root, 'current')), 'releases/' + next);
    assert.equal(fs.readFileSync(path.join(f.root, 'previous'), 'utf8').trim(), 'releases/' + old);
    const calls = fs.readFileSync(f.log, 'utf8'); assert.ok(calls.indexOf('stop') < calls.indexOf('start'));
    assert.ok(calls.indexOf('verify') < calls.indexOf('stop'));
    assert.ok(calls.indexOf('stop') < calls.indexOf('daemon-reload'));
    assert.match(fs.readFileSync(f.unitFile, 'utf8'), new RegExp('ExecStart=' + f.root + '/current/run-service.sh'));
    assert.equal(fs.readFileSync(path.join(f.hostDir, 'host.conf'), 'utf8'), f.hostSettings);
    assert.equal(fs.readFileSync(path.join(f.home, '.config/regata/runtime.env'), 'utf8'), 'DO_NOT_CHANGE=private-fixture\n');
    assert.equal(fs.readFileSync(path.join(f.root, 'releases', next, 'previous-unit.service'), 'utf8'), f.oldUnit);
  } finally { f.clean(); }
});
test('readiness failure restores previous artifact and remains failed', () => {
  const f = fixture({ failNew: true }); try {
    const r = f.run(); assert.equal(r.status, 1, r.stderr);
    assert.equal(fs.readlinkSync(path.join(f.root, 'current')), 'releases/' + old);
    assert.match(r.stderr, /previous unit and artifact restored/);
    assert.equal(fs.readFileSync(f.unitFile, 'utf8'), f.oldUnit);
  } finally { f.clean(); }
});

test('configured readiness allowance accepts startup beyond the old 30-attempt limit', () => {
  const f = fixture({ readyAfter: 35 }); try {
    const r = f.run({ readinessAttempts: '40' });
    assert.equal(r.status, 0, r.stderr);
    assert.equal(fs.readlinkSync(path.join(f.root, 'current')), 'releases/' + next);
  } finally { f.clean(); }
});

test('invalid readiness allowances are refused before service or artifact changes', () => {
  const f = fixture(); try {
    for (const readinessAttempts of ['0', '301', '-1', 'abc', '01']) {
      assert.equal(f.run({ readinessAttempts }).status, 2);
      assert.equal(fs.existsSync(f.log), false);
      assert.equal(fs.readlinkSync(path.join(f.root, 'current')), 'releases/' + old);
    }
  } finally { f.clean(); }
});

test('default readiness allowance provides bounded headroom for constrained hosts', () => {
  assert.match(fs.readFileSync(script, 'utf8'), /readiness_attempts=\$\{READINESS_ATTEMPTS:-120\}/);
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
      assert.doesNotMatch(fs.existsSync(f.log) ? fs.readFileSync(f.log, 'utf8') : '', /stop|start|daemon-reload/);
      assert.equal(fs.readlinkSync(path.join(f.root, 'current')), 'releases/' + old);
    } finally { f.clean(); }
  }
});
test('failed first release leaves writers stopped and no current artifact', () => {
  const f = fixture({ first: true, failAll: true }); try {
    assert.equal(f.run().status, 1); assert.equal(fs.existsSync(path.join(f.root, 'current')), false);
    assert.equal(fs.readFileSync(f.unitFile, 'utf8'), f.oldUnit);
  } finally { f.clean(); }
});

test('unit verification and deployment lock failures do not stop the current service', () => {
  for (const options of [{ verifyFailure: true }, { lockFailure: true }, { unmanagedDropin: true }]) {
    const f = fixture(options); try {
      assert.equal(f.run().status, 2);
      assert.equal(fs.readFileSync(f.unitFile, 'utf8'), f.oldUnit);
      assert.equal(fs.readlinkSync(path.join(f.root, 'current')), 'releases/' + old);
      assert.doesNotMatch(fs.existsSync(f.log) ? fs.readFileSync(f.log, 'utf8') : '', /stop|start|daemon-reload/);
    } finally { f.clean(); }
  }
});

test('installation and daemon-reload failure restore captured legacy unit and old artifact', () => {
  for (const options of [{ reloadFailure: true }, { installFailure: true }, { startFailure: true }]) {
    const f = fixture(options); try {
      const r = f.run(); assert.equal(r.status, 1, r.stderr);
      assert.equal(fs.readFileSync(f.unitFile, 'utf8'), f.oldUnit);
      assert.equal(fs.readlinkSync(path.join(f.root, 'current')), 'releases/' + old);
      assert.match(r.stderr, /previous unit and artifact restored/);
      assert.equal(fs.readFileSync(path.join(f.hostDir, 'host.conf'), 'utf8'), f.hostSettings);
    } finally { f.clean(); }
  }
});

test('a healthy HTTP response cannot approve an inactive service', () => {
  const f = fixture({ first: true, inactiveAfterStart: true }); try {
    const result = f.run(); assert.equal(result.status, 1);
    assert.equal(fs.existsSync(path.join(f.root, 'current')), false);
    assert.equal(fs.readFileSync(f.unitFile, 'utf8'), f.oldUnit);
  } finally { f.clean(); }
});

test('failed rollback unit copy does not start either artifact', () => {
  const f = fixture({ reloadFailure: true, restoreCopyFailure: true }); try {
    const result = f.run(); assert.equal(result.status, 1, result.stderr);
    assert.match(result.stderr, /Previous unit restore failed; writers stopped/);
    assert.doesNotMatch(fs.readFileSync(f.log, 'utf8'), /--user start/);
  } finally { f.clean(); }
});

test('operator runtime or invalid drop-in change during stop prevents startup', () => {
  for (const options of [{ runtimeChange: true }, { invalidDropin: true }]) {
    const f = fixture(options); try {
      const result = f.run(); assert.equal(result.status, 1, result.stderr);
      assert.match(result.stderr, /Operator settings changed during writer stop/);
      assert.equal(fs.readlinkSync(path.join(f.root, 'current')), 'releases/' + old);
      assert.equal(fs.readFileSync(f.unitFile, 'utf8'), f.oldUnit);
      assert.doesNotMatch(fs.readFileSync(f.log, 'utf8'), /--user start/);
    } finally { f.clean(); }
  }
});

test('failed fallback readiness stops the previous service for manual recovery', () => {
  const f = fixture({ failAll: true }); try {
    const result = f.run(); assert.equal(result.status, 1, result.stderr);
    assert.match(result.stderr, /Previous unit\/artifact readiness failed; writers stopped/);
    assert.equal(fs.existsSync(path.join(f.base, 'running')), false);
    assert.equal(fs.readFileSync(f.unitFile, 'utf8'), f.oldUnit);
  } finally { f.clean(); }
});

test('unit/launcher checksum, missing file, Java mismatch and startup overrides refuse before stopping', () => {
  for (const mode of ['unitHash', 'launcherHash', 'missingLauncher', 'symlinkUnit', 'javaMismatch', 'execOverride']) {
    const f = fixture(); try {
      const overrides = {};
      if (mode === 'unitHash') overrides.unitChecksum = '0'.repeat(64);
      if (mode === 'launcherHash') overrides.launcherChecksum = '0'.repeat(64);
      if (mode === 'missingLauncher') fs.unlinkSync(path.join(f.root, 'incoming', next, 'run-service.sh'));
      if (mode === 'symlinkUnit') {
        const unit = path.join(f.root, 'incoming', next, 'regatasimulator.service');fs.unlinkSync(unit);fs.symlinkSync(f.unitFile, unit);
      }
      if (mode === 'javaMismatch') overrides.javaBin = '/unreviewed/java';
      if (mode === 'execOverride') fs.appendFileSync(path.join(f.hostDir, 'host.conf'), 'ExecStart=/old/command\n');
      assert.equal(f.run(overrides).status, 2, mode);
      assert.doesNotMatch(fs.existsSync(f.log) ? fs.readFileSync(f.log, 'utf8') : '', /stop|start|daemon-reload/);
      assert.equal(fs.readFileSync(f.unitFile, 'utf8'), f.oldUnit);
    } finally { f.clean(); }
  }
});

test('release launcher preserves host Java/heap settings and shared IPv4 flags', () => {
  const f = fixture(); try {
    const release = path.join(f.root, 'incoming', next), output = path.join(f.base, 'java-arguments');
    const java = path.join(f.base, 'host-java');
    fs.writeFileSync(java, '#!' + process.execPath + '\nrequire("node:fs").writeFileSync(' + JSON.stringify(output) + ',JSON.stringify(process.argv.slice(2)));', { mode: 0o700 });
    const result = spawnSync('bash', [path.join(release, 'run-service.sh')], { encoding: 'utf8',
      env: { ...process.env, REGATA_JAVA_BIN: java, REGATA_JAVA_HEAP_MIN: '64m', REGATA_JAVA_HEAP_MAX: '256m' } });
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(JSON.parse(fs.readFileSync(output, 'utf8')), ['-Djava.net.preferIPv4Stack=true', '-Xms64m', '-Xmx256m', '-jar', path.join(release, 'app.jar')]);
    const invalid = spawnSync('bash', [path.join(release, 'run-service.sh')], { encoding: 'utf8',
      env: { ...process.env, REGATA_JAVA_BIN: java, REGATA_JAVA_HEAP_MAX: '256m;bad' } });
    assert.equal(invalid.status, 2);
    assert.equal(fs.readFileSync(path.join(f.home, '.config/regata/runtime.env'), 'utf8'), 'DO_NOT_CHANGE=private-fixture\n');
  } finally { f.clean(); }
});
test('service definition supervises cgroup, bounds shutdown and references only operator-owned secret file', () => {
  const unit = fs.readFileSync('scripts/regatasimulator.service', 'utf8');
  for (const field of ['KillMode=control-group', 'TimeoutStopSec=45', 'UMask=0077', 'Restart=on-failure', 'EnvironmentFile=%h/.config/regata/runtime.env', 'current/run-service.sh']) assert.ok(unit.includes(field));
  assert.ok(fs.readFileSync('scripts/run-service.sh', 'utf8').includes('-Djava.net.preferIPv4Stack=true'));
  assert.equal(unit.includes('subprocess'), false);
});

test('release workflow uses configured pinned host keys, bounded SSH and checked-in security-gated logic', () => {
  const workflow = fs.readFileSync('.github/workflows/release-and-deploy.yml', 'utf8');
  for (const text of ['vars.SERVER_KNOWN_HOSTS', '-n "$SERVER_KNOWN_HOSTS"', 'StrictHostKeyChecking=yes',
    'ConnectTimeout=10', 'dependencyScan', 'scripts/deploy-release.sh', 'verifyMaintenanceJava',
    'vars.SERVER_JAVA_BIN', 'java_bin=${SERVER_JAVA_BIN:-/usr/bin/java}', "JAVA_BIN='$java_bin'"]) assert.ok(workflow.includes(text), text);
  for (const text of ['unit_checksum=', 'launcher_checksum=', 'scripts/run-service.sh', 'artifact-and-unit-schema-compatible']) assert.ok(workflow.includes(text), text);
  for (const text of ['ssh-keyscan', 'subprocess', 'nohup', 'pgrep', 'secrets.REGATA_SIMULATOR_ENC_PASSWORD']) assert.equal(workflow.includes(text), false, text);
});

test('both CI workflows verify the exact maintenance JDK archive instead of unsupported four-part SemVer', () => {
  for (const file of ['build-and-test-pull-request.yml', 'release-and-deploy.yml']) {
    const workflow = fs.readFileSync('.github/workflows/' + file, 'utf8');
    for (const text of ['jdkfile', 'jdkFile:', "java-version: '21.0.12+101'", 'sha256sum --check --strict',
      'ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94',
      'OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz', 'verifyMaintenanceJava']) assert.ok(workflow.includes(text), text);
    assert.equal(workflow.includes("java-version: '21.0.12.1+1'"), false);
  }
});