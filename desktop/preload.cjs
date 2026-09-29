const { contextBridge, ipcRenderer } = require('electron');
const operations = new Set(['settings', 'saveSettings', 'devices', 'connect', 'disconnect', 'frame', 'input', 'memory', 'discover', 'probe', 'recover']);
contextBridge.exposeInMainWorld('BBUI', { postMessage(message) { ipcRenderer.send('bbui-command', message); } });
contextBridge.exposeInMainWorld('Desktop', {
  invoke(operation, params) {
    if (!operations.has(operation)) return Promise.reject(new Error('Unsupported operation'));
    return ipcRenderer.invoke('bbui-desktop', operation, params);
  },
});
ipcRenderer.on('bbui-event', (_event, value) => window.dispatchEvent(new CustomEvent('bbui-message', { detail: value })));
ipcRenderer.on('bbui-settings', () => window.dispatchEvent(new Event('bbui-settings')));
ipcRenderer.on('bbui-preview', () => window.dispatchEvent(new Event('bbui-preview')));
