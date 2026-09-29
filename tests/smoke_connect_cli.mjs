/** Opt-in native slash-command smoke; deliberately uses no model API. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

if (!process.argv.includes('--device')) throw new Error('Pass --device to connect the real phone.');
const root = fileURLToPath(new URL('..', import.meta.url));
const child = spawn(process.execPath, [join(root, 'pi/launch.mjs'), '--offline', '--no-session',
  '--no-extensions', '--no-skills', '--no-context-files', '--mode', 'rpc'],
  { cwd: root, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
const exited = once(child, 'exit');
let buffer = '', stderr = '';
child.stderr.on('data', value => { stderr += value; });
let timer;
try {
  const notification = await new Promise((resolve, reject) => {
    timer = setTimeout(() => reject(new Error('Connection command timed out')), 45000);
    child.on('error', reject);
    child.on('exit', code => reject(new Error(`Early exit ${code}: ${stderr}`)));
    child.stdout.on('data', chunk => {
      buffer += chunk;
      for (;;) {
        const end = buffer.indexOf('\n');
        if (end < 0) break;
        const line = buffer.slice(0, end); buffer = buffer.slice(end + 1);
        let event; try { event = JSON.parse(line); } catch { continue; }
        if (event.type === 'agent_start') reject(new Error('Slash command unexpectedly invoked model'));
        if (event.type === 'extension_ui_request' && event.method === 'notify') resolve(event);
      }
    });
    child.stdin.write(JSON.stringify({ id: 'connect', type: 'prompt', message: '/phone-connect' }) + '\n');
  });
  assert.equal(notification.notifyType, 'info', JSON.stringify(notification));
  assert.match(notification.message, /主屏只读镜像已打开/);
  console.log(notification.message);
} finally {
  clearTimeout(timer);
  child.stdin.end();
  const exitTimer = setTimeout(() => child.kill(), 10000);
  await exited;
  clearTimeout(exitTimer);
}
