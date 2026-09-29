// Opt-in, isolated settings: observes the phone without submitting any task or input.
import { _electron as electron } from '../android/chat-ui/node_modules/playwright/index.mjs';
import { mkdtemp, readFile } from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';

const serial = process.env.BBUI_SMOKE_DEVICE;
if (!serial) throw new Error('Set BBUI_SMOKE_DEVICE explicitly');
const data = await mkdtemp(path.resolve('runs/desktop-onboarding-device-'));
const executable = process.argv[2];
const env = { ...process.env, BBUI_DESKTOP_DATA: data }; delete env.ELECTRON_RUN_AS_NODE;
const application = await electron.launch({ executablePath: executable || path.resolve('node_modules/electron/dist/electron.exe'),
  args: executable ? [] : [path.resolve('.desktop/app')], env, timeout: 30000 });
try {
  const page = await application.firstWindow(); page.setDefaultTimeout(40000);
  const welcome = page.getByRole('heading', { name: '开始使用 BBUI' });
  const start = page.getByRole('button', { name: '开始使用', exact: true });
  await welcome.waitFor();
  assert.equal(await start.isDisabled(), true);
  assert.equal(await page.locator('.chat-shell,.phone-pane').count(), 0);
  await page.evaluate(async serial => {
    const settings = await window.Desktop.invoke('settings');
    await window.Desktop.invoke('saveSettings', { ...settings, serial });
    await window.Desktop.invoke('connect');
  }, serial);
  await page.getByRole('status').filter({ hasText: /^已连接/ }).waitFor();
  assert.equal(await start.isDisabled(), true, 'connected phone alone stays on welcome');
  await page.screenshot({ path: path.join(data, 'welcome.png') });
  await page.evaluate(async () => {
    const settings = await window.Desktop.invoke('settings');
    await window.Desktop.invoke('saveSettings', { ...settings,
      connections: [{ id: 'fixture', name: 'Fixture', provider: 'bbui', api: 'openai-completions', baseUrl: 'http://127.0.0.1:9/v1', apiKey: 'fixture-only', models: [{ id: 'fixture', input: ['text'] }] }],
      defaultModel: { connectionId: 'fixture', modelId: 'fixture', thinkingLevel: '' },
    });
  });
  await page.getByRole('button', { name: '管理模型' }).waitFor();
  assert.equal(await start.isEnabled(), true);
  assert.equal(await page.locator('.chat-shell').count(), 0);
  await start.click();
  await page.getByAltText('main 实时画面').waitFor();
  const composer = page.getByRole('textbox', { name: '消息', exact: true });
  await composer.fill('欢迎页回退后保留的草稿');
  await page.evaluate(async () => {
    const settings = await window.Desktop.invoke('settings');
    await window.Desktop.invoke('saveSettings', { ...settings, defaultModel: null });
  });
  await welcome.waitFor();
  assert.equal(await start.isDisabled(), true);
  await page.evaluate(async () => {
    const settings = await window.Desktop.invoke('settings');
    await window.Desktop.invoke('saveSettings', { ...settings, defaultModel: { connectionId: 'fixture', modelId: 'fixture', thinkingLevel: '' } });
  });
  await start.click();
  await composer.waitFor();
  assert.equal(await composer.inputValue(), '欢迎页回退后保留的草稿');
  await page.evaluate(() => window.Desktop.invoke('disconnect'));
  await welcome.waitFor();
  assert.equal(await start.isDisabled(), true, 'configured model alone stays on welcome');
  assert.equal(await page.locator('.chat-shell,.phone-pane').count(), 0);
} finally {
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().forEach(window => window.close())).catch(() => {});
  await application.close();
}
const state = JSON.parse(await readFile(path.join(data, 'state.json'), 'utf8'));
assert.equal(state.paused, true);
assert.equal(state.queue.length, 0);
assert.ok(!state.active);
console.log(JSON.stringify({ result: 'Welcome readiness, explicit start, draft persistence and disconnect: PASS', data }));
