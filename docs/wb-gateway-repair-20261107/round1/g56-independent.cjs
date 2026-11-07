'use strict';
// Independently authored round1 dynamic acceptance. Never addresses real gateways.
const fs = require('fs');
const path = require('path');
const http = require('http');
const net = require('net');
const crypto = require('crypto');
const { spawn, execFileSync } = require('child_process');
const OUT = __dirname;
const ROOT = path.dirname(OUT);
const CANDIDATE = path.join(ROOT, 'candidate', 'wb-router.js');
const SPEC = path.join(ROOT, 'acceptance-spec.md');
const LOCK = path.join(ROOT, 'acceptance-spec.lock');
const EXPECTED = '2262451f13e4dcef160bb133d1fd17754aa56f0a4ace45c069ea0ebf59c7ce0f';
const SPEC_SHA = 'c2a33fc5c61973cf58ea892fd7472a142ed4ef222d5eeb63e497b4b36e90fe91';
const LOCK_SHA = '50f75118dffa481cd7299f17c6e2981b7dcc96252261cc1543382428bf0fe88b';
const runtime = path.join(OUT, '.runtime-g56-' + process.pid);
const report = { startedAt: new Date().toISOString(), candidate: CANDIDATE, cases: [], scenarios: [], cleanup: [], fatal: null, productionRequests: 0 };
const delay = ms => new Promise(r => setTimeout(r, ms));
const hash = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const hashes = () => ({ spec: hash(SPEC), lock: hash(LOCK), candidate: hash(CANDIDATE) });
const safePort = p => { if (!Number.isInteger(p) || p < 1024 || [8788, 8789, 8790, 6199].includes(p)) throw Error('Unsafe isolated port: ' + p); return p; };
let serial = 0;
function must(ok, details) { if (!ok) { const e = new Error('Independent assertion failed'); e.details = details; throw e; } }
async function test(name, fn) {
  const began = Date.now();
  try { const details = await fn(); report.cases.push({ name, status: 'PASS', durationMs: Date.now() - began, details }); }
  catch (e) { report.cases.push({ name, status: 'FAIL', durationMs: Date.now() - began, error: e.message, details: e.details || null }); }
  console.log(name + ': ' + report.cases.at(-1).status);
}
async function until(fn, predicate, timeout = 4000) {
  const deadline = Date.now() + timeout;
  let last;
  while (Date.now() < deadline) {
    last = await fn();
    if (predicate(last)) return last;
    await delay(25);
  }
  const e = new Error('Timed out awaiting isolated condition'); e.details = last; throw e;
}
async function listen(server) {
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return safePort(server.address().port);
}
function httpCall(port, pathname, { method = 'GET', body = null, headers = {}, budgetMs = 4000 } = {}) {
  safePort(port);
  return new Promise(resolve => {
    const start = Date.now(); let ended = false; let response; const chunks = []; const arrivals = [];
    const finish = (complete, error) => {
      if (ended) return; ended = true; clearTimeout(budget);
      resolve({ status: response ? response.statusCode : null, complete, error: error || null, elapsedMs: Date.now()-start, firstDataMs: arrivals.length ? arrivals[0] : null, arrivalsMs: arrivals, body: Buffer.concat(chunks).toString('utf8'), bodyHex: Buffer.concat(chunks).toString('hex') });
    };
    const req = http.request({ host: '127.0.0.1', port, path: pathname, method, headers, agent: false }, res => {
      response = res;
      res.on('data', chunk => { chunks.push(chunk); arrivals.push(Date.now()-start); });
      res.on('end', () => finish(true));
      res.on('aborted', () => finish(false, 'response_aborted'));
      res.on('error', e => finish(false, e.code || e.message));
    });
    const budget = setTimeout(() => { req.destroy(); finish(false, 'client_budget'); }, budgetMs);
    req.on('error', e => finish(false, e.code || e.message));
    req.end(body);
  });
}
const SSE = Buffer.from('data: {"text":"首包完整"}\n\ndata: {"text":"second"}\n\ndata: [DONE]\n\n');
function mockPool(name) {
  const p = { name, healthMode: 'good', healthyCount: name === 'main' ? 7 : 1, healthCalls: 0, records: [], sockets: new Set(), timers: new Set() };
  function timer(fn, ms) { const t = setTimeout(() => { p.timers.delete(t); fn(); }, ms); p.timers.add(t); return t; }
  p.server = http.createServer((req, res) => {
    if (req.url === '/healthz') {
      p.healthCalls++;
      const mode = p.healthMode;
      const good = { service: 'workbuddy2api', healthy: p.healthyCount };
      const send = (status, body) => { if (!res.destroyed) { res.writeHead(status, { 'content-type': 'application/json' }); res.end(typeof body === 'string' ? body : JSON.stringify(body)); } };
      if (mode === 'good') return send(200, good);
      if (mode === 'arbitrary200') return send(200, { ok: true });
      if (mode === 'wrongService') return send(200, { service: 'unrelated-stub', healthy: 7 });
      if (mode === 'http404') return send(404, good);
      if (mode === 'invalidJSON') return send(200, '{');
      if (mode === 'tooLarge') return send(200, { ...good, pad: 'x'.repeat(2048) });
      if (mode === 'silent') return;
      if (mode === 'lateGood') return void timer(() => send(200, good), 450);
      if (mode === 'trickle') {
        res.writeHead(200, { 'content-type': 'application/json' }); res.flushHeaders();
        let n = 0; const tick = () => { if (!res.destroyed && n++ < 100) { res.write(' '); timer(tick, 25); } }; tick(); return;
      }
      const values = { zero: 0, negative: -1, fractional: 1.5, string: '1', boolean: true, null: null, unsafe: 9007199254740992 };
      return send(200, { service: 'workbuddy2api', healthy: values[mode] });
    }
    const rec = { id: req.headers['x-round1-id'] || null, pool: name, method: req.method, path: req.url, mode: req.headers['x-round1-mode'] || 'echo', receivedAt: Date.now(), bytes: 0, complete: false, requestAborted: false, responseClosed: false, responseFinished: false, writes: 0, authIsAuxFixture: req.headers.authorization === 'Bearer round1-aux-fixture', authIsClientFixture: req.headers.authorization === 'Bearer round1-client-fixture', hasXApiKey: Object.hasOwn(req.headers, 'x-api-key'), routerHeader: req.headers['x-wb-router'] || null };
    p.records.push(rec);
    const digest = crypto.createHash('sha256');
    req.on('data', chunk => { rec.bytes += chunk.length; rec.firstUploadAt ||= Date.now(); digest.update(chunk); });
    req.on('aborted', () => { rec.requestAborted = true; rec.requestClosedAt = Date.now(); });
    req.on('error', () => {});
    res.on('finish', () => { rec.responseFinished = true; });
    res.on('close', () => { rec.responseClosed = true; rec.responseClosedAt = Date.now(); });
    const send = (status, body) => { if (!res.destroyed) { res.writeHead(status, { 'content-type': 'application/json' }); res.end(JSON.stringify(body)); } };
    if (req.url === '/v1/models') { req.resume(); return send(200, { object: 'list', data: [{ id: name + '-fixture-model' }], pool: name }); }
    if (req.url === '/panel/' || req.url === '/status') { req.resume(); return send(200, { pool: name }); }
    if (rec.mode === 'resetHeaders') { req.socket.destroy(); return; }
    if (rec.mode === 'idleBeforeHeaders') { req.resume(); return; }
    if (rec.mode === 'idleAfterHeaders') { res.writeHead(200, { 'content-type': 'text/event-stream' }); res.flushHeaders(); res.write('data: first\n\n'); req.resume(); return; }
    if (rec.mode === 'partialReset') { res.writeHead(200, { 'content-type': 'text/event-stream' }); res.write('data: partial'); timer(() => req.socket.destroy(), 40); req.resume(); return; }
    if (rec.mode === 'cancelStream') {
      res.writeHead(200, { 'content-type': 'text/event-stream' }); res.flushHeaders(); req.resume();
      const tick = () => { if (!res.destroyed) { rec.writes++; res.write('data: tick\n\n'); timer(tick, 35); } }; tick(); return;
    }
    if (rec.mode === 'stream') {
      res.writeHead(200, { 'content-type': 'text/event-stream' }); res.flushHeaders(); req.resume();
      const bounds = [0, 18, 20, 29, 42, 55, SSE.length]; let i = 0;
      const tick = () => { if (res.destroyed) return; const a = bounds[i], b = bounds[++i]; rec.writes++; res.write(SSE.subarray(a, b)); if (i < bounds.length-1) timer(tick, 90); else res.end(); }; tick(); return;
    }
    req.on('end', () => {
      rec.complete = true; rec.endedAt = Date.now(); rec.bodySha256 = digest.digest('hex');
      if (rec.mode === 'resetBody') return req.socket.destroy();
      if (rec.mode === 'status404' || rec.mode === 'status429') return send(Number(rec.mode.slice(6)), { pool: name, error: rec.mode });
      if (rec.mode === 'uploadCancel') return send(500, { error: 'upload unexpectedly completed' });
      send(200, { pool: name, bytes: rec.bytes, bodySha256: rec.bodySha256 });
    });
  });
  p.server.on('connection', s => { p.sockets.add(s); s.on('close', () => p.sockets.delete(s)); });
  p.close = async () => { for (const t of p.timers) clearTimeout(t); p.timers.clear(); for (const s of p.sockets) s.destroy(); await new Promise(r => p.server.close(r)); };
  return p;
}
async function startScenario(label, options = {}) {
  const dir = path.join(runtime, label); fs.mkdirSync(dir, { recursive: true });
  const main = mockPool('main'), aux = mockPool('aux');
  const mainPort = await listen(main.server), auxPort = await listen(aux.server);
  const qqSockets = new Set(); const qqServer = net.createServer(s => { qqSockets.add(s); s.on('close', () => qqSockets.delete(s)); });
  const qqPort = await listen(qqServer);
  const cfg = { listen: 0, main: { host: '127.0.0.1', port: mainPort, key: 'round1-main-fixture' }, aux: { host: '127.0.0.1', port: auxPort, key: 'round1-aux-fixture' }, qqPort, probeMs: 150, onlineHoldMs: 600000, offlineHoldMs: 600000, mainWeight: 5, auxWeight: 3, auxHealthMs: 80, healthTimeoutMs: 140, healthMaxBytes: 1024, healthStaleMs: 1200, requestTimeoutMs: 240, auxPauseUntil: options.paused ? new Date(Date.now()+600000).toISOString() : null, ...options.config };
  fs.copyFileSync(CANDIDATE, path.join(dir, 'wb-router.js'));
  must(hash(path.join(dir, 'wb-router.js')) === EXPECTED, { copyHash: hash(path.join(dir, 'wb-router.js')) });
  fs.writeFileSync(path.join(dir, 'router.config.json'), JSON.stringify(cfg));
  fs.writeFileSync(path.join(dir, 'router-state.json'), JSON.stringify({ state: options.state || 'offline', changedAt: new Date().toISOString(), raw: null }));
  const logfile = path.join(OUT, 'g56-' + label + '-router.log');
  const log = fs.createWriteStream(logfile, { flags: 'wx' });
  const argv = ['--require', path.join(OUT, 'owned-router-lifecycle.cjs'), path.join(dir, 'wb-router.js')];
  const child = spawn(process.execPath, argv, { cwd: dir, stdio: ['ignore', 'pipe', 'pipe', 'ipc'], windowsHide: true });
  let output = '', closed = false;
  child.stdout.on('data', c => { output += c; log.write(c); }); child.stderr.on('data', c => { output += c; log.write(c); });
  child.on('exit', () => { closed = true; });
  const item = { label, pid: child.pid, command: [process.execPath, ...argv], mainPort, auxPort, qqPort, logfile, stateFixture: options.state || 'offline', pauseFixture: !!options.paused, ports: [], configTimings: { requestTimeoutMs: cfg.requestTimeoutMs, healthTimeoutMs: cfg.healthTimeoutMs, healthStaleMs: cfg.healthStaleMs, auxHealthMs: cfg.auxHealthMs }, candidateCopyHash: hash(path.join(dir, 'wb-router.js')) };
  report.scenarios.push(item);
  const s = { label, main, aux, child, qqServer, qqSockets, qqPort, cfg, records: () => [...main.records, ...aux.records], item };
  s.close = async () => {
    if (child.connected) child.send('round1-owned-teardown');
    await until(async () => closed, x => x, 3000).catch(() => { child.kill(); });
    if (!closed) await until(async () => closed, x => x, 3000);
    for (const socket of qqSockets) socket.destroy();
    await Promise.all([main.close(), aux.close(), new Promise(r => qqServer.close(r))]);
    await new Promise(r => log.end(r));
    const allPorts = [mainPort, auxPort, qqPort, s.port].filter(Boolean);
    const closedChecks = await Promise.all(allPorts.map(port => new Promise(resolve => { const c = net.connect({ host: '127.0.0.1', port }); c.on('connect', () => { c.destroy(); resolve(false); }); c.on('error', () => resolve(true)); })));
    report.cleanup.push({ label, pid: child.pid, childExited: closed, childExitCode: child.exitCode, ports: allPorts, allPortsClosed: closedChecks.every(Boolean), noProcessLookupKill: true });
    fs.rmSync(dir, { recursive: true });
  };
  try {
    await until(async () => { if (closed) throw Error('Owned router exited during startup: ' + output); return output; }, out => /wb-router listening :(\d+)/.test(out), 5000);
    s.port = safePort(Number(output.match(/wb-router listening :(\d+)/)[1])); item.routerPort = s.port;
    s.status = async () => { const r = await httpCall(s.port, '/_router/status'); must(r.status === 200, r); return JSON.parse(r.body); };
    s.waitHealth = async (m, a, previous = null) => until(s.status, v => v.main.reason === m && v.aux.reason === a && (!previous || (v.main.checkedAt !== previous.main.checkedAt && v.aux.checkedAt !== previous.aux.checkedAt)), 4000);
    s.call = async (mode = 'echo', body = '{}', pathname = '/v1/chat/completions') => {
      const id = 'round1-' + (++serial);
      const result = await httpCall(s.port, pathname, { method: 'POST', body, headers: { 'x-round1-id': id, 'x-round1-mode': mode, authorization: 'Bearer round1-client-fixture', 'x-api-key': 'round1-client-fixture', 'content-type': 'application/json' } });
      return { id, ...result };
    };
    return s;
  } catch (e) { await s.close(); throw e; }
}
async function withScenario(label, options, fn) { const s = await startScenario(label, options); try { await fn(s); } finally { await s.close(); } }
function received(s, id) { return s.records().filter(r => r.id === id); }
async function changeHealth(s, mainMode, auxMode, expectedMain, expectedAux) {
  const prior = await s.status(); s.main.healthMode = mainMode; s.aux.healthMode = auxMode;
  return s.waitHealth(expectedMain, expectedAux, prior);
}

