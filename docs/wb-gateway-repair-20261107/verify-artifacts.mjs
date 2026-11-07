import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const manifestPath = path.join(root, 'artifact-manifest.json');
const output = process.argv[2];
if (!output) throw new Error('Provide a new evidence output filename');
const hash = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
function inspect(filename) {
  const before = fs.lstatSync(filename);
  if (!before.isFile() || before.isSymbolicLink()) throw new Error('Expected a regular file: ' + filename);
  const bytes = fs.readFileSync(filename);
  const after = fs.lstatSync(filename);
  if (before.size !== after.size || before.mtimeMs !== after.mtimeMs || before.ino !== after.ino) {
    throw new Error('File changed during verification: ' + filename);
  }
  return { path: filename, sha256: hash(bytes), bytes: bytes.length };
}
const contractBytes = fs.readFileSync(manifestPath);
const manifest = JSON.parse(contractBytes.toString('utf8'));
const acceptance = inspect(manifest.acceptance.path);
const rows = manifest.artifacts.map((item) => {
  const target = inspect(item.target);
  const source = item.source ? inspect(item.source) : null;
  const backup = item.backup ? inspect(item.backup) : null;
  return {
    target, source, backup,
    targetMatches: target.sha256 === item.approvedSha256,
    sourceMatches: source === null || source.sha256 === item.approvedSha256,
    backupMatches: backup === null || backup.sha256 === item.originalSha256,
  };
});
const report = {
  checkedAt: new Date().toISOString(),
  manifestSha256: hash(contractBytes),
  acceptance,
  acceptanceUnchanged: acceptance.sha256 === manifest.acceptance.sha256,
  artifacts: rows,
  liveInferenceRequests: 0,
  mutations: 'Evidence output only',
  ok: acceptance.sha256 === manifest.acceptance.sha256 && rows.every((item) => item.targetMatches && item.sourceMatches && item.backupMatches),
};
fs.writeFileSync(output, JSON.stringify(report, null, 2) + '\n', { flag: 'wx', encoding: 'utf8' });
console.log(JSON.stringify({ ok: report.ok, artifacts: rows.length, acceptanceUnchanged: report.acceptanceUnchanged, evidence: path.resolve(output) }));
process.exitCode = report.ok ? 0 : 1;
