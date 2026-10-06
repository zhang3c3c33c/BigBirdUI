import path from 'node:path';
import { spawn } from 'node:child_process';
import { readJson } from './state.mjs';

// Public code consumes this interface. macOS gets its own manifest and lifecycle adapter.
export async function windowsPlatform(app, safeStorage, root) {
  if (process.platform !== 'win32' || process.arch !== 'x64') throw new Error('此构建仅支持 Windows x64');
  const manifest = await readJson(path.join(root, 'runtime-manifest.json'));
  if (manifest.platform !== 'win32' || manifest.arch !== 'x64') throw new Error('运行时与当前平台不匹配');
  const resource = name => name === 'deviceState' ? path.join(process.env.LOCALAPPDATA || path.join(app.getPath('home'), 'AppData', 'Local'), 'BBUI', 'devices')
    : path.join(root, ...manifest.paths[name].split('/'));
  let protectedKeySaved;
  async function waitForProtectedKey() {
    // Electron 44 stores its DPAPI-protected key in Chromium Local State with a
    // delayed write. Do not acknowledge the first credential save before it exists.
    const file = path.join(app.getPath('sessionData'), 'Local State');
    const deadline = Date.now() + 30000;
    while (!(await readJson(file, {})).os_crypt?.encrypted_key) {
      if (Date.now() >= deadline) throw new Error('系统加密状态尚未保存，请稍后重试');
      await new Promise(resolve => setTimeout(resolve, 200));
    }
  }
  return {
    data: app.getPath('userData'), root, resource,
    spawn: (file, args, options = {}) => spawn(file, args, { ...options, windowsHide: true, shell: false }),
    async seal(value) {
      if (!safeStorage.isEncryptionAvailable()) throw new Error('Windows 系统加密不可用，无法保存密钥');
      const encrypted = safeStorage.encryptString(JSON.stringify(value)).toString('base64');
      protectedKeySaved ||= waitForProtectedKey().catch(error => { protectedKeySaved = null; throw error; });
      await protectedKeySaved;
      return encrypted;
    },
    unseal(value) { return JSON.parse(safeStorage.decryptString(Buffer.from(value, 'base64'))); },
    async supervise() {
      const watcher = spawn(resource('python'), ['-X', 'utf8', '-m', 'bbui.job_watch', String(process.pid)], {
        cwd: resource('pythonSource'), env: { ...process.env, PYTHONPATH: resource('pythonSource') },
        windowsHide: true, shell: false, stdio: ['pipe', 'pipe', 'pipe'],
      });
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => { watcher.kill(); reject(new Error('Windows 进程清理监护未就绪')); }, 10000);
        watcher.stdout.once('data', chunk => { clearTimeout(timer); String(chunk).includes('BBUI_JOB_READY') ? resolve() : reject(new Error('无效监护回执')); });
        watcher.once('error', error => { clearTimeout(timer); reject(error); });
        watcher.once('exit', code => { clearTimeout(timer); reject(new Error(`Windows 进程监护已退出 (${code})`)); });
      });
      watcher.unref(); watcher.stdin.unref(); watcher.stdout.unref(); watcher.stderr.unref();
      return async () => {
        const ended = new Promise(resolve => watcher.once('exit', resolve));
        watcher.stdin.end('R'); await ended;
      };
    },
  };
}

// Linux runs on the host toolchain: the manifest names the python, node, adb and
// asset locations, and lifetime cleanup uses a POSIX process-group watcher
// instead of a Windows Job object.
export async function linuxPlatform(app, safeStorage, root) {
  const manifest = await readJson(path.join(root, 'runtime-manifest.json'));
  if (manifest.platform !== process.platform || manifest.arch !== process.arch) throw new Error('运行时与当前平台不匹配');
  const resource = name => {
    if (name === 'deviceState') return path.join(process.env.XDG_DATA_HOME || path.join(app.getPath('home'), '.local', 'share'), 'BBUI', 'devices');
    const target = manifest.paths[name];
    if (!target) return undefined;
    if (path.isAbsolute(target)) return target;
    // python and adb may name host executables (python3, adb) instead of staged files.
    if ((name === 'python' || name === 'adb') && !target.includes('/')) return target;
    return path.join(root, ...target.split('/'));
  };
  let watcher, warned = false;
  return {
    data: app.getPath('userData'), root, resource,
    spawn(file, args, options = {}) {
      // Every child leads its own process group so the watcher can end its tree.
      const child = spawn(file, args, { ...options, shell: false, detached: true });
      try { watcher?.stdin.write(`${child.pid}\n`); } catch { /* the watcher is already gone */ }
      return child;
    },
    async seal(value) {
      const plain = JSON.stringify(value);
      // Sessions without a keyring (minimal Wayland setups) keep credentials in the
      // 0600 user data file, marked so unseal never guesses at the encoding.
      if (!safeStorage.isEncryptionAvailable()) {
        if (!warned) { warned = true; console.warn('系统密钥环不可用：凭据仅由文件权限保护'); }
        return 'plain:' + Buffer.from(plain).toString('base64');
      }
      return 'safe:' + safeStorage.encryptString(plain).toString('base64');
    },
    unseal(value) {
      return JSON.parse(value.startsWith('plain:') ? Buffer.from(value.slice(6), 'base64')
        : safeStorage.decryptString(Buffer.from(value.slice(5), 'base64')));
    },
    async supervise() {
      watcher = spawn(resource('python'), ['-m', 'bbui.posix_job_watch', String(process.pid)], {
        cwd: resource('pythonSource'), env: { ...process.env, PYTHONPATH: resource('pythonSource') },
        detached: true, shell: false, stdio: ['pipe', 'pipe', 'pipe'],
      });
      watcher.stdin.on('error', () => {});
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => { watcher.kill(); reject(new Error('进程清理监护未就绪')); }, 10000);
        watcher.stdout.once('data', chunk => { clearTimeout(timer); String(chunk).includes('BBUI_JOB_READY') ? resolve() : reject(new Error('无效监护回执')); });
        watcher.once('error', error => { clearTimeout(timer); reject(error); });
        watcher.once('exit', code => { clearTimeout(timer); reject(new Error(`进程监护已退出 (${code})`)); });
      });
      watcher.unref(); watcher.stdin.unref(); watcher.stdout.unref(); watcher.stderr.unref();
      return async () => {
        const ended = new Promise(resolve => watcher.once('exit', resolve));
        watcher.stdin.end('R'); await ended;
      };
    },
  };
}

export { semanticKey } from '../android/chat-ui/src/input.ts';