async function matrix() {
  for (const state of ['offline', 'online']) for (const paused of [false, true]) {
    await withScenario('matrix-' + state + '-' + paused, { state, paused }, async s => {
      for (const mainOk of [true, false]) for (const auxOk of [true, false]) {
        await test(`G5.matrix.${state}.paused_${paused}.main_${mainOk}.aux_${auxOk}`, async () => {
          const status = await changeHealth(s, mainOk ? 'good' : 'zero', auxOk ? 'good' : 'zero', mainOk ? 'ok' : 'invalid_healthy', auxOk ? 'ok' : 'invalid_healthy');
          const eligible = state === 'online' || paused ? (mainOk ? ['main'] : []) : [mainOk && 'main', auxOk && 'aux'].filter(Boolean);
          const outputs = [];
          for (let i=0; i<8; i++) {
            const r = await s.call('echo', i%2 ? '{"fixture":true}' : '');
            const recs = received(s, r.id); outputs.push({ id: r.id, status: r.status, receipts: recs.map(x => x.pool) });
            must(eligible.length ? r.status === 200 && recs.length === 1 && eligible.includes(recs[0].pool) : r.status === 503 && recs.length === 0, { eligible, response: r, recs });
          }
          const counts = { main: outputs.filter(x => x.receipts.includes('main')).length, aux: outputs.filter(x => x.receipts.includes('aux')).length };
          if (eligible.length === 2) must(counts.main === 5 && counts.aux === 3, counts);
          return { status, eligible, counts, outputs };
        });
      }
    });
  }
}
async function healthValidation() {
  await withScenario('health-validation', {}, async s => {
    const invalid = { arbitrary200: 'invalid_service', wrongService: 'invalid_service', http404: 'http_status_404', invalidJSON: 'invalid_json', zero: 'invalid_healthy', negative: 'invalid_healthy', fractional: 'invalid_healthy', string: 'invalid_healthy', boolean: 'invalid_healthy', null: 'invalid_healthy', unsafe: 'invalid_healthy', tooLarge: 'body_too_large', silent: 'timeout', trickle: 'timeout', lateGood: 'timeout' };
    for (const pool of ['main', 'aux']) for (const [mode, reason] of Object.entries(invalid)) {
      await test(`G5.health.${pool}.${mode}`, async () => {
        await changeHealth(s, 'good', 'good', 'ok', 'ok');
        const status = await changeHealth(s, pool === 'main' ? mode : 'good', pool === 'aux' ? mode : 'good', pool === 'main' ? reason : 'ok', pool === 'aux' ? reason : 'ok');
        const r = await s.call(); const recs = received(s, r.id); const other = pool === 'main' ? 'aux' : 'main';
        must(status[pool].healthy === false && r.status === 200 && recs.length === 1 && recs[0].pool === other, { status, response: r, recs });
        return { rejectedPool: pool, reason, status, responseStatus: r.status, dispatchedPool: recs[0].pool };
      });
    }
    await test('G5.health.recovery-and-status-fields', async () => {
      const status = await changeHealth(s, 'good', 'good', 'ok', 'ok');
      for (const pool of ['main', 'aux']) must(status[pool].healthy && status[pool].service === 'workbuddy2api' && Number.isInteger(status[pool].healthyCount) && status[pool].healthyCount > 0 && status[pool].remainingTtlMs > 0 && status[pool].expiresAt, status);
      return status;
    });
    await test('G5.main-only.routes-and-aux-authorization', async () => {
      const outputs = [];
      for (const p of ['/v1/models', '/panel/', '/status', '/healthz']) {
        const before = s.aux.records.length; const r = await httpCall(s.port, p);
        must(r.status === 200 && s.aux.records.length === before, r);
        if (p !== '/healthz') must(JSON.parse(r.body).pool === 'main', r);
        outputs.push({ path: p, status: r.status, auxReceipts: s.aux.records.length-before });
      }
      await changeHealth(s, 'zero', 'good', 'invalid_healthy', 'ok');
      for (const p of ['/v1/models', '/panel/', '/status', '/healthz']) { const before = s.records().length; const r = await httpCall(s.port, p); must(r.status === 503 && s.records().length === before && JSON.parse(r.body).error.type === 'router_unavailable', r); outputs.push({ path: p, mainBadStatus: r.status }); }
      const aux = await s.call(); const rec = received(s, aux.id)[0];
      must(aux.status === 200 && rec.pool === 'aux' && rec.authIsAuxFixture && !rec.hasXApiKey && rec.routerHeader === 'aux', rec);
      await changeHealth(s, 'good', 'zero', 'ok', 'invalid_healthy');
      const main = await s.call(); const mainRec = received(s, main.id)[0];
      must(mainRec.authIsClientFixture && mainRec.hasXApiKey && mainRec.routerHeader === 'main', mainRec);
      return { outputs, authRewriting: { auxUsesOwnFixture: rec.authIsAuxFixture, auxDropsClientXApiKey: !rec.hasXApiKey, mainPreservesClientFixture: mainRec.authIsClientFixture } };
    });
    await changeHealth(s, 'good', 'good', 'ok', 'ok');
    await test('G5.all-split-endpoints', async () => {
      const paths = ['/v1/chat/completions', '/v1/responses', '/v1/images/generations', '/v1/images/edits', '/v1/embeddings', '/v1/messages', '/messages', '/v1/search', '/v1/fetch', '/v2/chat/busy'];
      await changeHealth(s, 'zero', 'good', 'invalid_healthy', 'ok');
      const outputs = [];
      for (const p of paths) { const r = await s.call('echo', '{}', p); const recs = received(s, r.id); must(r.status === 200 && recs.length === 1 && recs[0].pool === 'aux', { p, r, recs }); outputs.push({ path: p, pool: recs[0].pool }); }
      return outputs;
    });
  });
  await withScenario('health-ttl', { config: { auxHealthMs: 700, healthStaleMs: 180, healthTimeoutMs: 140 } }, async s => {
    await test('G5.health.ttl-expiry-no-stale-routing-and-recovery', async () => {
      await delay(240); const stale = await s.status(); const r = await s.call();
      must(stale.main.reason === 'stale' && stale.aux.reason === 'stale' && !stale.main.healthy && !stale.aux.healthy && r.status === 503 && received(s, r.id).length === 0, { stale, r });
      const recovered = await until(s.status, x => x.main.healthy && x.aux.healthy, 1800); const good = await s.call(); must(good.status === 200, good);
      return { stale, rejectedStatus: r.status, recovered, recoveredStatus: good.status };
    });
  });
  await withScenario('health-inflight-ttl', { config: { auxHealthMs: 160, healthStaleMs: 110, healthTimeoutMs: 450 } }, async s => {
    await test('G5.health.ttl-expires-during-probe-and-timeout', async () => {
      s.main.healthMode = 'silent'; s.aux.healthMode = 'silent'; await delay(235);
      const pending = await s.status(); const r = await s.call();
      must(pending.main.probing && pending.aux.probing && pending.main.reason === 'stale' && pending.aux.reason === 'stale' && r.status === 503 && received(s, r.id).length === 0, { pending, r });
      const failed = await s.waitHealth('timeout', 'timeout');
      return { pending, requestStatus: r.status, failed };
    });
  });
}
async function qqTransition() {
  await withScenario('qq-real-netstat', { state: 'offline', config: { onlineHoldMs: 0, offlineHoldMs: 0, probeMs: 100 } }, async s => {
    await test('G5.qq.actual-established-online-offline-transition', async () => {
      const client = net.connect({ host: '127.0.0.1', port: s.qqPort });
      await new Promise((r, j) => { client.once('connect', r); client.once('error', j); });
      try {
        const lines = execFileSync('netstat.exe', ['-ano'], { encoding: 'utf8', windowsHide: true }).split(/\r?\n/).filter(x => x.includes(':' + s.qqPort) && x.includes('ESTABLISHED'));
        must(lines.length > 0, { qqPort: s.qqPort, lines });
        const online = await until(s.status, x => x.qq.raw === true && x.state === 'online', 7000);
        await changeHealth(s, 'zero', 'good', 'invalid_healthy', 'ok');
        const blocked = await s.call(); must(blocked.status === 503 && received(s, blocked.id).length === 0, blocked);
        client.destroy();
        const offline = await until(s.status, x => x.qq.raw === false && x.state === 'offline', 7000);
        const allowed = await s.call(); const recs = received(s, allowed.id);
        must(allowed.status === 200 && recs.length === 1 && recs[0].pool === 'aux', { allowed, recs });
        return { qqPort: s.qqPort, independentNetstatLines: lines, online, onlineMainBadStatus: blocked.status, offline, offlineMainBadPool: recs[0].pool };
      } finally { client.destroy(); }
    });
  });
}
async function noReplayAndStreaming() {
  await withScenario('forwarding', { config: { requestTimeoutMs: 240 } }, async s => {
    for (const mode of ['status404', 'status429', 'resetHeaders', 'resetBody', 'partialReset']) for (const body of ['', '{"synthetic":"nonempty"}']) {
      await test('G6.no-replay.' + mode + '.' + (body ? 'nonempty' : 'empty'), async () => {
        const outputs = []; const pools = new Set();
        for (let i=0; i<8; i++) {
          const r = await s.call(mode, body); await delay(15); const recs = received(s, r.id);
          must(recs.length === 1, { r, recs }); pools.add(recs[0].pool);
          if (mode === 'status404' || mode === 'status429') must(r.complete && r.status === Number(mode.slice(6)), r);
          else if (mode === 'partialReset') must(r.status === 200 && !r.complete, r);
          else must(r.status === 502 || !r.complete, r);
          if (!['resetHeaders', 'partialReset'].includes(mode)) must(recs[0].bytes === Buffer.byteLength(body), recs[0]);
          outputs.push({ id: r.id, status: r.status, complete: r.complete, pool: recs[0].pool, receipts: recs.length, bytes: recs[0].bytes });
        }
        must(pools.size === 2, { pools: [...pools] }); return outputs;
      });
    }
    await test('G6.sse-first-data-exact-bytes-continuous-beyond-idle-timeout', async () => {
      const outputs = []; const pools = new Set();
      for (let i=0; i<8; i++) {
        const r = await s.call('stream'); const recs = received(s, r.id); pools.add(recs[0].pool);
        must(r.status === 200 && r.complete && r.bodyHex === SSE.toString('hex') && r.firstDataMs < 180 && r.arrivalsMs.length >= 5 && r.elapsedMs > s.cfg.requestTimeoutMs, { r, recs });
        outputs.push({ id: r.id, pool: recs[0].pool, exactBytes: true, firstDataMs: r.firstDataMs, totalMs: r.elapsedMs, arrivalsMs: r.arrivalsMs, idleTimeoutMs: s.cfg.requestTimeoutMs });
      }
      must(pools.size === 2, { pools: [...pools] }); return outputs;
    });
    await test('G6.sse-client-cancel-aborts-upstream', async () => {
      const outputs = []; const pools = new Set();
      for (let i=0; i<8; i++) {
        const id = 'round1-' + (++serial); let firstDataAt;
        await new Promise((resolve, reject) => {
          const req = http.request({ host: '127.0.0.1', port: s.port, path: '/v1/chat/completions', method: 'POST', headers: { 'x-round1-id': id, 'x-round1-mode': 'cancelStream' }, agent: false }, res => {
            res.once('data', () => { firstDataAt = Date.now(); res.destroy(); req.destroy(); resolve(); }); res.on('error', () => {});
          }); req.on('error', reject); req.end('{}');
        });
        const rec = await until(async () => received(s, id)[0], x => x && x.responseClosed, 1000); const writes = rec.writes; await delay(100);
        must(!rec.responseFinished && rec.responseClosedAt-firstDataAt < 700 && rec.writes === writes && received(s, id).length === 1, rec);
        pools.add(rec.pool); outputs.push({ id, pool: rec.pool, closeAfterCancelMs: rec.responseClosedAt-firstDataAt, writesAfterClose: rec.writes-writes, upstreamResponseFinished: rec.responseFinished });
      }
      must(pools.size === 2, { pools: [...pools] }); return outputs;
    });
    await test('G6.upload-streaming-and-client-cancel', async () => {
      const outputs = []; const pools = new Set();
      for (let i=0; i<8; i++) {
        const id = 'round1-' + (++serial);
        const req = http.request({ host: '127.0.0.1', port: s.port, path: '/v1/chat/completions', method: 'POST', headers: { 'x-round1-id': id, 'x-round1-mode': 'uploadCancel', 'content-length': String(1048576) }, agent: false }); req.on('error', () => {});
        req.write(Buffer.alloc(32768, 120));
        const partial = await until(async () => received(s, id)[0], x => x && x.bytes > 0, 1200); const seenBeforeFinish = !req.writableFinished;
        const cancelledAt = Date.now(); req.destroy();
        const rec = await until(async () => received(s, id)[0], x => x && x.requestAborted, 1000);
        must(seenBeforeFinish && rec.bytes === 32768 && !rec.complete && rec.requestClosedAt-cancelledAt < 700 && received(s, id).length === 1, rec);
        pools.add(rec.pool); outputs.push({ id, pool: rec.pool, forwardedBeforeClientEnd: seenBeforeFinish, bytes: rec.bytes, promisedBytes: 1048576, abortedAfterCancelMs: rec.requestClosedAt-cancelledAt });
      }
      must(pools.size === 2, { pools: [...pools] }); return outputs;
    });
    await test('G6.upload-active-longer-than-idle-timeout-completes', async () => {
      const id = 'round1-' + (++serial); const started = Date.now();
      const response = new Promise((resolve, reject) => {
        const req = http.request({ host: '127.0.0.1', port: s.port, path: '/v1/chat/completions', method: 'POST', headers: { 'x-round1-id': id, 'x-round1-mode': 'echo' }, agent: false }, res => { const chunks = []; res.on('data', x => chunks.push(x)); res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString() })); res.on('error', reject); });
        req.on('error', reject);
        (async () => { for (let j=0; j<8; j++) { req.write(Buffer.alloc(1024, 97+j)); await delay(70); } req.end(); })().catch(reject);
      });
      const r = await response; const rec = received(s, id)[0]; const elapsedMs = Date.now()-started;
      must(r.status === 200 && rec.complete && rec.bytes === 8192 && elapsedMs > s.cfg.requestTimeoutMs, { r, rec, elapsedMs });
      return { elapsedMs, idleTimeoutMs: s.cfg.requestTimeoutMs, status: r.status, bytes: rec.bytes, firstDataBeforeEnd: rec.firstUploadAt < rec.endedAt };
    });
    for (const mode of ['idleBeforeHeaders', 'idleAfterHeaders']) await test('G6.true-idle-timeout.' + mode, async () => {
      const r = await s.call(mode); const recs = received(s, r.id);
      must(recs.length === 1 && r.elapsedMs >= 180 && r.elapsedMs < 1600, { r, recs });
      must(mode === 'idleBeforeHeaders' ? r.status === 504 && r.complete : r.status === 200 && !r.complete, r);
      await until(async () => recs[0].responseClosed, x => x, 1000);
      return { status: r.status, complete: r.complete, elapsedMs: r.elapsedMs, idleTimeoutMs: s.cfg.requestTimeoutMs, receipts: recs.length, upstreamClosed: recs[0].responseClosed };
    });
    await delay(250);
    await test('G6.global-dispatch-uniqueness-after-settlement', async () => {
      const grouped = new Map(); for (const rec of s.records()) if (rec.id) grouped.set(rec.id, (grouped.get(rec.id)||0)+1);
      const duplicates = [...grouped].filter(([, n]) => n !== 1); must(duplicates.length === 0, { duplicates });
      return { distinctRequests: grouped.size, receipts: s.records().filter(x => x.id).length, duplicateReceipts: duplicates };
    });
    report.forwardingReceipts = s.records();
  });
}
(async () => {
  report.hashesBegin = hashes();
  must(report.hashesBegin.spec === SPEC_SHA && report.hashesBegin.lock === LOCK_SHA && report.hashesBegin.candidate === EXPECTED, report.hashesBegin);
  fs.mkdirSync(runtime);
  try { await matrix(); await healthValidation(); await qqTransition(); await noReplayAndStreaming(); }
  catch (e) { report.fatal = { error: e.message, details: e.details || null, stack: e.stack }; }
  finally {
    report.hashesEnd = hashes(); report.endedAt = new Date().toISOString();
    report.freezeUnchanged = report.hashesEnd.spec === SPEC_SHA && report.hashesEnd.lock === LOCK_SHA && report.hashesEnd.candidate === EXPECTED;
    report.runtimeRemoved = !fs.existsSync(runtime) || fs.readdirSync(runtime).length === 0;
    if (report.runtimeRemoved && fs.existsSync(runtime)) fs.rmdirSync(runtime);
    report.summary = { totalCases: report.cases.length, passed: report.cases.filter(x => x.status === 'PASS').length, failed: report.cases.filter(x => x.status === 'FAIL').map(x => x.name), cleanupAllPassed: report.cleanup.every(x => x.childExited && x.allPortsClosed), freezeUnchanged: report.freezeUnchanged, runtimeRemoved: report.runtimeRemoved, fatal: report.fatal };
    fs.writeFileSync(path.join(OUT, 'g56-evidence.json'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report.summary, null, 2));
    process.exitCode = report.summary.failed.length || report.fatal || !report.summary.cleanupAllPassed || !report.freezeUnchanged || !report.runtimeRemoved ? 1 : 0;
  }
})().catch(e => { console.error(e); process.exitCode = 2; });
