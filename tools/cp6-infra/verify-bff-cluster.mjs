// Actual BFF/Java processes, dedicated real PG/Redis and controlled HTTP suppliers.
// CP6 connection settings come only from the strict PowerShell test-config parser.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { parseArgs } from 'node:util';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { createWriteStream, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { randomUUID, createHash } from 'node:crypto';
import { once } from 'node:events';

const { values: args } = parseArgs({ options: {
  java: { type: 'string' }, jar: { type: 'string' }, bff: { type: 'string' }, psql: { type: 'string' },
  'auth-only': { type: 'boolean', default: false },
} });
for (const key of ['java', 'jar', 'bff', 'psql']) assert.ok(args[key], `Missing --${key}`);
const env = process.env;
assert.equal(env.CP6_IT_ENABLE, 'true');
assert.equal(env.CP6_IT_PERMISSION, 'DISPOSABLE_CP6_TEST_ONLY');
const target = new URL(env.CP6_IT_PG_URL.replace(/^jdbc:/, ''));
assert.equal(target.protocol, 'postgresql:'); assert.equal(target.hostname, '127.0.0.1');
assert.equal(target.pathname, `/${env.CP6_IT_PG_DATABASE}`);
assert.ok(target.port && !target.search && !target.username && !target.password && !target.hash);
assert.equal(env.CP6_IT_REDIS_HOST, '127.0.0.1');
assert.notEqual(env.CP6_IT_REDIS_TLS, 'true');
const directory = await mkdtemp(path.join(tmpdir(), 'cp6-bff-cluster-'));
const schema = `cp6_${randomUUID().replaceAll('-', '')}`;
const osEnv = Object.fromEntries(Object.entries(env).filter(([key]) =>
  /^(PATH|SystemRoot|WINDIR|TEMP|TMP|ComSpec|PATHEXT|USERPROFILE|APPDATA|LOCALAPPDATA)$/i.test(key)));
