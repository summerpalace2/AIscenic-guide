// Actual BFF entrypoint with disposable config/data and no external upstream.
import assert from 'node:assert/strict';
import { createServer } from 'node:net';
import { spawn } from 'node:child_process';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { once } from 'node:events';

if (!process.argv[2]) throw new Error('Provide the BFF repository directory.');
const bff = path.resolve(process.argv[2]);
const directory = await mkdtemp(path.join(tmpdir(), 'BFF 配置核验-'));
async function freePort() {
  const server = createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
const port = await freePort();
let filePort = await freePort();
while (filePort === port) filePort = await freePort();
await writeFile(path.join(directory, '.env'), [
  `PORT=${filePort}`, 'CORE_BACKEND_URL=http://127.0.0.1:1',
  'YUYOUZHICE_AUTH_SECRET=isolated-config-startup-secret-0123456789',
  'YUYOUZHICE_REVOKE_STORE=memory', 'YUYOUZHICE_MEMORY=1',
  `YUYOUZHICE_DATA_FILE=${path.join(directory, 'state.json')}`,
  `YUYOUZHICE_LOG_FILE=${path.join(directory, 'bff.log')}`,
  'AMAP_WEB_JS_KEY=isolated-public-map-fixture',
  'AMAP_WEB_SERVICE_KEY=private-map-fixture', 'DEEPSEEK_API_KEY=private-model-fixture',
].join('\n'), 'utf8');
const osEnv = Object.fromEntries(Object.entries(process.env).filter(([name]) =>
  /^(PATH|SystemRoot|WINDIR|TEMP|TMP|ComSpec|PATHEXT|USERPROFILE|APPDATA|LOCALAPPDATA)$/i.test(name)));
const child = spawn(process.execPath, [path.join(bff, 'server/index.mjs')], {
  cwd: directory, windowsHide: true,
  env: { ...osEnv, PORT: String(port), NODE_ENV: 'production', SCENIC_GUIDE_JAVA_DIR: directory },
  stdio: ['ignore', 'pipe', 'pipe'],
});
const exited = once(child, 'exit');
let log = '';
child.stdout.on('data', data => { log += data; });
child.stderr.on('data', data => { log += data; });
try {
  let health;
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`BFF exited before readiness: ${child.exitCode}`);
    try {
      const response = await fetch(`http://127.0.0.1:${port}/api/health`, { signal: AbortSignal.timeout(500) });
      if (response.ok) { health = await response.json(); break; }
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.equal(health?.ok, true, 'Actual BFF must become ready on the process-selected port');
  const response = await fetch(`http://127.0.0.1:${port}/api/map-config`);
  assert.equal(response.status, 200);
  const map = await response.json();
  assert.equal(map.map.key, 'isolated-public-map-fixture', 'Actual entrypoint must load the selected fixture file');
  assert.ok(!JSON.stringify({ health, map }).includes('private-map-fixture'));
  assert.ok(!JSON.stringify({ health, map }).includes('private-model-fixture'));
  const result = { ready: true, processPortOverridesFile: port !== filePort,
    selectedFilePublicMapLoaded: true, privateProviderValuesNotExposed: true,
    externalUpstream: false, directory };
  await writeFile(path.join(directory, 'result-summary.json'), JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result));
} finally {
  if (child.exitCode === null) child.kill();
  await exited;
  await writeFile(path.join(directory, 'startup.log'), log);
}
