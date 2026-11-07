'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const http = require('node:http');
const net = require('node:net');
const { fork } = require('node:child_process');
const { setTimeout: delay } = require('node:timers/promises');
const { once } = require('node:events');

const candidate = path.resolve(__dirname, '../wb-router.js');
const spec = path.resolve(__dirname, '../../acceptance-spec.md');
const specHash = 'c2a33fc5c61973cf58ea892fd7472a142ed4ef222d5eeb63e497b4b36e90fe91';
const reserved = new Set([8788, 8789, 8790, 6199]);
const splitPaths = ['/v1/chat/completions', '/v1/responses', '/v1/images/generations',
  '/v1/images/edits', '/v1/embeddings', '/v1/messages', '/messages', '/v1/search',
  '/v1/fetch', '/v2/chat/busy'];

async function verifySpec() {
  assert.equal(crypto.createHash('sha256').update(await fs.readFile(spec)).digest('hex'), specHash);
}

async function until(check, message, timeout = 3000) {
  const end = Date.now() + timeout;
  let value;
  do {
    value = await check();
    if (value) return value;
    await delay(10);
  } while (Date.now() < end);
  assert.fail(message);
}

async function listen(server) {
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const port = server.address().port;
  assert.ok(!reserved.has(port), 'test listener must not use real WB/QQ ports');
  return port;
}

async function pool(name) {
  const p = { name, calls: [], health: { status: 200, body: { service: 'workbuddy2api', healthy: 7 } },
    probes: 0, probeActive: 0, probeMax: 0, probeStarts: [], cancelled: 0, behaviors: {},
    sockets: new Set(), pending: new Set(), firstUpload: null };
  function later(res, fn, ms) {
    const timer = setTimeout(() => { p.pending.delete(timer); if (!res.destroyed) fn(); }, ms);
    p.pending.add(timer);
    res.once('close', () => { clearTimeout(timer); p.pending.delete(timer); });
  }
  p.server = http.createServer((req, res) => {
    const pathname = req.url.split('?')[0];
    if (pathname === '/healthz' && !req.headers['x-wb-router']) {
      ++p.probes;
      p.probeStarts.push(Date.now());
      p.probeMax = Math.max(p.probeMax, ++p.probeActive);
      res.once('close', () => { --p.probeActive; });
      const health = { ...p.health };
      if (health.hang) return;
      const respond = () => {
        const text = typeof health.body === 'string' ? health.body : JSON.stringify(health.body);
        res.writeHead(health.status, { 'content-type': 'application/json' });
        if (health.drip) {
          res.write(' ');
          const drip = setInterval(() => { if (!res.destroyed) res.write(' '); }, 15);
          res.once('close', () => clearInterval(drip));
        } else res.end(text);
      };
      if (health.delay) later(res, respond, health.delay); else respond();
      return;
    }
    const call = { url: req.url, method: req.method, headers: req.headers, body: [], startedAt: Date.now() };
    p.calls.push(call);
    req.on('data', (chunk) => {
      call.body.push(chunk);
      if (p.firstUpload) { p.firstUpload(); p.firstUpload = null; }
    });
    res.once('close', () => { if (!res.writableFinished) ++p.cancelled; });
    req.on('end', () => {
      call.body = Buffer.concat(call.body).toString('utf8');
      const mode = p.behaviors[req.headers['x-test-case']] || 'ok';
      if (mode === 'reset') return req.socket.destroy();
      if (mode === 'hang') return;
      if (mode === 'active-sse') {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        res.write('data: 0\n\n');
        for (let i = 1; i <= 7; ++i) later(res, () => res.write('data: ' + i + '\n\n'), i * 60);
        later(res, () => res.end('data: [DONE]\n\n'), 480);
        return;
      }
      if (mode === 'sse' || mode === 'cancel') {
        res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' });
        res.write('data: {"text":"first');
        later(res, () => res.write(' chunk"}\n\n'), 30);
        later(res, () => res.end('data: {"text":"尾包"}\n\ndata: [DONE]\n\n'), 400);
        return;
      }
      if (mode === 'partial-reset') {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        res.write('data: first\n\n');
        later(res, () => req.socket.destroy(), 30);
        return;
      }
      const body = JSON.stringify({ pool: name, service: 'workbuddy2api', healthy: 7, body: call.body });
      res.writeHead(typeof mode === 'number' ? mode : 200, { 'content-type': 'application/json',
        'content-length': Buffer.byteLength(body) });
      res.end(body);
    });
  });
  p.server.on('connection', (socket) => {
    p.sockets.add(socket);
    socket.once('close', () => p.sockets.delete(socket));
  });
  p.port = await listen(p.server);
  p.close = async () => {
    for (const timer of p.pending) clearTimeout(timer);
    p.pending.clear();
    const closed = new Promise((resolve) => p.server.close(resolve));
    for (const socket of p.sockets) socket.destroy();
    await closed;
  };
  return p;
}

