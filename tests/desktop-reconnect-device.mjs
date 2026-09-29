// Opt-in Electron acceptance: crash only this test's executor, never send phone input.
import assert from 'node:assert/strict';
import { _electron as electron } from '../android/chat-ui/node_modules/playwright/index.mjs';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { createServer } from 'node:http';
import path from 'node:path';

const serial = process.env.BBUI_SMOKE_DEVICE;
const devicePlatform = process.env.BBUI_SMOKE_PLATFORM || 'android';
assert.ok(serial && ['android', 'ios'].includes(devicePlatform), 'Explicit test device required');
const data = await mkdtemp(path.resolve('runs/desktop-auto-reconnect-'));
let modelRequests = 0;
const api = createServer(async (req, res) => {
  let body = ''; for await (const chunk of req) body += chunk;
  const input = JSON.parse(body);
  res.writeHead(200, { 'Content-Type': 'text/event-stream' });
  const chunk = (delta, finish_reason = null) => res.write('data: ' + JSON.stringify({ id: 'mock', object: 'chat.completion.chunk', created: 1, model: 'fixture', choices: [{ index: 0, delta, finish_reason }] }) + '\n\n');
  chunk({ role: 'assistant' });
  if (!input.tools?.length) { chunk({ content: '连接恢复验收' }); chunk({}, 'stop'); res.end('data: [DONE]\n\n'); }
  else { modelRequests++; chunk({ content: '正在等待本地断线验收。' }); }
});
await new Promise(resolve => api.listen(0, '127.0.0.1', resolve));
const env = { ...process.env, BBUI_DESKTOP_DATA: data }; delete env.ELECTRON_RUN_AS_NODE;
const executable = process.argv[2];
const application = await electron.launch({ executablePath: executable || path.resolve('node_modules/electron/dist/electron.exe'),
  args: executable ? [] : [path.resolve('.desktop/app')], env, timeout: 30000 });
