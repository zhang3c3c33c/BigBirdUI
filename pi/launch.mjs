// Keep Pi's native CLI, /login, /model, settings and session management intact.
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('..', import.meta.url));
const child = spawn(process.execPath, [
  fileURLToPath(new URL('../node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js', import.meta.url)),
  '--extension', fileURLToPath(new URL('./phone-extension.ts', import.meta.url)),
  '--no-builtin-tools', '--no-skills', '--skill', fileURLToPath(new URL('./skills/phone-operation/SKILL.md', import.meta.url)),
  '--tools', 'phone_action,task_state,read', ...process.argv.slice(2),
], { cwd: root, stdio: 'inherit', windowsHide: true });
child.on('error', error => { console.error(error.message); process.exitCode = 1; });
child.on('exit', code => { process.exitCode = code ?? 1; });