function request(port, pathname, options = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, path: pathname, agent: false,
      method: options.method || 'GET', headers: options.headers || {} }, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.once('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        let json;
        try { json = JSON.parse(text); } catch (_) {}
        resolve({ status: res.statusCode, text, json });
      });
      res.once('error', reject);
    });
    req.setTimeout(3000, () => req.destroy(new Error('test client timed out')));
    req.once('error', reject);
    req.end(options.body);
  });
}

async function fixture(t, options = {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'wb-router-isolated-'));
  let main;
  let aux;
  let qq;
  let child;
  let exit;
  let stopped = false;
  let logs = '';
  async function stop() {
    if (!child || stopped) return;
    stopped = true;
    if (child.exitCode === null && child.signalCode === null && child.connected) child.send({ type: 'stop' });
    const timer = setTimeout(() => child.kill(), 2000);
    try {
      const [code, signal] = await exit;
      assert.equal(signal, null, logs);
      assert.equal(code, 0, logs);
    } finally { clearTimeout(timer); }
  }
  t.after(async () => {
    try { await stop(); }
    finally {
      await Promise.all([main && main.close(), aux && aux.close(),
        qq && new Promise((resolve) => qq.close(resolve))]);
      const absolute = path.resolve(dir);
      assert.equal(path.dirname(absolute), path.resolve(os.tmpdir()));
      assert.ok(path.basename(absolute).startsWith('wb-router-isolated-'));
      await fs.rm(absolute, { recursive: true, force: true });
      await verifySpec();
    }
  });
  main = await pool('main');
  aux = await pool('aux');
  qq = net.createServer();
  const qqPort = await listen(qq);
  if (options.mainHealth) Object.assign(main.health, options.mainHealth);
  if (options.auxHealth) Object.assign(aux.health, options.auxHealth);
  const config = {
    listen: 0,
    main: { host: '127.0.0.1', port: main.port, key: 'test-main-key' },
    aux: { host: '127.0.0.1', port: aux.port, key: 'test-aux-key' },
    qqPort, probeMs: 20, onlineHoldMs: 60, offlineHoldMs: 80,
    auxHealthMs: 40, healthTimeoutMs: 120, healthMaxBytes: 1024,
    healthStaleMs: 500, requestTimeoutMs: 2000,
    ...options.config,
  };
  await fs.copyFile(candidate, path.join(dir, 'wb-router.js'));
  await fs.writeFile(path.join(dir, 'router.config.json'), JSON.stringify(config));
  if (options.saved !== false) {
    await fs.writeFile(path.join(dir, 'router-state.json'), JSON.stringify({
      state: options.state || 'offline', changedAt: new Date().toISOString(), raw: false }));
  }
  child = fork(path.join(__dirname, 'isolated-router-child.cjs'), [path.join(dir, 'wb-router.js')],
    { cwd: dir, stdio: ['ignore', 'pipe', 'pipe', 'ipc'] });
  child.stdout.on('data', (chunk) => { logs += chunk; });
  child.stderr.on('data', (chunk) => { logs += chunk; });
  exit = once(child, 'exit');
  let port;
  let listening = false;
  const ready = new Promise((resolve, reject) => {
    child.on('message', (message) => {
      if (message.type === 'listening') {
        port = message.port;
        assert.ok(!reserved.has(port));
        listening = true;
        resolve(port);
      }
    });
    child.once('exit', (code) => { if (!listening) reject(new Error('child exited before listen: ' + code + ' ' + logs)); });
  });
  // Tests that deliberately stop during initial probing still observe rejection.
  ready.catch(() => {});
  if (!options.pending) await ready;
  return {
    main, aux, child, dir, ready, stop,
    get port() { return port; },
    get listening() { return listening; },
    get logs() { return logs; },
    status: async () => (await request(port, '/_router/status')).json,
    qq: (online) => child.send({ type: 'qq', online }),
    post: (pathname = '/v1/chat/completions', headers = {}, body = '{}') =>
      request(port, pathname, { method: 'POST', headers, body }),
  };
}

// Run serially so cleanup and output remain attributable; every listener uses port 0.
test('frozen acceptance specification matches before tests', verifySpec);

