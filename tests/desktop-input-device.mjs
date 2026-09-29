// Opt-in manual-control timing through the real Electron IPC, with isolated settings.
// Taps only the status-bar corner; no model API requests or app-content input.
import { _electron as electron } from '../android/chat-ui/node_modules/playwright/index.mjs';
import { mkdtemp, writeFile } from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';

const serial = process.env.BBUI_SMOKE_DEVICE;
if (!serial) throw new Error('Set BBUI_SMOKE_DEVICE explicitly');
const data = await mkdtemp(path.resolve('runs/desktop-input-ipc-'));
const executable = process.argv[2];
const env = { ...process.env, BBUI_DESKTOP_DATA: data }; delete env.ELECTRON_RUN_AS_NODE;
const application = await electron.launch({ executablePath: executable || path.resolve('node_modules/electron/dist/electron.exe'),
  args: executable ? [] : [path.resolve('.desktop/app')], env, timeout: 30000 });
try {
  const page = await application.firstWindow(); page.setDefaultTimeout(40000);
  await page.getByRole('heading', { name: '开始使用 BBUI' }).waitFor();
  await page.evaluate(async serial => {
    window.addEventListener('bbui-message', event => { if (event.detail?.type === 'snapshot') window.inputSnapshot = event.detail; });
    const settings = await window.Desktop.invoke('settings');
    await window.Desktop.invoke('saveSettings', { ...settings, serial,
      connections: [{ id: 'fixture', name: 'Fixture', provider: 'bbui', api: 'openai-completions', baseUrl: 'http://127.0.0.1:9/v1', apiKey: 'fixture-only', models: [{ id: 'fixture', input: ['text'] }] }],
      defaultModel: { connectionId: 'fixture', modelId: 'fixture', thinkingLevel: '' },
    });
    await window.Desktop.invoke('connect');
  }, serial);
  await page.getByRole('button', { name: '开始使用', exact: true }).click();
  await page.getByAltText('main 实时画面').waitFor();
  const pane = page.locator('.phone-pane');
  await pane.getByRole('button', { name: '我来操作', exact: true }).click();
  await pane.getByRole('button', { name: '结束操作', exact: true }).waitFor();
  const samples = await page.evaluate(async () => {
    const samples = [];
    for (let index = 0; index < 5; index++) {
      const frame = await window.Desktop.invoke('frame', { screen: 'main' });
      const begin = performance.now();
      const result = await window.Desktop.invoke('input', { operation: '点击', params: { 位置: [5, 5] }, screen: 'main',
        frameId: frame.frameId, controlId: window.inputSnapshot.control.id, actionId: crypto.randomUUID() });
      if (result.错误 || result.执行?.状态 !== '已派发') throw new Error(result.错误 || 'Input not dispatched');
      samples.push(performance.now() - begin);
    }
    return samples;
  });
  assert.equal(samples.length, 5);
  const max = Number(process.env.BBUI_INPUT_MAX_MS || 0);
  if (max) assert.ok(samples.every(value => value < max), `Input timings exceed ${max}ms: ${samples}`);
  await pane.getByRole('button', { name: '结束操作', exact: true }).click();
  await pane.getByRole('button', { name: '我来操作', exact: true }).waitFor();
  await writeFile(path.join(data, 'result.json'), JSON.stringify({ roundTripMs: samples }, null, 2));
  console.log(JSON.stringify({ data, roundTripMs: samples }));
} finally {
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().forEach(window => window.close())).catch(() => {});
  await application.close();
}
