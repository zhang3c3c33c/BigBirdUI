import { app, BrowserWindow, ipcMain, safeStorage, dialog, shell, Menu } from 'electron';
import { fileURLToPath } from 'node:url';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import { windowsPlatform } from './platform.mjs';
import { DesktopHost } from './host.mjs';
import { PiProcess } from './rpc.mjs';
import { discoverDevices } from './devices.mjs';
import { discoverModels } from './model-discovery.mjs';
import { APP_NAME, configureAppIdentity } from './branding.mjs';

const root = path.dirname(fileURLToPath(import.meta.url));
configureAppIdentity(app);
if (!app.requestSingleInstanceLock()) app.quit();
else app.whenReady().then(async () => {
  const platform = await windowsPlatform(app, safeStorage, root);
  const releaseSupervisor = await platform.supervise();
  const host = new DesktopHost(platform);
  let window, stopWindow, exiting = false;
  const options = { preload: path.join(root, 'preload.cjs'), sandbox: true, contextIsolation: true, nodeIntegration: false };
  function secure(win) {
    win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:\/\//.test(url)) void shell.openExternal(url); return { action: 'deny' }; });
    win.webContents.on('will-navigate', event => event.preventDefault());
    win.webContents.session.setPermissionRequestHandler((_wc, _permission, callback) => callback(false));
  }
  function floating() {
    if (!host.settings.floatingStop) { stopWindow?.destroy(); stopWindow = null; return; }
    if (stopWindow) return;
    stopWindow = new BrowserWindow({ width: 170, height: 65, icon: platform.resource('icon'), frame: false, alwaysOnTop: true, resizable: false, skipTaskbar: true, webPreferences: options });
    secure(stopWindow); void stopWindow.loadFile(path.join(root, 'ui', 'stop.html'));
  }
  await host.initialize();
  window = new BrowserWindow({ width: 1400, height: 900, minWidth: 1000, minHeight: 640, icon: platform.resource('icon'), backgroundColor: '#faf9f6', title: APP_NAME, webPreferences: options });
  secure(window); Menu.setApplicationMenu(null);
  const senderAllowed = event => [window, stopWindow].some(win => win && !win.isDestroyed() && event.sender === win.webContents && event.senderFrame === win.webContents.mainFrame);
  host.on('snapshot', value => { if (!window.isDestroyed() && !window.webContents.isDestroyed()) window.webContents.send('bbui-event', value); });
  host.on('failure', error => { if (!window.isDestroyed() && !window.webContents.isDestroyed()) window.webContents.send('bbui-event', { ...host.snapshot(), sessionError: error.message }); });
  host.on('settings', () => window.webContents.send('bbui-settings'));
  host.on('preview', () => window.webContents.send('bbui-preview'));
  host.on('preferences', floating);
  ipcMain.on('bbui-command', (event, message) => {
    if (!senderAllowed(event) || typeof message !== 'string' || message.length > 131072) return;
    try {
      const command = JSON.parse(message);
      if (event.sender === stopWindow?.webContents && command.type !== 'stop') return;
      void host.command(command).catch(error => host.rejectCommand(command, error));
    } catch (error) { host.fail(error); }
  });
  ipcMain.handle('bbui-desktop', async (event, operation, params = {}) => {
    if (!senderAllowed(event) || event.sender !== window.webContents) throw new Error('界面来源无效');
    switch (operation) {
      case 'settings': return host.publicSettings();
      case 'saveSettings': return host.saveSettings(params);
      case 'connect': await host.connect(params); return true;
      case 'disconnect': await host.disconnect(); return true;
      case 'devices': {
        const result = await discoverDevices(platform, params);
        return result;
      }
      case 'frame':
        if (window.isMinimized() || !window.isVisible()) return null;
        return host.deviceControl('frame', { screen: params.screen, afterFrameId: params.afterFrameId });
      case 'input':
        if (host.control.mode !== 'manual' || params.controlId !== host.control.id) throw new Error('请先接管手机');
        return host.deviceControl('input', { ...params, token: host.manualToken });
      case 'recover': return host.recoverDevice();
      case 'memory': return host.memory(params);
      case 'discover': {
        const c = host.settings.connections.find(c => c.id === params.connectionId); if (!c) throw new Error('请先保存连接');
        if (params.api !== c.api || params.baseUrl !== c.baseUrl || params.clearApiKey || (params.apiKey && params.apiKey !== c.apiKey)) throw new Error('请先保存连接修改，再获取模型');
        return discoverModels(c);
      }
      case 'probe': {
        const c = host.settings.connections.find(c => c.id === params.connectionId); if (!c) throw new Error('请先保存连接');
        const model = c.models.find(m => m.id === params.modelId); if (!model) throw new Error('模型不存在');
        await mkdir(path.join(host.data, 'probe'), { recursive: true });
        const worker = new PiProcess(platform, { ...c, ...model, model: model.id, probeOnly: true }, path.join(host.data, 'probe'));
        try {
          return await new Promise((resolve, reject) => { const timer = setTimeout(() => reject(new Error('连接测试超时')), 30000);
            worker.on('event', e => { if (e.type === 'model_probe_result') { clearTimeout(timer); resolve(e); } });
            worker.on('failure', error => { clearTimeout(timer); reject(error); }); });
        } finally { await worker.close(); }
      }
      default: throw new Error('不支持的桌面操作');
    }
  });
  app.on('second-instance', () => { if (window.isMinimized()) window.restore(); window.focus(); });
  window.on('close', event => { if (!exiting) { event.preventDefault(); void shutdown(); } });
  app.on('before-quit', event => { if (!exiting) { event.preventDefault(); void shutdown(); } });
  let shutdownPromise;
  async function shutdown() {
    if (shutdownPromise) return shutdownPromise;
    shutdownPromise = (async () => {
      try { await host.close(); await releaseSupervisor(); } catch (error) { console.error('BBUI shutdown:', error.message); dialog.showErrorBox('退出清理未完成', error.message); }
      exiting = true; stopWindow?.destroy(); app.quit();
    })(); return shutdownPromise;
  }
  await window.loadFile(path.join(root, 'ui', 'desktop.html')); floating();
  if (process.argv.includes('--smoke-exit')) {
    if (process.env.BBUI_SMOKE_DEVICE) {
      await host.saveSettings({ ...host.publicSettings(), serial: process.env.BBUI_SMOKE_DEVICE });
      await host.ensureDevice(); await host.deviceControl('frame', { screen: 'main' });
    }
    setTimeout(() => window.close(), 1000);
  }
}).catch(error => { console.error('BBUI startup:', error.message); dialog.showErrorBox(`${APP_NAME} 启动失败`, error.message); app.exit(1); });
