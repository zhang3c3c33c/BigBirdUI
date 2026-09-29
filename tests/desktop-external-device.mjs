// Opt-in real-device viewer test; never injects input into the existing external display.
import { _electron as electron } from '../android/chat-ui/node_modules/playwright/index.mjs';
import { mkdtemp, readFile } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';
import assert from 'node:assert/strict';

const serial = process.env.BBUI_SMOKE_DEVICE;
if (!serial) throw new Error('Set BBUI_SMOKE_DEVICE; an external virtual display must already exist');
const data = await mkdtemp(path.resolve('runs/desktop-external-ui-'));
const executable = process.argv[2];
const root = executable ? path.join(path.dirname(path.resolve(executable)), 'resources/app') : path.resolve('.desktop/app');
const manifest = JSON.parse(await readFile(path.join(root, 'runtime-manifest.json'), 'utf8'));
const env = { ...process.env, BBUI_DESKTOP_DATA: data }; delete env.ELECTRON_RUN_AS_NODE;
const application = await electron.launch({ executablePath: executable || path.resolve('node_modules/electron/dist/electron.exe'),
  args: executable ? [] : [root], env, timeout: 30000 });
let selected;
try {
  const page = await application.firstWindow(); page.setDefaultTimeout(40000);
  await page.getByRole('heading', { name: '开始使用 BBUI' }).waitFor();
  await page.evaluate(async serial => {
    window.addEventListener('bbui-message', event => { if (event.detail?.type === 'snapshot') window.viewerSnapshot = event.detail; });
    const settings = await window.Desktop.invoke('settings');
    await window.Desktop.invoke('saveSettings', { ...settings, serial,
      connections: [{ id: 'fixture', name: 'Fixture', provider: 'bbui', api: 'openai-completions', baseUrl: 'http://127.0.0.1:9/v1', apiKey: 'fixture-only', models: [{ id: 'fixture', input: ['text'] }] }],
      defaultModel: { connectionId: 'fixture', modelId: 'fixture', thinkingLevel: '' },
    });
    await window.Desktop.invoke('connect');
  }, serial);
  await page.getByRole('button', { name: '开始使用', exact: true }).click();
  await page.waitForFunction(() => window.viewerSnapshot.desktop.screens.some(screen => screen.external));
  selected = await page.evaluate(() => window.viewerSnapshot.desktop.screens.find(screen => screen.external));
  const pane = page.locator('.phone-pane');
  await pane.getByRole('button', { name: '我来操作', exact: true }).click();
  await pane.getByRole('button', { name: '结束操作', exact: true }).waitFor();
  assert.equal(await pane.getByRole('button', { name: '停止', exact: true }).count(), 0);
  assert.equal(await pane.getByRole('button', { name: '全选', exact: true }).count(), 0);
  assert.equal(await pane.getByPlaceholder('输入中文或其他文字').count(), 0);
  for (const label of ['返回', '主页', '最近任务']) {
    const button = pane.getByRole('button', { name: label, exact: true });
    assert.equal((await button.textContent()).trim(), '');
    assert.equal(await button.locator('svg').count(), 1);
  }
  await pane.locator('.phone-navigation').screenshot({ path: path.join(data, 'navigation.png') });
  await pane.getByRole('tab', { name: new RegExp(selected.label) }).click();
  await page.getByAltText(`${selected.屏幕会话} 实时画面`).waitFor();
  await pane.getByText('只读 · 由其他端管理', { exact: true }).waitFor();
  assert.equal(await pane.locator('.phone-primary,.phone-navigation').count(), 0);
  const rejected = await page.evaluate(async screen => {
    const frame = await window.Desktop.invoke('frame', { screen });
    try {
      await window.Desktop.invoke('input', { screen, frameId: frame.frameId, operation: '点击', params: { 位置: [5, 5] },
        controlId: window.viewerSnapshot.control.id, actionId: crypto.randomUUID() });
      return false;
    } catch (error) { return String(error).includes('外部屏幕'); }
  }, selected.屏幕会话);
  assert.equal(rejected, true, 'Host must reject input even with current manual authority');
  await pane.screenshot({ path: path.join(data, 'external.png') });
} finally {
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().forEach(window => window.close())).catch(() => {});
  await application.close();
}
const { stdout } = await promisify(execFile)(path.join(root, manifest.paths.adb), ['-s', serial, 'shell', 'dumpsys', 'display'], { windowsHide: true, maxBuffer: 2 * 1024 * 1024 });
assert.ok(stdout.includes(`mDisplayId=${selected.显示屏编号}`), 'Owner display remains after viewer closes');
console.log(JSON.stringify({ data, displayId: selected.显示屏编号, result: 'External tab, video, icon controls and read-only boundary: PASS' }));
