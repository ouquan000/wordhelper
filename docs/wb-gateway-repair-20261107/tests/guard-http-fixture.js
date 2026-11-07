'use strict';
// Ephemeral localhost fixture. Never touches 8788/8789/8790 or upstream services.
const http = require('http');
const fs = require('fs');
const manifest = process.argv[2];
const sockets = new Set();
const server = http.createServer((req, res) => {
  if (req.method !== 'GET') { res.writeHead(405); return res.end(); }
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  if (req.url === '/ok') return res.end('{"service":"workbuddy2api","healthy":7}');
  if (req.url === '/wrong') return res.end('{"service":"some-other-service","healthy":7}');
  if (req.url === '/zero') return res.end('{"service":"workbuddy2api","healthy":0}');
  if (req.url === '/malformed') return res.end('{');
  if (req.url === '/array') return res.end('[{"service":"workbuddy2api","healthy":7}]');
  if (req.url === '/html') { res.setHeader('Content-Type', 'text/html'); return res.end('<html>ok</html>'); }
  if (req.url === '/redirect') { res.writeHead(302, { Location: '/ok' }); return res.end(); }
  if (req.url === '/oversize-length') return res.end(JSON.stringify({ padding: 'x'.repeat(65536) }));
  if (req.url === '/oversize-chunked') {
    res.writeHead(200); res.write('{"padding":"');
    for (let i = 0; i < 40; i++) res.write('x'.repeat(1024));
    return res.end('"}');
  }
  if (req.url === '/slow-headers') { const timer = setTimeout(() => res.end('{}'), 3000); req.on('close', () => clearTimeout(timer)); return; }
  if (req.url === '/drip') {
    res.writeHead(200); res.write('{');
    const timer = setInterval(() => res.write(' '), 40);
    res.on('close', () => clearInterval(timer)); return;
  }
  if (req.url === '/truncated') { res.writeHead(200, { 'Content-Length': 1024 }); res.write('{'); return res.destroy(); }
  res.writeHead(404); res.end('{"service":"workbuddy2api","healthy":7}');
});
server.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
server.listen(0, '127.0.0.1', () => {
  const port = server.address().port;
  if ([8788, 8789, 8790].includes(port)) { server.close(); process.exitCode = 2; return; }
  fs.writeFileSync(manifest, JSON.stringify({ pid: process.pid, port }), 'utf8');
});
process.on('SIGTERM', () => { for (const socket of sockets) socket.destroy(); server.close(() => process.exit(0)); });
