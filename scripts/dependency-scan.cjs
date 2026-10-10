'use strict';

const fs = require('node:fs/promises');
const OSV = 'https://api.osv.dev/v1/querybatch';

function parseBatch(body, packages) {
  if (!body || !Array.isArray(body.results) || body.results.length !== packages.length) throw new Error('Incomplete OSV response');
  return body.results.flatMap((result, i) => {
    if (!result || result.next_page_token || (result.vulns !== undefined && !Array.isArray(result.vulns))) throw new Error('Incomplete/paginated OSV response');
    return (result.vulns || []).map(v => {
      if (typeof v.id !== 'string' || !/^[A-Za-z0-9-]{1,160}$/.test(v.id)) throw new Error('Invalid advisory');
      return { package: packages[i].name, version: packages[i].version, advisory: v.id };
    });
  });
}

async function request(packages, fetcher) {
  const body = JSON.stringify({ queries: packages.map(p => ({ package: { ecosystem: 'Maven', name: p.name }, version: p.version })) });
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const response = await fetcher(OSV, { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body, redirect: 'error', signal: AbortSignal.timeout(15000) });
      if (!response.ok) throw new Error('OSV unavailable');
      // Stream with a byte bound; never trust content-length or parse an unbounded response.
      const chunks = []; let bytes = 0;
      for await (const chunk of response.body) {
        bytes += chunk.length;
        if (bytes > 4 * 1024 * 1024) throw new Error('OSV response exceeds limit');
        chunks.push(Buffer.from(chunk));
      }
      return parseBatch(JSON.parse(Buffer.concat(chunks).toString('utf8')), packages);
    } catch (error) {
      if (attempt === 2) throw new Error('Dependency scan incomplete', { cause: error });
    }
  }
}

async function scan(packages, fetcher = fetch) {
  if (!Array.isArray(packages) || packages.length === 0 || packages.length > 2000) throw new Error('Invalid dependency inventory');
  for (const p of packages) if (!p || !/^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+$/.test(p.name) || typeof p.version !== 'string' || p.version.length > 120) throw new Error('Invalid Maven coordinate');
  const findings = [];
  for (let i = 0; i < packages.length; i += 50) findings.push(...await request(packages.slice(i, i + 50), fetcher));
  return { outcome: 'COMPLETE', timestamp: new Date().toISOString(), packages: packages.length, findings,
    limits: 'OSV Maven database only; no reachability, JDK/native/ImageMagick/CDN or zero-vulnerability guarantee' };
}

async function main() {
  const [input, output] = process.argv.slice(2);
  if (!input || !output) throw new Error('Inventory/report paths required');
  let report;
  try { report = await scan(JSON.parse(await fs.readFile(input, 'utf8'))); }
  catch { report = { outcome: 'INCOMPLETE', findings: [], timestamp: new Date().toISOString() }; }
  await fs.writeFile(output, JSON.stringify(report, null, 2));
  console.log(`OSV ${report.outcome}; packages=${report.packages || 0}; findings=${report.findings.length}`);
  if (report.outcome !== 'COMPLETE' || report.findings.length) process.exitCode = 1;
}

module.exports = { parseBatch, scan };
if (require.main === module) main().catch(() => { console.error('Dependency scan failed'); process.exitCode = 1; });