test('startup probes both pools concurrently before listening; shutdown cancels pending probes', async (t) => {
  const f = await fixture(t, { pending: true, mainHealth: { hang: true }, auxHealth: { hang: true },
    config: { healthTimeoutMs: 1000 } });
  await until(() => f.main.probes && f.aux.probes, 'both probes must start before either completes');
  assert.equal(f.listening, false);
  assert.ok(Math.abs(f.main.probeStarts[0] - f.aux.probeStarts[0]) < 100);
  await f.stop();
  assert.equal(f.listening, false);
});

test('offline both healthy: exact 5:3, all split endpoints, body and aux key rewriting', async (t) => {
  const f = await fixture(t);
  const count = { main: 0, aux: 0 };
  for (let i = 0; i < 80; ++i) {
    const reply = await f.post(splitPaths[i % splitPaths.length],
      { authorization: 'Bearer caller-key', 'x-api-key': 'caller-anthropic-key' }, 'fake-body-' + i);
    assert.equal(reply.status, 200);
    ++count[reply.json.pool];
    assert.equal(reply.json.body, 'fake-body-' + i);
  }
  assert.deepEqual(count, { main: 50, aux: 30 });
  for (const call of f.aux.calls) {
    assert.equal(call.headers.authorization, 'Bearer test-aux-key');
    assert.equal(call.headers['x-api-key'], undefined);
  }
  for (const call of f.main.calls) assert.equal(call.headers.authorization, 'Bearer caller-key');
  const status = await f.status();
  for (const name of ['main', 'aux']) {
    assert.equal(status[name].healthy, true);
    assert.equal(status[name].service, 'workbuddy2api');
    assert.equal(status[name].reason, 'ok');
    assert.ok(status[name].checkedAt && status[name].expiresAt);
    assert.ok(status[name].ttlMs > 0 && status[name].remainingTtlMs > 0);
  }
});

test('main-only panel/status/health/models always main; unhealthy main gives 503 and split uses healthy aux', async (t) => {
  const f = await fixture(t);
  for (const pathname of ['/panel', '/panel/', '/panel/api/status', '/status', '/healthz', '/v1/models']) {
    const reply = await request(f.port, pathname);
    assert.equal(reply.status, 200);
    assert.equal(reply.json.pool, 'main');
  }
  f.main.health.body = { service: 'foreign-service', healthy: 7 };
  await until(async () => !(await f.status()).main.healthy, 'main should lose health');
  const before = f.aux.calls.length;
  for (const pathname of ['/panel', '/panel/api/status', '/status', '/healthz', '/v1/models']) {
    const reply = await request(f.port, pathname);
    assert.equal(reply.status, 503);
    assert.match(reply.json.error.message, /main_only/);
  }
  assert.equal(f.aux.calls.length, before);
  for (const pathname of splitPaths) {
    const reply = await f.post(pathname);
    assert.equal(reply.status, 200);
    assert.equal(reply.json.pool, 'aux');
  }
  f.aux.health.body = { service: 'workbuddy2api', healthy: 0 };
  await until(async () => !(await f.status()).aux.healthy, 'aux should lose health');
  assert.equal((await f.post()).status, 503);
});

test('health rejects fake 200, 404, malformed JSON, invalid healthy and oversized bodies for each pool', async (t) => {
  const f = await fixture(t);
  const invalid = [
    [{ status: 200, body: 'just an arbitrary 200' }, 'invalid_json'],
    [{ status: 200, body: { service: 'fake', healthy: 7 } }, 'invalid_service'],
    [{ status: 404, body: { service: 'workbuddy2api', healthy: 7 } }, 'http_status_404'],
    [{ status: 200, body: { service: 'workbuddy2api', healthy: 0 } }, 'invalid_healthy'],
    [{ status: 200, body: { service: 'workbuddy2api', healthy: '7' } }, 'invalid_healthy'],
    [{ status: 200, body: { service: 'workbuddy2api', healthy: 1.5 } }, 'invalid_healthy'],
    [{ status: 200, body: { service: 'workbuddy2api', healthy: -1 } }, 'invalid_healthy'],
    [{ status: 200, body: { service: 'workbuddy2api', healthy: true } }, 'invalid_healthy'],
    [{ status: 200, body: 'x'.repeat(2048) }, 'body_too_large'],
  ];
  for (const name of ['main', 'aux']) {
    for (const [health, reason] of invalid) {
      Object.assign(f[name].health, health);
      await until(async () => (await f.status())[name].reason === reason, name + ' must report ' + reason);
      const status = await f.status();
      assert.equal(status[name].healthy, false);
      const reply = await f.post();
      assert.equal(reply.status, 200);
      assert.equal(reply.json.pool, name === 'main' ? 'aux' : 'main');
    }
    f[name].health = { status: 200, body: { service: 'workbuddy2api', healthy: 7 } };
    await until(async () => (await f.status())[name].healthy, name + ' should recover');
  }
});

