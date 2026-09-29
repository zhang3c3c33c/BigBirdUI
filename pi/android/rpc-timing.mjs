import { displayEvent } from './display-projection.mjs';

/** Project complete RPC records for the UI without changing write callbacks/backpressure. */
export function timedRpcWriter(write, now = Date.now) {
  return (chunk, ...args) => {
    if (typeof chunk === 'string' && chunk.startsWith('{') && chunk.endsWith('}\n') &&
        chunk.indexOf('\n') === chunk.length - 1) {
      const emittedAt = now();
      try {
        const event = JSON.parse(chunk);
        if (typeof event.type === 'string') {
          chunk = JSON.stringify({ ...displayEvent(event), piEmittedAtMs: event.piEmittedAtMs ?? emittedAt }) + '\n';
        }
      } catch { /* Non-RPC output passes through unchanged. */ }
    }
    return write(chunk, ...args);
  };
}