const runFile = promisify(execFile);
async function sql(statement) {
  try {
    const result = await runFile(args.psql, ['-X', '-v', 'ON_ERROR_STOP=1', '-h', target.hostname,
      '-p', target.port, '-U', env.CP6_IT_PG_USER, '-d', env.CP6_IT_PG_DATABASE, '-Atqc', statement],
    { env: { ...osEnv, PGPASSWORD: env.CP6_IT_PG_PASSWORD, PGCONNECT_TIMEOUT: '5' }, timeout: 20_000, windowsHide: true });
    return result.stdout.trim();
  } catch (error) {
    await writeFile(path.join(directory, 'sql-error.log'), String(error.stderr || 'SQL execution failed'));
    throw new Error('Isolated SQL failed; see sql-error.log');
  }
}
async function freePort() {
  const server = createServer(); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port; await new Promise(resolve => server.close(resolve)); return port;
}
const children = [], servers = [], providerCalls = [], proxyCalls = [], phases = [];
let cookie = '', csrf = '', openDrop = false, applyDrop = false, ackDrop = false, failFormalGet = false;
function start(command, argv, childEnv, name) {
  const log = createWriteStream(path.join(directory, `${name}.log`));
  const cwd = path.join(directory, name); mkdirSync(cwd);
  const child = spawn(command, argv, { cwd, env: childEnv, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.pipe(log); child.stderr.pipe(log);
  const finished = once(child, 'exit').then(() => { log.end(); });
  children.push({ child, finished, name }); return child;
}
async function listen(server) {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); servers.push(server);
  return `http://127.0.0.1:${server.address().port}`;
}
async function ready(url, child) {
  const deadline = Date.now() + 90_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error('Owned application exited before readiness');
    try { if ((await fetch(url, { signal: AbortSignal.timeout(1000) })).ok) return; } catch {}
    await new Promise(resolve => setTimeout(resolve, 150));
  }
  throw new Error('Owned application readiness timed out');
}
function rememberCookies(response) {
  const entries = response.headers.getSetCookie().map(value => value.split(';')[0]);
  cookie = entries.join('; ');
  csrf = decodeURIComponent(entries.find(value => value.startsWith('__Host-yuyouzhice_csrf='))?.split('=').slice(1).join('=') || '');
}
async function http(index, method, route, body, options = {}) {
  const response = await fetch(bffUrls[index] + route, {
    method, headers: { 'content-type': 'application/json', origin: bffUrls[0],
      cookie: options.cookie ?? cookie, 'x-yuyouzhice-csrf': options.csrf ?? csrf,
      'x-device-id': 'isolated-cluster-device', ...(options.headers || {}) },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(60_000),
  });
  const text = await response.text();
  let data; try { data = JSON.parse(text); } catch { data = { text }; }
  return { status: response.status, data, response };
}
let bffUrls = [];
await sql(`CREATE SCHEMA ${schema}`);
let outcome = { status: 'failed', directory, schema };
try {
  const providerUrl = await listen(createServer(async (req, res) => {
    let raw = ''; for await (const chunk of req) raw += chunk;
    const url = new URL(req.url, 'http://fixture');
    if (url.pathname.includes('chat/completions')) {
      const request = JSON.parse(raw); providerCalls.push({ path: url.pathname, request });
      const content = '已收到本次行程信息。';
      if (request.stream) {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        res.write(`data: ${JSON.stringify({ id: randomUUID(), object: 'chat.completion.chunk', created: 1,
          model: 'fixture', choices: [{ index: 0, delta: { content }, finish_reason: null }] })}\n\n`);
        res.end('data: [DONE]\n\n');
      } else {
        res.setHeader('content-type', 'application/json'); res.end(JSON.stringify({ id: randomUUID(), object: 'chat.completion',
          choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
          usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }));
      }
      return;
    }
    providerCalls.push({ path: url.pathname });
    let body = { status: '1', infocode: '10000' };
    if (url.pathname.includes('/direction/')) body.route = { paths: [{ distance: '100', duration: '120', steps: [] }], transits: [] };
    else if (url.pathname.includes('/place/')) body.pois = [];
    else if (url.pathname.includes('/weather/')) body.lives = [];
    else { res.statusCode = 503; body = { error: 'No external supplier is enabled' }; }
    res.setHeader('content-type', 'application/json'); res.end(JSON.stringify(body));
  }));
  const javaUrls = [];
  const closed = await freePort();
  for (let index = 0; index < 2; index++) {
    const port = await freePort(); javaUrls.push(`http://127.0.0.1:${port}`);
    const child = start(args.java, ['-jar', path.resolve(args.jar), '--server.address=127.0.0.1', `--server.port=${port}`], {
      ...osEnv, JWT_SECRET: 'isolated-cluster-jwt-secret-0123456789-abcdef',
      DEEPSEEK_API_KEY: 'isolated-local-model-key', DEEPSEEK_BASE_URL: providerUrl,
      DASHSCOPE_API_KEY: '', DASHSCOPE_EMBEDDING_URL: `${providerUrl}/disabled-embedding`, BAILIAN_RERANK_URL: `${providerUrl}/disabled-rerank`,
      AMAP_WEB_SERVICE_KEY: 'isolated-local-map-key', AMAP_WEB_SERVICE_BASE_URL: providerUrl,
      PLANNER_LLM_INTENT_ENABLED: 'false', PLANNER_LLM_NARRATIVE_ENABLED: 'false',
      BAIDU_WEB_SEARCH_URL: `${providerUrl}/disabled-search`, BAIDU_API_KEY: '',
      QDRANT_HOST: '127.0.0.1', QDRANT_PORT: String(closed), QDRANT_USE_TLS: 'false',
      QDRANT_PRODUCTION_V2_AUTO_CREATE_INDEXES: 'false', APP_KNOWLEDGE_SYNC_ENABLED: 'false', ADMIN_BOOTSTRAP_MODE: 'disabled',
      KNOWLEDGE_DB_TYPE: 'postgresql', KNOWLEDGE_DB_URL: env.CP6_IT_PG_URL + `?currentSchema=${schema}`,
      KNOWLEDGE_DB_USERNAME: env.CP6_IT_PG_USER, KNOWLEDGE_DB_PASSWORD: env.CP6_IT_PG_PASSWORD,
      YUYOUZHICE_REDIS_HOST: '127.0.0.1', YUYOUZHICE_REDIS_PORT: env.CP6_IT_REDIS_PORT,
      YUYOUZHICE_REDIS_DATABASE: '15', YUYOUZHICE_REDIS_PASSWORD: env.CP6_IT_REDIS_PASSWORD || '', JWT_REVOCATION_REDIS_ENABLED: 'true',
    }, `java-${index}`);
    await ready(javaUrls[index] + '/actuator/health/readiness', child);
  }
  const proxies = [];
  for (let index = 0; index < 2; index++) {
    proxies.push(await listen(createServer(async (req, res) => {
      try {
        const chunks = []; for await (const chunk of req) chunks.push(chunk);
        const body = Buffer.concat(chunks);
        const headers = { ...req.headers }; delete headers.host; delete headers.connection; delete headers['content-length'];
        if (index === 0 && failFormalGet && req.method === 'GET' && /^\/ai\/trips\/[^/]+$/.test(req.url)) {
          proxyCalls.push({ index, path: req.url, method: req.method, status: 503, injectedFailure: 'formal-read-unavailable' });
          res.writeHead(503, { 'content-type': 'application/json' }); res.end('{"code":503,"message":"isolated formal-read failure"}'); return;
        }
        const upstream = await fetch(javaUrls[index] + req.url, { method: req.method, headers,
          body: body.length ? body : undefined, signal: AbortSignal.timeout(60_000) });
        const bytes = Buffer.from(await upstream.arrayBuffer());
        proxyCalls.push({ index, path: req.url, method: req.method, status: upstream.status,
          idempotencyKey: req.headers['idempotency-key'] || null });
        if (index === 0 && upstream.ok && ((openDrop && /\/trips\/[^/]+\/open$/.test(req.url))
            || (applyDrop && req.url.endsWith('/adjust/apply')) || (ackDrop && req.url.endsWith('/ack')))) {
          proxyCalls.at(-1).receiptDroppedAfterSuccess = true;
          res.destroy(); return;
        }
        res.writeHead(upstream.status, { 'content-type': upstream.headers.get('content-type') || 'application/json' }); res.end(bytes);
      } catch { if (!res.destroyed) { res.statusCode = 502; res.end('{"error":"isolated upstream unavailable"}'); } }
    })));
  }
  bffUrls = [`http://127.0.0.1:${await freePort()}`, `http://127.0.0.1:${await freePort()}`];
  assert.notEqual(bffUrls[0], bffUrls[1]);
  for (let index = 0; index < 2; index++) {
    const child = start(process.execPath, [path.join(path.resolve(args.bff), 'server/index.mjs')], {
      ...osEnv, NODE_ENV: 'production', PORT: new URL(bffUrls[index]).port, YUYOUZHICE_ENV_FILE_ENABLED: '0',
      CORE_BACKEND_URL: proxies[index], YUYOUZHICE_WEB_ORIGINS: bffUrls.join(','),
      YUYOUZHICE_AUTH_SECRET: 'isolated-shared-bff-secret-0123456789-abcdef', YUYOUZHICE_BFF_STATE_STORE: 'redis',
      YUYOUZHICE_REVOKE_STORE: 'redis', YUYOUZHICE_REDIS_HOST: '127.0.0.1',
      YUYOUZHICE_REDIS_PORT: env.CP6_IT_REDIS_PORT, YUYOUZHICE_REDIS_DATABASE: '15',
      YUYOUZHICE_REDIS_PASSWORD: env.CP6_IT_REDIS_PASSWORD || '',
      YUYOUZHICE_REDIS_KEY_PREFIX: `cp6:${schema}:`, YUYOUZHICE_DATA_FILE: path.join(directory, `bff-${index}.json`),
      YUYOUZHICE_LOG_FILE: path.join(directory, `bff-${index}-diagnostic.log`),
    }, `bff-${index}`);
    await ready(bffUrls[index] + '/api/health', child);
  }
  const login = await http(0, 'POST', '/api/auth/register', { email: `cluster-${randomUUID()}@fixture.invalid`, password: 'Isolated-password-012345!' });
  assert.equal(login.status, 201); assert.equal(login.data.ok, true); rememberCookies(login.response);
  assert.ok(cookie && csrf);
  const shared = await http(1, 'GET', '/api/auth/session');
  assert.equal(shared.data.authenticated, true, 'Original cookie must authenticate on the second BFF without logging in again');
  phases.push('same-cookie-cross-bff-auth');
  if (!args['auth-only']) {
    const trip = { id: `fixture-${randomUUID()}`, title: '隔离多实例行程', version: 1, city: '杭州',
      constraints: { cityName: '杭州', companions: '带父母', walkingTolerance: '少走路' },
      days: [{ day: 1, dayNumber: 1, stops: ['测试展馆甲', '测试公园乙', '测试建筑丙', '测试广场丁'].map((name, index) => ({
        id: `stop-${index}`, name, type: 'attraction', location: `${120.12 + index * .001},30.24`,
        time: `${String(9 + index * 2).padStart(2, '0')}:00`, duration: '60分钟', durationMinutes: 60, address: '隔离测试片区',
      })) }] };
    const saved = await http(0, 'POST', '/api/trips/save', { trip }); assert.equal(saved.status, 200);
    const formalId = saved.data.saved.id;
    failFormalGet = true;
    const failedRead = await http(0, 'POST', `/api/trips/${formalId}/open`, { openOperationKey: `read-failure-${randomUUID()}` });
    failFormalGet = false;
    assert.ok(failedRead.status >= 500);
    assert.equal(await sql(`SELECT COUNT(*) FROM ${schema}.planner_session`), '0', 'Known pending Open must be compensated after formal-read failure');
    assert.equal(await sql(`SELECT COUNT(*) FROM ${schema}.planner_plan_revision`), '0');
    phases.push('formal-read-failure-compensates-owned-pending-open');
    const ackOperation = `ack-loss-${randomUUID()}`;
    ackDrop = true;
    const lostAck = await http(0, 'POST', `/api/trips/${formalId}/open`, { openOperationKey: ackOperation });
    ackDrop = false;
    assert.ok(lostAck.status >= 500);
    assert.equal(await sql(`SELECT COUNT(*) FROM ${schema}.planner_session WHERE status='ACTIVE'`), '1', 'Lost ack receipt must not delete an activated session');
    const ackRecovered = await http(1, 'POST', `/api/trips/${formalId}/open`, { openOperationKey: ackOperation });
    assert.equal(ackRecovered.status, 200);
    assert.equal(await sql(`SELECT COUNT(*) FROM ${schema}.planner_session`), '1');
    phases.push('ack-lost-receipt-recovery-without-blind-compensation');
    const operationKey = `open-${randomUUID()}`;
    openDrop = true;
    const lost = await http(0, 'POST', `/api/trips/${formalId}/open`, { openOperationKey: operationKey }, { headers: { 'idempotency-key': operationKey } });
    openDrop = false;
    assert.ok(lost.status >= 500, 'Deliberately lost upstream Open receipt must not report success');
    const opened = await http(1, 'POST', `/api/trips/${formalId}/open`, { openOperationKey: operationKey }, { headers: { 'idempotency-key': operationKey } });
    assert.equal(opened.status, 200); assert.ok(opened.data.sessionId && opened.data.javaSessionId);
    assert.equal(await sql(`SELECT COUNT(*) FROM ${schema}.planner_session`), '2', 'One prior ack scenario plus this one Open operation must create exactly two sessions');
    assert.match(opened.data.javaSessionId, /^[A-Za-z0-9_-]+$/);
    const javaSessionId = opened.data.javaSessionId;
    assert.equal(await sql(`SELECT status FROM ${schema}.planner_session WHERE session_id='${javaSessionId}'`), 'ACTIVE', 'Successfully persisted Open must be acknowledged');
    phases.push('open-lost-receipt-cross-instance-recovery');
    const sessionId = opened.data.sessionId;
    assert.equal((await http(0, 'GET', `/api/planner/sessions/${sessionId}`)).status, 200);
    const anchor = `CLUSTER_ANCHOR_${randomUUID().replaceAll('-', '')}`;
    const chat = async (index, message) => {
      const result = await http(index, 'POST', '/api/chat/stream', { message, plannerSessionId: sessionId });
      assert.equal(result.status, 200);
      assert.ok(result.data.text?.includes('已收到本次行程信息。'), 'Chat must deliver the local supplier text, not merely an SSE error');
      return result;
    };
    await chat(0, `请记住本次出行代号${anchor}，同行老人不能走台阶。`);
    await chat(1, '继续按刚才的出行条件回答。');
    // The application also sends asynchronous non-streaming sentiment calls.
    // Context assertions must inspect the actual streaming chat request, not the last classifier call.
    const chatRequests = () => providerCalls.filter(call => call.request?.stream === true).map(call => call.request);
    const history = () => chatRequests().at(-1)?.messages.filter(message => message.role === 'user').map(message => message.content).join('\n') || '';
    assert.ok(history().includes(anchor) && history().includes('不能走台阶'), 'Second Java model request must contain earlier user history and physical restriction');
    phases.push('chat-server-history-cross-java');
    const beforePreview = await http(1, 'GET', `/api/planner/sessions/${sessionId}`);
    const preview = await http(0, 'POST', '/api/planner/conversation', { sessionId, message: '删除测试展馆甲',
      context: { activeDay: 1, selectedStopId: 'stop-0' }, baseRevision: 1 });
    assert.equal(preview.status, 200); assert.ok(preview.data.proposalId, 'Actual conversation must create a removable-stop proposal');
    const afterPreview = await http(1, 'GET', `/api/planner/sessions/${sessionId}`);
    assert.deepEqual(afterPreview.data.trip, beforePreview.data.trip, 'Preview must not mutate the authoritative itinerary');
    assert.deepEqual(preview.data.proposedTrip.days[0].stops.map(stop => stop.id), ['stop-1', 'stop-2', 'stop-3']);
    phases.push('preview-isolated-from-current-authoritative-trip');
    const applyBody = { sessionId, proposalId: preview.data.proposalId, baseRevision: 1,
      conversationEpoch: preview.data.conversationEpoch, idempotencyKey: `apply-${randomUUID()}` };
    applyDrop = true;
    const lostApply = await http(0, 'POST', '/api/planner/adjust/apply', applyBody);
    applyDrop = false; assert.ok(lostApply.status >= 500);
    const replay = await http(1, 'POST', '/api/planner/adjust/apply', applyBody);
    assert.equal(replay.status, 200, 'Second instance must recover committed apply receipt');
    assert.equal(replay.data.trip.version, 2);
    assert.equal(replay.data.trip.days[0].stops.length, 3);
    assert.equal(await sql(`SELECT current_version FROM ${schema}.planner_session WHERE session_id='${javaSessionId}'`), '2');
    assert.equal(await sql(`SELECT COUNT(*) FROM ${schema}.planner_plan_revision WHERE session_id='${javaSessionId}'`), '2');
    phases.push('apply-lost-receipt-cross-instance-exactly-once');
    const changedFingerprint = await http(1, 'POST', '/api/planner/adjust/apply', { ...applyBody, proposalId: 'different-proposal' });
    assert.equal(changedFingerprint.status, 409);
    phases.push('apply-key-fingerprint-conflict');
    await chat(0, '请按现在剩下的站点继续建议。');
    const currentModel = chatRequests().at(-1);
    // Untrusted stop facts are intentionally attached to the current USER prompt,
    // while the SYSTEM prompt contains policy and preference constraints only.
    const currentPrompt = currentModel.messages.filter(message => message.role === 'user').at(-1).content;
    assert.ok(currentPrompt.includes('测试公园乙'));
    assert.ok(!currentPrompt.includes('测试展馆甲'), 'Prewarmed BFF must refresh the authoritative itinerary before chat');
    phases.push('prewarmed-bff-refreshes-current-plan-context');
    const nextPreview = await http(1, 'POST', '/api/planner/conversation', { sessionId, message: '删除测试公园乙',
      context: { activeDay: 1, selectedStopId: 'stop-1' }, baseRevision: 2 });
    assert.equal(nextPreview.status, 200); assert.ok(nextPreview.data.proposalId);
    const concurrentBody = { sessionId, proposalId: nextPreview.data.proposalId, baseRevision: 2, idempotencyKey: `parallel-${randomUUID()}` };
    const simultaneous = await Promise.all([0, 1].map(index => http(index, 'POST', '/api/planner/adjust/apply', concurrentBody)));
    assert.ok(simultaneous.every(result => result.status === 200), 'Same-key concurrent confirmation must return the one committed receipt');
    assert.ok(simultaneous.every(result => result.data.trip.version === 3));
    assert.deepEqual(simultaneous[0].data.trip, simultaneous[1].data.trip);
    assert.deepEqual(simultaneous[0].data.trip.days[0].stops.map(stop => stop.id), ['stop-2', 'stop-3']);
    assert.equal(await sql(`SELECT current_version FROM ${schema}.planner_session WHERE session_id='${javaSessionId}'`), '3');
    assert.equal(await sql(`SELECT COUNT(*) FROM ${schema}.planner_plan_revision WHERE session_id='${javaSessionId}'`), '3');
    phases.push('simultaneous-cross-instance-confirm-one-revision');
    const cancelledPreview = await http(0, 'POST', '/api/planner/conversation', { sessionId, message: '删除测试建筑丙',
      context: { activeDay: 1, selectedStopId: 'stop-2' }, baseRevision: 3 });
    assert.ok(cancelledPreview.data.proposalId);
    const cancel = await http(1, 'POST', '/api/planner/conversation', { sessionId, message: '取消本次调整', baseRevision: 3 });
    assert.equal(cancel.status, 200);
    const stale = await http(0, 'POST', '/api/planner/adjust/apply', { sessionId, proposalId: cancelledPreview.data.proposalId,
      baseRevision: 3, idempotencyKey: `cancelled-${randomUUID()}` });
    assert.equal(stale.status, 409);
    assert.equal(await sql(`SELECT current_version FROM ${schema}.planner_session WHERE session_id='${javaSessionId}'`), '3');
    phases.push('cross-instance-cancel-rejects-stale-proposal');
    const unauthorized = await http(1, 'GET', `/api/planner/sessions/${sessionId}`, undefined, { cookie: '', csrf: '' });
    assert.ok([401, 403, 404].includes(unauthorized.status)); phases.push('anonymous-cannot-read-user-planner-session');
    const originalCookie = cookie, originalCsrf = csrf;
    const foreign = await http(1, 'POST', '/api/auth/register', { email: `foreign-${randomUUID()}@fixture.invalid`, password: 'Isolated-other-password-012345!' });
    assert.equal(foreign.status, 201); rememberCookies(foreign.response);
    const forbidden = await http(1, 'GET', `/api/planner/sessions/${sessionId}`);
    assert.ok([403, 404].includes(forbidden.status));
    phases.push('other-owner-cannot-read-user-planner-session');
    const foreignLogout = await http(0, 'POST', '/api/auth/logout', {}); assert.equal(foreignLogout.status, 200);
    assert.equal((await http(1, 'GET', '/api/auth/session')).data.authenticated, false);
    phases.push('cross-bff-logout-invalidates-original-cookie');
    cookie = originalCookie; csrf = originalCsrf;
    const bff0 = children.find(item => item.name === 'bff-0'); bff0.child.kill(); await bff0.finished;
    const authoritative = await http(1, 'GET', `/api/planner/sessions/${sessionId}`);
    assert.equal(authoritative.status, 200); assert.equal(authoritative.data.trip.version, 3);
    const savedAgain = await http(1, 'POST', '/api/trips/save', { trip: authoritative.data.trip,
      savedTripId: formalId, plannerSessionId: sessionId });
    assert.equal(savedAgain.status, 200);
    assert.equal(savedAgain.data.saved.trip.version, 2);
    assert.equal(await sql(`SELECT COUNT(*) FROM ${schema}.trip_version`), '2');
    const reopened = await http(1, 'POST', `/api/trips/${formalId}/open`, { openOperationKey: `reopen-${randomUUID()}` });
    assert.equal(reopened.status, 200); assert.notEqual(reopened.data.sessionId, sessionId);
    assert.notEqual(reopened.data.javaSessionId, javaSessionId);
    assert.equal(reopened.data.trip.version, 1);
    assert.equal(reopened.data.trip.sourceTripVersion, savedAgain.data.saved.trip.version);
    assert.deepEqual(reopened.data.trip.days[0].stops.map(stop => stop.id), ['stop-2', 'stop-3']);
    const continuing = await http(1, 'POST', '/api/chat/stream', { message: '重新打开后继续之前的出行条件。', plannerSessionId: reopened.data.sessionId });
    assert.equal(continuing.status, 200);
    assert.ok(continuing.data.text?.includes('已收到本次行程信息。'));
    assert.ok(history().includes(anchor) && history().includes('不能走台阶'));
    phases.push('bff-peer-kill-save-reopen-retains-server-chat-history');
    const logout = await http(1, 'POST', '/api/auth/logout', {}); assert.equal(logout.status, 200);
    // The peer process was stopped above; verify this original cookie is gone in the shared store.
    const revoked = await http(1, 'GET', '/api/auth/session'); assert.equal(revoked.data.authenticated, false);
  }
  outcome = { status: 'passed', directory, schema, phases, instances: { java: 2, bff: 2 },
    jarSha256: createHash('sha256').update(await readFile(args.jar)).digest('hex'),
    supplierMode: 'controlled local HTTP; not real model semantic quality', platform: 'Windows PG16.15/community Redis7.4.3' };
} catch (error) {
  outcome.error = String(error.message); outcome.phases = phases; throw error;
} finally {
  for (const { child } of children) if (child.exitCode === null) child.kill();
  await Promise.allSettled(children.map(item => item.finished));
  for (const server of servers) { server.closeAllConnections(); server.close(); }
  await writeFile(path.join(directory, 'provider-calls.json'), JSON.stringify(providerCalls, null, 2));
  await writeFile(path.join(directory, 'proxy-calls.json'), JSON.stringify(proxyCalls, null, 2));
  await sql(`DROP SCHEMA ${schema} CASCADE`);
  outcome.cleanup = { applicationProcessesStopped: true, schemaDropped: true, redisFlushed: false,
    redisScope: 'Dedicated disposable runtime; service shutdown is verified separately by the orchestrator' };
  await writeFile(path.join(directory, 'result-summary.json'), JSON.stringify(outcome, null, 2));
  console.log(JSON.stringify(outcome));
}