test('QQ online hysteresis and auxPauseUntil forbid auxiliary borrowing; safe first startup is online', async (t) => {
  const f = await fixture(t);
  f.qq(true);
  await until(async () => (await f.status()).state === 'online', 'QQ must reach online after hold');
  for (let i = 0; i < 10; ++i) assert.equal((await f.post()).json.pool, 'main');
  f.main.health.body = { service: 'workbuddy2api', healthy: 0 };
  await until(async () => !(await f.status()).main.healthy, 'main must be unhealthy');
  const auxBefore = f.aux.calls.length;
  const reply = await f.post();
  assert.equal(reply.status, 503);
  assert.match(reply.json.error.message, /qq_online/);
  assert.equal(f.aux.calls.length, auxBefore);
  f.qq(false);
  await until(async () => (await f.status()).state === 'offline', 'QQ must reach offline after hold');
  assert.equal((await f.post()).json.pool, 'aux');
  const paused = await fixture(t, { config: { auxPauseUntil: new Date(Date.now() + 60000).toISOString() },
    mainHealth: { body: { service: 'fake', healthy: 7 } } });
  assert.equal((await paused.status()).aux.paused, true);
  assert.equal((await paused.post()).status, 503);
  assert.equal(paused.aux.calls.length, 0);
  const safe = await fixture(t, { saved: false, config: { offlineHoldMs: 60000 },
    mainHealth: { body: { service: 'fake', healthy: 7 } } });
  assert.equal((await safe.status()).state, 'online');
  assert.equal((await safe.post()).status, 503);
  assert.equal(safe.aux.calls.length, 0);
});

test('health deadline defeats drip responses; TTL rejects old success and single flight prevents overlap', async (t) => {
  const f = await fixture(t, { config: { auxHealthMs: 30, healthTimeoutMs: 350, healthStaleMs: 90 } });
  f.main.health = { status: 200, body: {}, drip: true };
  f.aux.health = { status: 200, body: {}, drip: true };
  await until(async () => {
    const status = await f.status();
    return status.main.reason === 'stale' && status.aux.reason === 'stale' && status.main.probing && status.aux.probing;
  }, 'both successful health results must expire during slow probes');
  assert.equal((await f.post()).status, 503);
  await until(async () => {
    const status = await f.status();
    return status.main.reason === 'timeout' && status.aux.reason === 'timeout';
  }, 'wall-clock timeout must stop indefinitely dripping probes');
  assert.equal(f.main.probeMax, 1);
  assert.equal(f.aux.probeMax, 1);
  f.main.health = { status: 200, body: { service: 'workbuddy2api', healthy: 7 }, delay: 600 };
  await delay(400);
  f.main.health = { status: 200, body: { service: 'fake', healthy: 7 } };
  await until(async () => (await f.status()).main.reason === 'invalid_service', 'new failed probe must win');
  await delay(650);
  assert.equal((await f.status()).main.healthy, false);
});

test('404/429/socket reset, including empty POST, are never replayed across pools', async (t) => {
  const f = await fixture(t);
  let id = 0;
  for (const mode of [404, 429, 'reset']) {
    for (const body of ['', 'fake-request-body']) {
      for (let repeat = 0; repeat < 4; ++repeat) {
        const tag = 'no-replay-' + ++id;
        f.main.behaviors[tag] = mode;
        f.aux.behaviors[tag] = mode;
        const reply = await f.post('/v1/chat/completions', { 'x-test-case': tag }, body);
        assert.equal(reply.status, mode === 'reset' ? 502 : mode);
        const calls = [...f.main.calls, ...f.aux.calls].filter((call) => call.headers['x-test-case'] === tag);
        assert.equal(calls.length, 1, tag + ' must dispatch once');
        assert.equal(calls[0].body, body);
      }
    }
  }
  assert.ok(f.main.calls.length > 0 && f.aux.calls.length > 0);
});

