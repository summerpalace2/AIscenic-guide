import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

test('release tool hashes staged bytes and refuses to replace an existing manifest', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'city-release-tool-'));
  try {
    const city = '[{"id":"city-one","city":"测试城","name":"测试景点"}]\n';
    await writeFile(join(directory, 'city.json'), city);
    const run = version => spawnSync(process.execPath,
      [new URL('./publish-city-catalog.mjs', import.meta.url).pathname.replace(/^\/(?=[A-Za-z]:)/, ''),
        '--directory', directory, '--version', String(version)], { encoding: 'utf8' });
    assert.equal(run(1).status, 0);
    const release = JSON.parse(await readFile(join(directory, 'catalog-release.json'), 'utf8'));
    assert.equal(release.version, 1);
    assert.equal(release.files['city.json'], createHash('sha256').update(city).digest('hex'));
    assert.notEqual(run(2).status, 0);
    assert.equal(JSON.parse(await readFile(join(directory, 'catalog-release.json'), 'utf8')).version, 1);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
