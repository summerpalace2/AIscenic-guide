// Build an immutable release manifest for a complete, staged external city catalog.
// Java validates these exact file bytes again inside its database install transaction.
import { createHash, randomUUID } from 'node:crypto';
import { link, lstat, readFile, readdir, unlink, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';

const args = process.argv.slice(2);
const value = name => {
  const at = args.indexOf(name);
  return at >= 0 ? args[at + 1] : undefined;
};
const directoryArg = value('--directory');
const versionArg = value('--version');
if (!directoryArg || !/^[1-9][0-9]*$/.test(versionArg || '')) {
  throw new Error('Usage: node publish-city-catalog.mjs --directory <complete-staged-catalog> --version <positive-integer>');
}
const version = Number(versionArg);
if (!Number.isSafeInteger(version)) throw new Error('Release version exceeds JavaScript safe integer range.');
const directory = resolve(directoryArg);
if (!(await lstat(directory)).isDirectory()) throw new Error('Catalog path must be a directory.');
const manifestPath = join(directory, 'catalog-release.json');
try { await lstat(manifestPath); throw new Error('Release manifest already exists; publish from a fresh staged directory.'); }
catch (error) { if (error.code !== 'ENOENT') throw error; }
const names = (await readdir(directory, { withFileTypes: true }))
  .filter(entry => entry.name.endsWith('.json') && entry.name !== 'catalog-release.json')
  .map(entry => {
    if (!entry.isFile() || !/^[A-Za-z0-9._-]+\.json$/.test(entry.name))
      throw new Error(`Invalid city file: ${entry.name}`);
    return entry.name;
  }).sort();
if (!names.length) throw new Error('A versioned release must contain at least one city JSON file.');
const files = {};
const ids = new Set();
for (const name of names) {
  const bytes = await readFile(join(directory, name));
  const rows = JSON.parse(bytes.toString('utf8'));
  if (!Array.isArray(rows) || !rows.length) throw new Error(`${name} must contain a nonempty city array.`);
  const cities = new Set(rows.map(row => String(row?.city || '').replace(/市$/, '')));
  if (cities.size !== 1 || cities.has('')) throw new Error(`${name} contains mixed or missing cities.`);
  for (const row of rows) {
    const id = String(row?.id || '');
    if (!id || ids.has(id)) throw new Error(`Missing or duplicate attraction ID in ${name}.`);
    ids.add(id);
  }
  files[name] = createHash('sha256').update(bytes).digest('hex');
}
const manifest = JSON.stringify({ version, files }, null, 2) + '\n';
const temporary = join(directory, `.catalog-release-${randomUUID()}.tmp`);
try {
  await writeFile(temporary, manifest, { flag: 'wx' });
  // Hard-link creation fails atomically if another publisher already created the manifest.
  await link(temporary, manifestPath);
} finally {
  await unlink(temporary).catch(() => {});
}
console.log(JSON.stringify({ published: manifestPath, version, files: names, attractionCount: ids.size }, null, 2));