test('request body streams before end; normal request close preserves direct SSE chunks and UTF-8', async (t) => {
  const f = await fixture(t, { state: 'online' });
  const firstUpload = new Promise((resolve) => { f.main.firstUpload = resolve; });
  const upload = http.request({ host: '127.0.0.1', port: f.port, path: '/v1/responses', method: 'POST', agent: false });
  const uploaded = new Promise((resolve, reject) => {
    upload.once('response', (res) => { res.resume(); res.once('end', resolve); });
    upload.once('error', reject);
  });
  upload.write('first-');
  await Promise.race([firstUpload, delay(1000).then(() => assert.fail('upstream must see body before client end'))]);
  upload.end('last');
  await uploaded;
  assert.equal(f.main.calls[0].body, 'first-last');
  f.main.behaviors.stream = 'sse';
  const started = Date.now();
  const chunks = [];
  let firstAt;
  await new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port: f.port, path: '/v1/responses', method: 'POST', agent: false,
      headers: { 'x-test-case': 'stream' } }, (res) => {
      res.on('data', (chunk) => { if (!firstAt) firstAt = Date.now(); chunks.push(chunk); });
      res.once('end', resolve);
      res.once('error', reject);
    });
    req.once('error', reject);
    req.end('{}');
  });
  assert.ok(firstAt - started < 250, 'first SSE bytes must arrive before delayed tail');
  assert.ok(Date.now() - firstAt >= 300, 'request end must not prematurely close response');
  assert.equal(Buffer.concat(chunks).toString('utf8'),
    'data: {"text":"first chunk"}\n\ndata: {"text":"尾包"}\n\ndata: [DONE]\n\n');
});

test('requestTimeoutMs is socket idle time: active SSE outlives it, true idle terminates', async (t) => {
  const f = await fixture(t, { state: 'online', config: { requestTimeoutMs: 180 } });
  f.main.behaviors.active = 'active-sse';
  const started = Date.now();
  const reply = await f.post('/v1/responses', { 'x-test-case': 'active' }, '{}');
  assert.equal(reply.status, 200);
  assert.ok(Date.now() - started >= 400, 'active SSE must outlive the 180ms inactivity threshold');
  assert.equal(reply.text, Array.from({ length: 8 }, (_, i) => 'data: ' + i + '\n\n').join('') + 'data: [DONE]\n\n');
  f.main.behaviors.idle = 'hang';
  const idleStarted = Date.now();
  const idle = await f.post('/v1/responses', { 'x-test-case': 'idle' }, '{}');
  assert.equal(idle.status, 504);
  assert.match(idle.json.error.message, /idle timeout/);
  assert.ok(Date.now() - idleStarted >= 150);
  assert.equal(f.aux.calls.length, 0);
  assert.equal(f.main.calls.filter((call) => call.headers['x-test-case'] === 'idle').length, 1);
});

test('client cancellation closes upstream; post-header reset and request timeout do not replay', async (t) => {
  const f = await fixture(t, { state: 'online', config: { requestTimeoutMs: 800 } });
  f.main.behaviors.cancel = 'cancel';
  await new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port: f.port, path: '/v1/responses', method: 'POST', agent: false,
      headers: { 'x-test-case': 'cancel' } }, (res) => {
      res.once('data', () => { res.destroy(); req.destroy(); resolve(); });
      res.on('error', () => {});
    });
    req.on('error', reject);
    req.end('{}');
  });
  await until(() => f.main.cancelled > 0, 'client cancellation must close upstream response');
  const beforeUploadCancel = f.main.cancelled;
  const firstBody = new Promise((resolve) => { f.main.firstUpload = resolve; });
  const upload = http.request({ host: '127.0.0.1', port: f.port, path: '/v1/responses', method: 'POST', agent: false,
    headers: { 'x-test-case': 'upload-cancel' } });
  upload.on('error', () => {});
  upload.write('unfinished-upload');
  await Promise.race([firstBody, delay(1000).then(() => assert.fail('upload must reach fake upstream'))]);
  upload.destroy();
  await until(() => f.main.cancelled > beforeUploadCancel, 'request abort must close unfinished upstream upload');
  f.main.behaviors.partial = 'partial-reset';
  let partial = '';
  await new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port: f.port, path: '/v1/responses', method: 'POST', agent: false,
      headers: { 'x-test-case': 'partial' } }, (res) => {
      res.on('data', (chunk) => { partial += chunk; });
      res.once('aborted', resolve);
      res.on('error', () => {});
      res.once('end', () => reject(new Error('reset response should abort')));
    });
    req.once('error', reject);
    req.end('{}');
  });
  assert.equal(partial, 'data: first\n\n');
  f.main.behaviors.hang = 'hang';
  const reply = await f.post('/v1/responses', { 'x-test-case': 'hang' }, '{}');
  assert.equal(reply.status, 504);
  assert.equal(f.aux.calls.length, 0);
  for (const tag of ['cancel', 'partial', 'hang']) {
    assert.equal(f.main.calls.filter((call) => call.headers['x-test-case'] === tag).length, 1);
  }
});

test('frozen acceptance specification matches after tests', verifySpec);
