'use strict';
// Test-only lifecycle hook. DUT bytes remain unchanged; this runs only on teardown.
process.on('message', (message) => {
  if (message === 'round1-owned-teardown') {
    process.emit('SIGTERM');
    if (process.connected) process.disconnect();
  }
});
