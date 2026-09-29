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

export { semanticKey } from '../android/chat-ui/src/input.ts';
