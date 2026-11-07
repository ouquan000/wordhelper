'use strict';
// Test-only bootstrap. It is never loaded by the deployable router.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const cp = require('node:child_process');

const router = path.resolve(process.argv[2]);
assert.ok(path.basename(path.dirname(router)).startsWith('wb-router-isolated-'));
const config = JSON.parse(fs.readFileSync(path.join(path.dirname(router), 'router.config.json'), 'utf8'));
let online = false;
cp.execFile = (file, args, options, callback) => {
  assert.equal(file, 'netstat.exe');
  assert.deepEqual(args, ['-ano']);
  const timer = setImmediate(() => callback(null, online ?
    '  TCP  127.0.0.1:' + config.qqPort + '  127.0.0.1:54321  ESTABLISHED  42\r\n' :
    '  TCP  127.0.0.1:' + config.qqPort + '  0.0.0.0:0  LISTENING  42\r\n'));
  return { kill() { clearImmediate(timer); } };
};
const listen = http.Server.prototype.listen;
http.Server.prototype.listen = function (...args) {
  this.once('listening', () => {
    if (process.connected) process.send({ type: 'listening', port: this.address().port });
  });
  return listen.apply(this, args);
};
process.on('message', (message) => {
  if (message.type === 'qq') online = message.online;
  if (message.type === 'stop') {
    process.emit('SIGTERM');
    if (process.connected) process.disconnect();
  }
});
process.on('disconnect', () => process.emit('SIGTERM'));
require(router);
