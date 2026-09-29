// Opt-in acceptance through the actual connection UI. No phone input or cloud requests.
import { _electron as electron } from '../android/chat-ui/node_modules/playwright/index.mjs';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';

const serial = process.env.BBUI_SMOKE_DEVICE;
const devicePlatform = process.env.BBUI_SMOKE_PLATFORM || 'android';
assert.ok(serial && ['android', 'ios'].includes(devicePlatform), 'Specify the target USB device');
const deviceId = devicePlatform === 'ios' ? `ios:${serial.replaceAll('-', '').toUpperCase()}` : `android:${serial}`;
const data = await mkdtemp(path.resolve('runs/desktop-connect-device-'));
const env = { ...process.env, BBUI_DESKTOP_DATA: data }; delete env.ELECTRON_RUN_AS_NODE;
const executable = process.argv[2];
const application = await electron.launch({ executablePath: executable || path.resolve('node_modules/electron/dist/electron.exe'),
  args: executable ? [] : [path.resolve('.desktop/app')], env, timeout: 30000 });
let page;
try {
  page = await application.firstWindow(); page.setDefaultTimeout(60000);
  await page.getByRole('heading', { name: '开始使用 BBUI' }).waitFor();
  await page.evaluate(async () => {
    window.addEventListener('bbui-message', event => { if (event.detail?.type === 'snapshot') window.connectionSnapshot = event.detail; });
    const settings = await window.Desktop.invoke('settings');
    await window.Desktop.invoke('saveSettings', { ...settings,
      connections: [{ id: 'fixture', name: 'Offline fixture', provider: 'bbui', api: 'openai-completions', baseUrl: 'http://127.0.0.1:9/v1',
        apiKey: 'fixture-only', models: [{ id: 'fixture', input: ['text', 'image'] }] }],
      defaultModel: { connectionId: 'fixture', modelId: 'fixture', thinkingLevel: '' } });
  });
  const before = await page.evaluate(() => ({ sessionId: window.connectionSnapshot.sessionId }));
  assert.equal(await page.getByRole('button', { name: '设置设备', exact: true }).count(), 0);
  assert.equal(await page.getByRole('button', { name: '连接手机', exact: true }).count(), 1);
  await page.getByRole('button', { name: '连接手机', exact: true }).click();
  const settings = page.getByRole('dialog', { name: '设置', exact: true });
  await settings.waitFor();
  assert.equal(await settings.getByRole('button', { name: '保存设置', exact: true }).count(), 0);
  assert.equal(await settings.getByLabel('禁止操作的应用（每行一个包名）').count(), 0);
  assert.equal(await settings.getByLabel('悬浮停止按钮').count(), 0);
  await settings.getByRole('button', { name: devicePlatform === 'ios' ? 'iPhone' : 'Android', exact: true }).click();
  await settings.getByRole('button', { name: '刷新设备', exact: true }).click();
  const dropdown = settings.getByRole('combobox', { name: '设备', exact: true });
  await dropdown.click();
  await page.screenshot({ path: path.join(data, 'device-options.png') });
  await settings.locator(`[role="option"][data-device-id="${deviceId}"]`).click();
  await settings.getByRole('button', { name: '连接', exact: true }).click();
  await settings.getByRole('button', { name: '断开', exact: true }).waitFor({ timeout: 240000 });
  assert.equal(await settings.getByRole('button', { name: devicePlatform === 'ios' ? 'Android' : 'iPhone', exact: true }).isDisabled(), true);
  await page.screenshot({ path: path.join(data, 'connected-settings.png') });
  const connected = await page.evaluate(async () => ({ settings: await window.Desktop.invoke('settings'), snapshot: window.connectionSnapshot }));
  assert.equal(connected.settings.devicePlatform, devicePlatform);
  assert.equal(connected.settings.serial, serial);
  assert.equal(connected.settings.connections[0].id, 'fixture');
  assert.equal(connected.settings.connections[0].hasKey, true);
  assert.equal(connected.snapshot.sessionId, before.sessionId);
  assert.equal(connected.snapshot.desktop.connected, true);
  await settings.getByRole('button', { name: '完成', exact: true }).click();
  await page.screenshot({ path: path.join(data, 'connected-welcome.png') });
  await page.getByRole('button', { name: '开始使用', exact: true }).click();
  await page.getByAltText('main 实时画面').waitFor();
  const composer = page.getByRole('textbox', { name: '消息', exact: true });
  await composer.fill('切换连接页面后仍保留的草稿');
  await page.getByRole('button', { name: '设置', exact: true }).click();
  await settings.getByRole('button', { name: '断开', exact: true }).click();
  await settings.getByRole('button', { name: '连接', exact: true }).waitFor();
  // Reopening Settings does not silently rediscover or connect the last target.
  await settings.getByRole('button', { name: '刷新设备', exact: true }).click();
  await dropdown.click();
  await settings.locator(`[role="option"][data-device-id="${deviceId}"]`).click();
  await settings.getByRole('button', { name: '连接', exact: true }).click();
  await settings.getByRole('button', { name: '断开', exact: true }).waitFor({ timeout: 240000 });
  await settings.getByRole('button', { name: '完成', exact: true }).click();
  await page.getByRole('button', { name: '开始使用', exact: true }).click();
  await composer.waitFor();
  assert.equal(await composer.inputValue(), '切换连接页面后仍保留的草稿');
  await page.getByRole('button', { name: '设置', exact: true }).click();
  await settings.getByRole('button', { name: '断开', exact: true }).click();
  await settings.getByRole('button', { name: '连接', exact: true }).waitFor();
  await settings.getByRole('button', { name: '完成', exact: true }).click();
  await page.getByRole('heading', { name: '开始使用 BBUI' }).waitFor();
  assert.equal(await page.getByRole('button', { name: '开始使用', exact: true }).isDisabled(), true);
  await writeFile(path.join(data, 'result.json'), JSON.stringify({ devicePlatform, connectedThroughUi: true, disconnectedThroughUi: true,
    modelAndSessionPreserved: true, draftPreserved: true, apiRequests: 0 }, null, 2));
  console.log(JSON.stringify({ result: 'Device platform, refresh, name selection, connect/disconnect and draft: PASS', devicePlatform, data }));
} catch (error) {
  await page?.screenshot({ path: path.join(data, 'failure.png') }).catch(() => {});
  console.error('Acceptance data:', data); throw error;
} finally {
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().forEach(window => window.close())).catch(() => {});
  await application.close();
}
const state = JSON.parse(await readFile(path.join(data, 'state.json'), 'utf8'));
assert.equal(state.paused, true); assert.equal(state.queue.length, 0); assert.ok(!state.active);
