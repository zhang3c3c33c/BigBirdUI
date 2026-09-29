import { spawn } from 'node:child_process';
import { mkdtemp, readFile } from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
const data = process.env.BBUI_TEST_DATA || await mkdtemp(path.resolve('runs/desktop-exit-'));
let previous;
try { previous = JSON.parse(await readFile(path.join(data, 'state.json'))); } catch (error) { if (error.code !== 'ENOENT') throw error; }
const env = { ...process.env, BBUI_DESKTOP_DATA: data }; delete env.ELECTRON_RUN_AS_NODE;
const executable = process.argv[2] || path.resolve('node_modules/electron/dist/electron.exe');
const args = process.argv[2] ? ['--smoke-exit'] : [path.resolve('.desktop/app'), '--smoke-exit'];
const child = spawn(executable, args, { env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
child.stderr.on('data', data => process.stderr.write(data));
const timer = setTimeout(() => { child.kill(); console.error('Normal close timed out'); process.exitCode = 1; }, 45000);
const code = await new Promise((resolve, reject) => { child.once('close', resolve); child.once('error', reject); });
clearTimeout(timer); assert.equal(code, 0);
const state = JSON.parse(await readFile(path.join(data, 'state.json')));
assert.equal(state.paused, true); assert.equal(state.queue.length, 0);
if (previous) { assert.equal(state.selected, previous.selected); assert.deepEqual(state.views, previous.views); }
console.log('Normal window close, persisted state and process exit: PASS');
