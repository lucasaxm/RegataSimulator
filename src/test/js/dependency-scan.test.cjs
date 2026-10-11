'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { parseBatch, scan } = require('../../../scripts/dependency-scan.cjs');
const packages = [{ name: 'org.synthetic:fixture', version: '1.0' }];

test('maps advisories to exact resolved coordinates', () => {
  assert.deepEqual(parseBatch({ results: [{ vulns: [{ id: 'GHSA-test-1234' }] }] }, packages),
    [{ package: 'org.synthetic:fixture', version: '1.0', advisory: 'GHSA-test-1234' }]);
});
test('missing/paginated/malformed responses fail rather than pass clean', () => {
  for (const body of [{}, { results: [] }, { results: [{ next_page_token: 'more' }] }, { results: [{ vulns: 'bad' }] }])
    assert.throws(() => parseBatch(body, packages));
});
test('public API request is fixed, bounded, no secrets, and uses unversioned coordinate plus version', async () => {
  let calls = 0;
  const result = await scan(packages, async (url, options) => {
    calls++; assert.equal(url, 'https://api.osv.dev/v1/querybatch'); assert.equal(options.redirect, 'error');
    assert.ok(options.signal); assert.deepEqual(JSON.parse(options.body).queries,
      [{ package: { ecosystem: 'Maven', name: 'org.synthetic:fixture' }, version: '1.0' }]);
    return new Response(JSON.stringify({ results: [{}] }), { status: 200 });
  });
  assert.equal(calls, 1); assert.equal(result.outcome, 'COMPLETE'); assert.deepEqual(result.findings, []);
});
test('network failure is bounded to three attempts and never reported clean', async () => {
  let calls = 0;
  await assert.rejects(scan(packages, async () => { calls++; throw new Error('synthetic timeout'); }));
  assert.equal(calls, 3);
});
test('invalid inventories cannot reach the API', async () => {
  let calls = 0;
  await assert.rejects(scan([{ name: 'https://attacker/', version: '1' }], async () => { calls++; }));
  assert.equal(calls, 0);
});