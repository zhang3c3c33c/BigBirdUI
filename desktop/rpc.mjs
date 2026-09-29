import { randomUUID } from 'node:crypto';
import { EventEmitter } from 'node:events';
import path from 'node:path';

export class PiProcess extends EventEmitter {
  constructor(platform, config, home) {
    super();
    this.pending = new Map(); this.closed = false;
    // Host capabilities and inherited cloud credentials never enter the Pi child.
    const env = Object.fromEntries(['PATH', 'SystemRoot', 'TEMP', 'TMP', 'USERPROFILE', 'LOCALAPPDATA', 'APPDATA']
      .filter(key => process.env[key]).map(key => [key, process.env[key]]));
    this.child = platform.spawn(platform.resource('node'), [path.join(platform.resource('pi'), 'bootstrap.mjs')], {
      cwd: home, env: { ...env, PI_CODING_AGENT_DIR: home, BBUI_BOOTSTRAP_CONFIG: JSON.stringify(config) }, stdio: ['pipe', 'pipe', 'pipe'],
    });
    this.exit = new Promise(resolve => this.child.once('close', () => {
      this.closed = true;
      for (const request of this.pending.values()) request.reject(new Error('Pi 进程已结束'));
      this.pending.clear(); this.emit('closed'); resolve();
    }));
    this.child.on('error', error => this.emit('failure', error));
    this.child.stderr.on('data', () => {});
    let buffer = '';
    this.child.stdout.setEncoding('utf8');
    this.child.stdout.on('data', chunk => {
      buffer += chunk;
      for (let at; (at = buffer.indexOf('\n')) >= 0;) {
        const line = buffer.slice(0, at); buffer = buffer.slice(at + 1);
        let event; try { event = JSON.parse(line); } catch { continue; }
        if (event.type === 'response' && this.pending.has(event.id)) {
          const request = this.pending.get(event.id); this.pending.delete(event.id);
          if (request.timedOut) { void Promise.resolve().then(() => request.onLateResponse(event.success === true)).catch(() => {}); continue; }
          event.success ? request.resolve(event.data) : request.reject(Object.assign(new Error(event.error), { responseReceived: true }));
        } else if (event.type === 'runtime_error') this.emit('failure', new Error(event.message));
        else this.emit('event', event);
      }
    });
  }
  request(command, timeout = 30000, { onLateResponse } = {}) {
    if (this.closed) return Promise.reject(new Error('Pi 已关闭'));
    const id = randomUUID();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (onLateResponse) this.pending.get(id).timedOut = true;
        else this.pending.delete(id);
        reject(new Error('Pi 请求超时'));
      }, timeout);
      this.pending.set(id, { onLateResponse, resolve: value => { clearTimeout(timer); resolve(value); }, reject: error => { clearTimeout(timer); reject(error); } });
      this.child.stdin.write(JSON.stringify({ ...command, id }) + '\n');
    });
  }
  async close() {
    if (this.closed) return;
    this.child.stdin.end();
    const timer = setTimeout(() => this.child.kill(), 5000);
    await this.exit; clearTimeout(timer);
  }
}
