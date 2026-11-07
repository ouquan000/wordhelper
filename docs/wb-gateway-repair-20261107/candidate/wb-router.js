'use strict';
/*
 * wb-router.js —— WorkBuddy 网关入口路由
 *
 * 客户端 :8788 -> 主实例 :8790 / 年糕实例 :8789。
 * QQ 在线只用主实例；离线且辅池未暂停时，健康池按 5:3 权重合力。
 * 保留 QQ 迟滞、配置/状态路径、分流端点和辅池 API key 改写。
 * 两池定期验证 /healthz 的 JSON 身份与有效 healthy 数，过期状态不可选。
 * 每个请求只派发一次；请求体与 SSE 直接 pipe，客户端取消终止上游。
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');

const RUN_DIR = __dirname;
const LOG_FILE = path.join(RUN_DIR, 'router.log');
const STATE_FILE = path.join(RUN_DIR, 'router-state.json');
const CFG_FILE = path.join(RUN_DIR, 'router.config.json');

const DEFAULTS = {
  listen: 8788,
  main: { host: '127.0.0.1', port: 8790, key: 'sk-wb-local-8788' },
  aux: { host: '127.0.0.1', port: 8789, key: 'sk-wb-local-8789' },
  qqPort: 6199,
  probeMs: 5000,
  onlineHoldMs: 180000,
  offlineHoldMs: 300000,
  mainWeight: 5,
  auxWeight: 3,
  auxHealthMs: 10000,
  auxPauseUntil: null,
  requestTimeoutMs: 300000,
  // Additive settings; auxHealthMs remains the refresh interval for both pools.
  healthTimeoutMs: 2500,
  healthMaxBytes: 16384,
  healthStaleMs: 15000,
};

const SPLIT_PATHS = new Set([
  '/v1/chat/completions',
  '/v1/responses',
  '/v1/images/generations',
  '/v1/images/edits',
  '/v1/embeddings',
  '/v1/messages',
  '/messages',
  '/v1/search',
  '/v1/fetch',
  '/v2/chat/busy',
]);

function rotateLog() {
  try {
    const s = fs.statSync(LOG_FILE);
    if (s.size > 5 * 1024 * 1024) fs.renameSync(LOG_FILE, LOG_FILE + '.1');
  } catch (_) {}
}

function log(msg) {
  const line = new Date().toISOString() + ' ' + msg + '\n';
  try { rotateLog(); fs.appendFileSync(LOG_FILE, line, 'utf8'); } catch (_) {}
  process.stdout.write(line);
}

function bounded(value, fallback, min, max) {
  const n = Number(value);
  return Number.isFinite(n) && n >= min ? Math.min(Math.floor(n), max) : fallback;
}

function loadConfig() {
  let user = {};
  try {
    if (fs.existsSync(CFG_FILE)) user = JSON.parse(fs.readFileSync(CFG_FILE, 'utf8')) || {};
  } catch (e) {
    log('WARN router.config.json parse failed: ' + e.message);
  }
  const cfg = Object.assign({}, DEFAULTS, user);
  cfg.main = Object.assign({}, DEFAULTS.main, user.main || {});
  cfg.aux = Object.assign({}, DEFAULTS.aux, user.aux || {});
  cfg.probeMs = bounded(cfg.probeMs, DEFAULTS.probeMs, 20, 60000);
  cfg.auxHealthMs = bounded(cfg.auxHealthMs, DEFAULTS.auxHealthMs, 20, 60000);
  cfg.healthTimeoutMs = bounded(cfg.healthTimeoutMs, DEFAULTS.healthTimeoutMs, 20, 10000);
  cfg.healthMaxBytes = bounded(cfg.healthMaxBytes, DEFAULTS.healthMaxBytes, 128, 65536);
  cfg.healthStaleMs = bounded(cfg.healthStaleMs, DEFAULTS.healthStaleMs, 20, 120000);
  cfg.requestTimeoutMs = bounded(cfg.requestTimeoutMs, DEFAULTS.requestTimeoutMs, 20, 3600000);
  return cfg;
}

function loadState() {
  try {
    const j = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
    if (j && (j.state === 'online' || j.state === 'offline')) return j;
  } catch (_) {}
  return null;
}

function saveState() {
  const j = {
    state: st.state,
    changedAt: new Date(st.changedAt || Date.now()).toISOString(),
    raw: st.raw,
  };
  try { fs.writeFileSync(STATE_FILE, JSON.stringify(j, null, 2), 'utf8'); }
  catch (e) { log('WARN state save failed: ' + e.message); }
}

function newHealth() {
  return { ok: false, reason: 'not_checked', service: null, healthyCount: null,
    checkedAt: 0, startedAt: 0, generation: 0, flight: null };
}

const st = {
  cfg: null,
  state: 'online',
  changedAt: 0,
  raw: null,
  rawSince: 0,
  lastProbeAt: 0,
  lastProbeErr: '',
  health: { main: newHealth(), aux: newHealth() },
  wrr: { main: 0, aux: 0 },
  counters: { requests: 0, toMain: 0, toAux: 0, auxFail: 0, upstream5xx: 0 },
  stopping: false,
};

const timers = new Set();
const activeForwards = new Set();
const sockets = new Set();
let server = null;
let qqFlight = null;
const keepAliveAgent = new http.Agent({ keepAlive: true, maxSockets: 64 });

function setState(next, reason) {
  if (st.state === next) return;
  const prev = st.state;
  st.state = next;
  st.changedAt = Date.now();
  saveState();
  log('STATE ' + prev + ' -> ' + next + ' (' + reason + ')');
}

function probeQQ() {
  if (st.stopping || qqFlight) return;
  const flight = { child: null };
  qqFlight = flight;
  flight.child = execFile('netstat.exe', ['-ano'],
    { timeout: 8000, maxBuffer: 1024 * 1024, windowsHide: true }, (err, stdout) => {
      if (qqFlight !== flight || st.stopping) return;
      qqFlight = null;
      const now = Date.now();
      st.lastProbeAt = now;
      if (err) {
        st.lastProbeErr = err.message;
        return;
      }
      st.lastProbeErr = '';
      const port = String(st.cfg.qqPort);
      const re = new RegExp(':' + port + '\\s+\\S+\\s+ESTABLISHED', 'i');
      applyRaw(re.test(stdout || ''), now);
    });
}

function applyRaw(online, now) {
  if (st.raw === null || st.raw !== online) {
    st.raw = online;
    st.rawSince = now;
    return;
  }
  const held = now - st.rawSince;
  if (!online && st.state !== 'offline' && held >= st.cfg.offlineHoldMs) {
    setState('offline', 'QQ offline for ' + Math.round(held / 1000) + 's');
  } else if (online && st.state !== 'online' && held >= st.cfg.onlineHoldMs) {
    setState('online', 'QQ online for ' + Math.round(held / 1000) + 's');
  }
}

function healthView(name, now = Date.now()) {
  const h = st.health[name];
  const ageMs = h.checkedAt ? Math.max(0, now - h.checkedAt) : null;
  const fresh = ageMs !== null && ageMs < st.cfg.healthStaleMs;
  return {
    target: st.cfg[name].host + ':' + st.cfg[name].port,
    healthy: h.ok && fresh,
    reason: h.ok && !fresh ? 'stale' : h.reason,
    service: h.service,
    healthyCount: h.healthyCount,
    checkedAt: h.checkedAt ? new Date(h.checkedAt).toISOString() : null,
    ttlMs: st.cfg.healthStaleMs,
    ageMs,
    remainingTtlMs: ageMs === null ? 0 : Math.max(0, st.cfg.healthStaleMs - ageMs),
    expiresAt: h.checkedAt ? new Date(h.checkedAt + st.cfg.healthStaleMs).toISOString() : null,
    probing: !!h.flight,
  };
}

function checkHealth(name, force = false) {
  const h = st.health[name];
  if (st.stopping) return Promise.resolve();
  if (h.flight) return h.flight.promise;
  if (!force && Date.now() - h.startedAt < st.cfg.auxHealthMs) return Promise.resolve();
  h.startedAt = Date.now();
  const generation = ++h.generation;
  let resolve;
  const flight = { promise: new Promise((r) => { resolve = r; }), cancel: null };
  h.flight = flight;
  let request;
  let response;
  let deadline;
  let settled = false;
  function finish(result) {
    if (settled) return;
    settled = true;
    clearTimeout(deadline);
    // Complete only the generation that still owns this pool's probe state.
    if (h.flight === flight && h.generation === generation) {
      h.flight = null;
      if (!st.stopping) {
        if (h.ok !== result.ok || h.reason !== result.reason) {
          log('HEALTH ' + name + ' ' + result.reason);
        }
        Object.assign(h, result, { checkedAt: Date.now() });
      }
    }
    if (response) response.destroy();
    if (request) request.destroy();
    resolve();
  }
  function fail(reason) { finish({ ok: false, reason, service: null, healthyCount: null }); }
  flight.cancel = () => fail('shutdown');
  deadline = setTimeout(() => fail('timeout'), st.cfg.healthTimeoutMs);
  try {
    request = http.request({
      host: st.cfg[name].host,
      port: st.cfg[name].port,
      path: '/healthz',
      method: 'GET',
      headers: { accept: 'application/json' },
      agent: false,
      maxHeaderSize: 8192,
    }, (res) => {
      response = res;
      if (settled) return res.destroy();
      if (res.statusCode !== 200) return fail('http_status_' + res.statusCode);
      const chunks = [];
      let size = 0;
      res.on('data', (chunk) => {
        if (settled) return;
        size += chunk.length;
        if (size > st.cfg.healthMaxBytes) return fail('body_too_large');
        chunks.push(chunk);
      });
      res.on('aborted', () => fail('response_aborted'));
      res.on('error', () => fail('response_error'));
      res.on('end', () => {
        if (settled) return;
        let body;
        try { body = JSON.parse(Buffer.concat(chunks, size).toString('utf8')); }
        catch (_) { return fail('invalid_json'); }
        if (!body || body.service !== 'workbuddy2api') return fail('invalid_service');
        if (!Number.isSafeInteger(body.healthy) || body.healthy <= 0) {
          return finish({ ok: false, reason: 'invalid_healthy', service: body.service, healthyCount: null });
        }
        finish({ ok: true, reason: 'ok', service: body.service, healthyCount: body.healthy });
      });
    });
    request.on('error', () => fail('connection_error'));
    request.end();
  } catch (_) { fail('request_error'); }
  return flight.promise;
}

function checkPools() {
  return Promise.all([checkHealth('main'), checkHealth('aux')]);
}

function auxPaused(now = Date.now()) {
  return now < Date.parse(st.cfg.auxPauseUntil);
}

function pickTarget() {
  const now = Date.now();
  const mainOk = healthView('main', now).healthy;
  if (auxPaused(now) || st.state === 'online') return mainOk ? 'main' : null;
  const auxOk = healthView('aux', now).healthy;
  if (!mainOk) return auxOk ? 'aux' : null;
  if (!auxOk) return 'main';
  const mw = Math.max(1, Number(st.cfg.mainWeight) || 1);
  const aw = Math.max(1, Number(st.cfg.auxWeight) || 1);
  st.wrr.main += mw;
  st.wrr.aux += aw;
  const pick = st.wrr.aux >= st.wrr.main ? 'aux' : 'main';
  if (pick === 'aux') st.wrr.aux -= (mw + aw); else st.wrr.main -= (mw + aw);
  return pick;
}

function isSplit(pathname) { return SPLIT_PATHS.has(pathname.toLowerCase()); }

function unavailable(req, res, mainOnly) {
  const reason = mainOnly ? 'main_only' : auxPaused() ? 'aux_paused' :
    st.state === 'online' ? 'qq_online' : 'no_healthy_pool';
  const body = JSON.stringify({ error: {
    type: 'router_unavailable',
    message: 'wb-router: no eligible healthy upstream (' + reason + ')',
    main: healthView('main').reason,
    aux: healthView('aux').reason,
  } });
  res.writeHead(503, { 'content-type': 'application/json',
    'content-length': Buffer.byteLength(body), connection: 'close' });
  res.end(body);
  req.resume();
}

function forward(req, res, target) {
  const t = st.cfg[target];
  const headers = Object.assign({}, req.headers);
  headers.host = t.host + ':' + t.port;
  headers['x-wb-router'] = target;
  if (target === 'aux') {
    headers.authorization = 'Bearer ' + t.key;
    delete headers['x-api-key'];
  }
  const started = Date.now();
  let sent = 0;
  let upstream;
  let upRes;
  let done = false;
  function cleanup() {
    activeForwards.delete(cancel);
    req.removeListener('data', countData);
    req.removeListener('aborted', cancel);
    // Leave the idempotent error handler attached for a late upload abort.
    req.removeListener('close', requestClosed);
    res.removeListener('close', responseClosed);
    res.removeListener('finish', completed);
  }
  function cancel() {
    if (done) return;
    done = true;
    cleanup();
    if (upstream) { req.unpipe(upstream); upstream.destroy(); }
    if (upRes) upRes.destroy();
    if (!res.destroyed) res.destroy();
  }
  function countData(chunk) { sent += chunk.length; }
  function requestClosed() {
    // IncomingMessage.close also fires after a completely normal request body.
    if (req.aborted || !req.complete) cancel();
  }
  function responseClosed() { if (!res.writableFinished) cancel(); }
  function completed() {
    if (done) return;
    done = true;
    cleanup();
    if (upstream && !upstream.writableFinished) {
      req.unpipe(upstream);
      upstream.destroy();
      req.resume();
    }
    log('REQ ' + req.method + ' ' + req.url.split('?')[0] + ' -> ' + target +
      ' ' + res.statusCode + ' ' + (Date.now() - started) + 'ms ' + sent + 'B');
  }
  function fail(err, status = 502) {
    if (done) return;
    done = true;
    cleanup();
    if (target === 'aux') st.counters.auxFail++;
    if (upstream) { req.unpipe(upstream); upstream.destroy(); }
    if (upRes) upRes.destroy();
    log('ERR upstream ' + target + ' ' + err.message + ' ' + req.method + ' ' + req.url.split('?')[0]);
    if (res.headersSent) return res.destroy();
    if (res.destroyed) return;
    const body = JSON.stringify({ error: { type: 'router_error',
      message: 'wb-router: upstream ' + target + ' failed: ' + err.message } });
    res.writeHead(status, { 'content-type': 'application/json',
      'content-length': Buffer.byteLength(body), connection: 'close' });
    res.end(body);
    req.resume();
  }
  activeForwards.add(cancel);
  req.on('data', countData);
  req.once('aborted', cancel);
  req.once('error', cancel);
  req.once('close', requestClosed);
  res.once('close', responseClosed);
  res.once('finish', completed);
  try {
    upstream = http.request({ host: t.host, port: t.port, method: req.method,
      path: req.url, headers, agent: keepAliveAgent }, (response) => {
      upRes = response;
      if (done) return response.destroy();
      if (response.statusCode >= 500) st.counters.upstream5xx++;
      response.on('aborted', () => fail(new Error('upstream response aborted')));
      response.on('error', (e) => fail(e));
      response.on('close', () => {
        if (!response.complete) fail(new Error('upstream response closed'));
      });
      res.writeHead(response.statusCode, response.headers);
      if (res.flushHeaders) res.flushHeaders();
      response.pipe(res);
    });
    upstream.setTimeout(st.cfg.requestTimeoutMs, () => fail(new Error('request idle timeout'), 504));
    st.counters[target === 'aux' ? 'toAux' : 'toMain']++;
    upstream.on('error', (err) => fail(err));
    // Never buffer or replay a request, even if the peer fails before replying.
    req.pipe(upstream);
  } catch (e) { fail(e); }
}

function localOnly(req) {
  const a = req.socket.remoteAddress || '';
  return a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
}

function statusJSON() {
  const now = Date.now();
  return {
    state: st.state,
    stateMeaning: st.state === 'online' ? 'QQ 在线：只用主实例（不碰年糕的号）' : 'QQ 离线：主实例 + 年糕实例按权重合力',
    changedAt: st.changedAt ? new Date(st.changedAt).toISOString() : null,
    qq: { raw: st.raw, rawHeldMs: st.rawSince ? now - st.rawSince : null,
      lastProbeAt: st.lastProbeAt ? new Date(st.lastProbeAt).toISOString() : null,
      lastProbeErr: st.lastProbeErr },
    aux: Object.assign(healthView('aux', now), {
      paused: auxPaused(now), pauseUntil: st.cfg.auxPauseUntil || null }),
    main: healthView('main', now),
    hold: { onlineMs: st.cfg.onlineHoldMs, offlineMs: st.cfg.offlineHoldMs },
    weight: { main: st.cfg.mainWeight, aux: st.cfg.auxWeight },
    counters: st.counters,
  };
}

function shutdown(reason, exitCode = 0) {
  if (st.stopping) return;
  st.stopping = true;
  process.exitCode = exitCode;
  log('SHUTDOWN ' + reason);
  for (const timer of timers) clearInterval(timer);
  timers.clear();
  for (const h of Object.values(st.health)) {
    ++h.generation;
    if (h.flight) { const f = h.flight; h.flight = null; f.cancel(); }
  }
  if (qqFlight) {
    const child = qqFlight.child;
    qqFlight = null;
    if (child) child.kill();
  }
  for (const cancel of Array.from(activeForwards)) cancel();
  keepAliveAgent.destroy();
  if (server) server.close();
  for (const socket of sockets) socket.destroy();
}

function every(fn, ms) { timers.add(setInterval(fn, ms)); }

async function main() {
  st.cfg = loadConfig();
  const saved = loadState();
  if (saved) {
    st.state = saved.state;
    st.changedAt = Date.parse(saved.changedAt) || Date.now();
    log('START saved state ' + st.state);
  } else {
    st.changedAt = Date.now();
    log('START safe default online');
  }
  for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => shutdown(sig));
  // Both probes start together; neither pool is assumed healthy before these finish.
  await Promise.all([checkHealth('main', true), checkHealth('aux', true)]);
  if (st.stopping) return;
  server = http.createServer((req, res) => {
    const pathname = (req.url || '/').split('?')[0];
    if (pathname === '/_router/status') {
      if (!localOnly(req)) { res.writeHead(403); return res.end('local only'); }
      const body = JSON.stringify(statusJSON(), null, 2);
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      return res.end(body);
    }
    st.counters.requests++;
    const mainOnly = !isSplit(pathname);
    const target = mainOnly ? (healthView('main').healthy ? 'main' : null) : pickTarget();
    if (!target) return unavailable(req, res, mainOnly);
    forward(req, res, target);
  });
  server.on('connection', (socket) => {
    sockets.add(socket);
    socket.once('close', () => sockets.delete(socket));
  });
  server.on('upgrade', (req, socket) => {
    log('UPGRADE rejected ' + (req.url || '').split('?')[0]);
    socket.end('HTTP/1.1 426 Upgrade Required\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
  });
  server.on('error', (e) => shutdown('listen ' + st.cfg.listen + ': ' + e.message, 1));
  server.listen(st.cfg.listen, () => {
    log('wb-router listening :' + server.address().port +
      ' main=' + st.cfg.main.host + ':' + st.cfg.main.port +
      ' aux=' + st.cfg.aux.host + ':' + st.cfg.aux.port +
      ' weights=' + st.cfg.mainWeight + ':' + st.cfg.auxWeight);
    probeQQ();
    every(probeQQ, st.cfg.probeMs);
    every(checkPools, Math.min(5000, st.cfg.auxHealthMs));
    every(saveState, 600000);
  });
}

main().catch((e) => shutdown('startup: ' + e.message, 1));
