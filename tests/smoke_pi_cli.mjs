import { spawn } from 'node:child_process';
import { mkdtemp, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import assert from 'node:assert/strict';
const root = fileURLToPath(new URL('..', import.meta.url));
await mkdir(join(root, 'runs'), { recursive: true });
const config = await mkdtemp(join(root, 'runs', 'pi-cli-'));
const child = spawn(process.execPath, [join(root, 'pi', 'launch.mjs'), '--offline', '--no-session', '--no-extensions', '--mode', 'rpc'], {
  cwd: root, windowsHide: true, env: { ...process.env, PI_CODING_AGENT_DIR: config, PI_TELEMETRY: '0' }, stdio: ['pipe', 'pipe', 'pipe'],
});
let buffer = '', stderr = '';
child.stderr.on('data', chunk => { stderr += chunk; });
const done = new Promise((resolve, reject) => {
  child.on('error', reject);
  child.on('exit', code => reject(new Error(`Pi exited before RPC response: ${code}: ${stderr}`)));
  child.stdout.on('data', chunk => {
    buffer += chunk;
    for (;;) {
      const i = buffer.indexOf('\n');
      if (i < 0) break;
      const line = buffer.slice(0, i); buffer = buffer.slice(i + 1);
      let data; try { data = JSON.parse(line); } catch { continue; }
      if (data.id === 'commands') resolve(data);
    }
  });
});
const timer = setTimeout(() => { console.error('Pi RPC startup timed out'); child.kill(); }, 20000);
child.stdin.write(JSON.stringify({ id: 'commands', type: 'get_commands' }) + '\n');
try {
  const result = await done;
  assert.equal(result.success, true, JSON.stringify(result));
  const names = result.data.commands.map(c => c.name);
  for (const name of ['phone-connect', 'phone-stop', 'phone-resume', 'phone-screens']) assert.ok(names.includes(name), JSON.stringify(result));
  console.log('Native Pi CLI loaded BBUI extension and all 4 commands; no model API invoked.');
} finally {
  clearTimeout(timer);
  child.stdin.end();
}
