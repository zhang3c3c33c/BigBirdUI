import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { chromium } from '../android/chat-ui/node_modules/playwright/index.mjs';
import { initialSnapshot } from '../android/chat-ui/src/contract.ts';

test('welcome gates chat on model and phone, starts explicitly, and preserves draft on fallback', async () => {
  const directory = path.resolve('.desktop/app/ui');
  const server = createServer(async (req, res) => {
    const file = path.resolve(directory, '.' + (req.url === '/' ? '/desktop.html' : req.url));
    if (!file.startsWith(directory + path.sep)) { res.writeHead(403).end(); return; }
    try {
      res.setHeader('Content-Type', file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html');
      res.end(await readFile(file));
    } catch { res.writeHead(404).end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
    page.setDefaultTimeout(7000);
    await page.addInitScript(snapshot => {
      window.snapshot = snapshot; window.commands = []; window.calls = [];
      window.publish = () => window.dispatchEvent(new CustomEvent('bbui-message', { detail: { ...window.snapshot, revision: ++window.snapshot.revision } }));
      window.BBUI = { postMessage: raw => {
        const command = JSON.parse(raw); window.commands.push(command);
        if (command.type === 'ready') setTimeout(window.publish, 60);
        if (command.type === 'viewState') window.snapshot.viewState = command;
      } };
      window.Desktop = { invoke: async (operation, params) => {
        window.calls.push(operation);
        if (operation === 'settings') return { serial: 'fixture', blocked_packages: [], connections: [], floatingStop: false, tools: { search: { provider: '', apiKey: '' } } };
        if (operation === 'devices') return { devices: [{ serial: 'fixture', label: '测试手机', devicePlatform: 'android' }], errors: [] };
        if (operation === 'connect') { window.snapshot.desktop.connected = true; window.snapshot.desktop.deviceName = params.label; window.publish(); return {}; }
        if (operation === 'frame') return null;
        throw new Error('Unexpected operation: ' + operation);
      } };
    }, { ...initialSnapshot, revision: 0, sessionId: 'fixture', sessionsReady: true,
      sessions: [{ id: 'fixture', title: '已保存的会话' }], viewState: { sessionId: 'fixture', text: '', scrollTop: 0 },
      control: { id: 'control', mode: 'idle', canResume: false },
      desktop: { connected: false, configured: false, screens: [{ 屏幕会话: 'main', 显示屏编号: 0 }] } });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    const welcome = page.getByRole('heading', { name: '开始使用 BBUI' });
    const start = page.getByRole('button', { name: '开始使用', exact: true });
    await welcome.waitFor();
    assert.equal(await page.evaluate(() => window.commands.filter(c => c.type === 'ready').length), 1, 'App sends ready without mounting Chat');
    assert.equal(await page.locator('.chat-shell,.session-sidebar,.phone-pane').count(), 0);
    assert.equal(await start.isDisabled(), true);
    await page.getByRole('button', { name: '连接手机' }).click();
    await page.getByRole('button', { name: '刷新设备' }).waitFor();
    assert.equal(await page.getByRole('button', { name: '保存设置' }).count(), 0);
    await page.getByRole('button', { name: '完成', exact: true }).click();
    await page.getByRole('button', { name: '添加模型', exact: true }).click();
    await page.getByRole('button', { name: '添加连接', exact: true }).waitFor();
    await page.getByRole('button', { name: '完成', exact: true }).click();
    if (process.env.BBUI_WELCOME_SCREENSHOT) await page.screenshot({ path: process.env.BBUI_WELCOME_SCREENSHOT });
    await page.getByRole('button', { name: '连接手机', exact: true }).click();
    await page.getByRole('button', { name: '刷新设备' }).click();
    await page.getByRole('button', { name: '连接', exact: true }).click();
    await page.getByRole('button', { name: '完成', exact: true }).click();
    await page.getByText('已连接 · 测试手机', { exact: true }).waitFor();
    assert.equal(await start.isDisabled(), true, 'phone alone is insufficient');
    await page.evaluate(() => { window.snapshot.desktop.configured = true; window.publish(); });
    await page.getByRole('button', { name: '管理模型' }).waitFor();
    assert.equal(await start.isEnabled(), true);
    assert.equal(await page.locator('.chat-shell').count(), 0, 'both ready still requires explicit start');
    await start.click();
    const composer = page.getByRole('textbox', { name: '消息', exact: true });
    await composer.fill('恢复后保留这段草稿');
    await page.evaluate(() => { window.snapshot.desktop.configured = false; window.snapshot.isRunning = true; window.publish(); });
    await welcome.waitFor();
    assert.equal(await page.locator('.chat-shell,.phone-pane').count(), 0);
    await page.getByRole('button', { name: '停止任务', exact: true }).click();
    assert.equal(await page.evaluate(() => window.commands.at(-1).type), 'stop');
    assert.equal(await page.evaluate(() => window.snapshot.viewState.text), '恢复后保留这段草稿', 'unmount flushes the edit even before the draft debounce expires');
    await page.evaluate(() => { window.snapshot.isRunning = false; window.snapshot.desktop.configured = true; window.publish(); });
    await start.click();
    await composer.waitFor();
    assert.equal(await composer.inputValue(), '恢复后保留这段草稿');
    await page.evaluate(() => { window.snapshot.desktop.connected = false; window.publish(); });
    await welcome.waitFor();
    assert.equal(await start.isDisabled(), true, 'model alone is insufficient');
    await page.getByRole('button', { name: '连接手机', exact: true }).click();
    assert.equal(await page.locator('.chat-shell').count(), 0, 'reconnection must not automatically reenter or resume');
    assert.equal(await page.evaluate(() => window.commands.some(c => ['send', 'resumeTask', 'takeOver'].includes(c.type))), false);
    assert.equal(await page.evaluate(() => window.calls.some(c => /test|discover/i.test(c))), false, 'welcome never probes an API automatically');
  } finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
});