let page;
try {
  page = await application.firstWindow(); page.setDefaultTimeout(60000);
  await page.getByRole('heading', { name: '开始使用 BBUI' }).waitFor();
  await page.evaluate(async ({ serial, devicePlatform, apiPort }) => {
    window.reconnectEvents = [];
    window.addEventListener('bbui-message', event => {
      if (event.detail?.type !== 'snapshot') return;
      const s = window.reconnectSnapshot = event.detail;
      const item = { connected: s.desktop?.connected, code: s.desktop?.connectionStatus?.code,
        message: s.desktop?.connectionStatus?.message, running: s.isRunning,
        paused: s.queuePaused, queue: s.queue?.length, session: s.sessionId };
      if (JSON.stringify(window.reconnectEvents.at(-1)) !== JSON.stringify(item)) window.reconnectEvents.push(item);
    });
    const settings = await window.Desktop.invoke('settings');
    await window.Desktop.invoke('saveSettings', { ...settings, serial, devicePlatform,
      connections: [{ id: 'fixture', name: 'Local fixture', provider: 'bbui', api: 'openai-completions', baseUrl: `http://127.0.0.1:${apiPort}/v1`,
        apiKey: 'fixture-only', models: [{ id: 'fixture', input: ['text', 'image'] }] }],
      defaultModel: { connectionId: 'fixture', modelId: 'fixture', thinkingLevel: '' } });
  }, { serial, devicePlatform, apiPort: api.address().port });
  await page.getByRole('button', { name: '连接手机', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '设置', exact: true });
  await dialog.getByRole('button', { name: '刷新设备', exact: true }).click();
  await dialog.getByRole('combobox', { name: '设备', exact: true }).click();
  const identity = devicePlatform === 'ios' ? `ios:${serial.replaceAll('-', '').toUpperCase()}` : `android:${serial}`;
  await dialog.locator(`[role="option"][data-device-id="${identity}"]`).click();
  await dialog.getByRole('button', { name: '连接', exact: true }).click();
  await dialog.getByRole('button', { name: '断开', exact: true }).waitFor({ timeout: 240000 });
  await dialog.getByRole('button', { name: '完成', exact: true }).click();
  await page.getByRole('button', { name: '开始使用', exact: true }).click();
  await page.getByAltText('main 实时画面').waitFor();
  const session = await page.evaluate(() => window.reconnectSnapshot.sessionId);
  const composer = page.getByRole('textbox', { name: '消息', exact: true });
  await composer.fill('断线验收运行项：等待测试终止');
  await page.getByRole('button', { name: '发送消息', exact: true }).click();
  await page.waitForFunction(() => window.reconnectSnapshot.isRunning === true);
  const modelDeadline = Date.now() + 30000;
  while (!modelRequests) { assert.ok(Date.now() < modelDeadline, 'The real Pi process must reach the mock API'); await new Promise(resolve => setTimeout(resolve, 50)); }
  assert.equal(await page.evaluate(() => window.reconnectSnapshot.isRunning), true);
  await composer.fill('断线验收等待项：保持暂停');
  await page.getByRole('button', { name: '加入队列', exact: true }).click();
  await page.waitForFunction(() => window.reconnectSnapshot.queue?.length === 1);
  await composer.fill('重连后保留的草稿');
  const mainPid = await application.evaluate(() => process.pid);
  const module = devicePlatform === 'ios' ? 'bbui.ios_mcp' : 'bbui.desktop_mcp';
  const command = `Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq ${mainPid} -and $_.CommandLine -like '*${module}*' } | Select-Object -ExpandProperty ProcessId`;
  const pids = execFileSync('powershell.exe', ['-NoProfile', '-Command', command], { encoding: 'utf8', windowsHide: true }).trim().split(/\s+/).filter(Boolean).map(Number);
  assert.equal(pids.length, 1, 'Crash only the isolated test executor');
  process.kill(pids[0], 'SIGKILL');
  await page.waitForFunction(() => window.reconnectEvents.some(event => event.code === 'reconnecting'));
  assert.equal(await page.getByRole('heading', { name: '开始使用 BBUI' }).count(), 0);
  await page.screenshot({ path: path.join(data, 'reconnecting.png') });
  await page.waitForFunction(() => window.reconnectSnapshot.desktop?.connected === true, undefined, { timeout: 240000 });
  await page.getByAltText('main 实时画面').waitFor();
  assert.equal(await page.getByRole('heading', { name: '开始使用 BBUI' }).count(), 0);
  assert.equal(await composer.inputValue(), '重连后保留的草稿');
  const result = await page.evaluate(() => ({ snapshot: window.reconnectSnapshot, events: window.reconnectEvents }));
  assert.equal(result.snapshot.sessionId, session);
  assert.equal(result.snapshot.isRunning, false);
  assert.equal(result.snapshot.queue.length, 1);
  assert.equal(result.snapshot.queuePaused, true);
  assert.equal(result.snapshot.control.mode, 'stopped');
  await page.waitForTimeout(3000);
  assert.equal(await page.evaluate(() => window.reconnectSnapshot.isRunning), false);
  assert.equal(modelRequests, 1, 'Do not restart the active request or start queued work');
  await page.screenshot({ path: path.join(data, 'reconnected.png') });
  await writeFile(path.join(data, 'result.json'), JSON.stringify({ devicePlatform, deviceId: identity,
    crashedExecutor: pids[0], retainedSession: true, retainedDraft: true, queuePaused: true, replayed: false,
    modelRequests, events: result.events }, null, 2));
  console.log(JSON.stringify({ result: 'PASS', devicePlatform, data }));
} catch (error) {
  await page?.screenshot({ path: path.join(data, 'failure.png') }).catch(() => {});
  await writeFile(path.join(data, 'failure.txt'), String(error));
  console.error('Acceptance data:', data); throw error;
} finally {
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().forEach(window => window.close())).catch(() => {});
  await application.close();
  api.closeAllConnections(); await new Promise(resolve => api.close(resolve));
}
