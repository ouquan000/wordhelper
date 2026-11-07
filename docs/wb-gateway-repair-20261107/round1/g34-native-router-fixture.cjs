'use strict';
// A native node.exe owner for isolated helper/guard identity acceptance.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const cfg = JSON.parse(fs.readFileSync(path.join(__dirname, 'router-fixture.json'), 'utf8'));
if (!(cfg.port > 1024) || [8788, 8789, 8790, 6199].includes(cfg.port)) throw new Error('independent port is not isolated');
const record = item => fs.appendFileSync(cfg.receiptFile, JSON.stringify({...item, pid: process.pid}) + '\n');
record({kind:'launch', port:cfg.port, exe:process.execPath});
const server = http.createServer((req, res) => {
  if (req.url === '/__independent_stop/' + cfg.stopToken) {
    res.end('{"stopped":true}');
    server.close(() => {record({kind:'exit', stopped:true}); process.exit(0);});
    return;
  }
  record({kind:'http', method:req.method, path:req.url});
  const mode = JSON.parse(fs.readFileSync(cfg.bodyFile, 'utf8'));
  res.writeHead(mode.status, {'content-type':mode.contentType});
  setTimeout(() => res.end(mode.body), mode.delayMs || 0);
});
server.listen(cfg.port, '127.0.0.1', () => fs.writeFileSync(cfg.pidFile, String(process.pid)));
setTimeout(() => {record({kind:'expiry'}); server.close(() => process.exit(3));}, 480000).unref();
