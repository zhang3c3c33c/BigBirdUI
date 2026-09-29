import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { chromium } from '../android/chat-ui/node_modules/playwright/index.mjs';
import { initialSnapshot } from '../android/chat-ui/src/contract.ts';

async function fixture(run) {
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
    page.setDefaultTimeout(5000);
    await page.addInitScript(() => {
      window.commands = []; window.calls = []; window.frames = 0;
      window.BBUI = { postMessage: raw => {
        const command = JSON.parse(raw); window.commands.push(command);
        if (command.type === 'ready' && window.snapshot) window.dispatchEvent(new CustomEvent('bbui-message', { detail: window.snapshot }));
      } };
      window.Desktop = { invoke: async (operation, params) => {
        window.calls.push({ operation, params });
        if (operation === 'settings') return window.saved;
        if (operation === 'devices') return { devices: window.devices };
        if (operation === 'frame') {
          window.frames++;
          await new Promise(resolve => setTimeout(resolve, 30));
          if (window.failPreview) throw new Error('Screenshot timeout');
          return { frameId: String(window.frames), screen: 'main', width: 750, height: 1334,
            image: 'data:image/svg+xml;base64,' + btoa('<svg xmlns="http://www.w3.org/2000/svg" width="750" height="1334"><rect width="100%" height="100%" fill="green"/></svg>') };
        }
      } };
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await run(page);
  } finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
}

const state = platform => ({ ...initialSnapshot, revision: 1, sessionId: 's', sessionsReady: true,
  sessions: [{ id: 's', title: '保留的会话', modified: 0, running: false, queued: 0, state: 'stopped' }],
  control: { id: 'c', mode: 'stopped', canResume: true },
  desktop: { connected: true, configured: true, devicePlatform: platform, deviceId: `${platform}:original`, deviceName: '我的手机', connectionStatus: { code: 'ready', message: '已连接' } },
  messages: [{ id: 'a', role: 'assistant', status: 'complete', parts: [{ id: 'p', type: 'text', state: 'complete', text: '保留的回复' }] }],
});
async function publish(page, value) {
  await page.evaluate(detail => { window.snapshot = detail; window.dispatchEvent(new CustomEvent('bbui-message', { detail })); }, value);
}

for (const platform of ['android', 'ios']) {
  test(`${platform}: preview errors stay local; reconnect keeps chat, draft and STOP; exhausted attempts use welcome`, async () => fixture(async page => {
    let value = state(platform);
    await publish(page, value);
    await page.getByRole('button', { name: '开始使用', exact: true }).click();
    await page.getByText('保留的回复', { exact: true }).waitFor();
    await page.getByRole('textbox', { name: '消息', exact: true }).fill('保留草稿');
    await page.evaluate(() => { window.failPreview = true; });
    await page.locator('.phone-pane').getByText('画面已断开 · 正在重试').waitFor();
    assert.equal(await page.locator('.session-error').count(), 0);
    assert.equal(await page.locator('.desktop-welcome').count(), 0);
    await page.evaluate(() => { window.failPreview = false; });
    await page.waitForFunction(() => !document.querySelector('.phone-pane .desktop-error'));

    value = { ...value, revision: 2, queuePaused: true, queue: [{ id: 'q', sessionId: 's', text: '稍后执行' }],
      desktop: { ...value.desktop, connected: false, connectionStatus: { code: 'reconnecting', message: '正在重连 1/3' } } };
    await publish(page, value);
    await page.locator('.global-execution').getByText('正在重连 1/3').waitFor();
    assert.equal(await page.locator('.desktop-welcome').count(), 0);
    assert.equal(await page.getByRole('textbox', { name: '消息', exact: true }).inputValue(), '保留草稿');
    assert.equal(await page.getByRole('button', { name: '发送消息', exact: true }).isDisabled(), true);
    assert.equal(await page.getByRole('button', { name: '继续队列', exact: true }).isDisabled(), true);
    assert.equal(await page.getByRole('button', { name: '复制回复' }).isEnabled(), true);
    assert.equal(await page.locator('.phone-controls button').isDisabled(), true);
    await page.getByRole('button', { name: '停止', exact: true }).click();
    assert.equal(await page.evaluate(() => window.commands.at(-1).type), 'stop');
    await page.getByRole('button', { name: '手机画面', exact: true }).click();
    assert.equal(await page.locator('.phone-pane').isHidden(), true);

    value = { ...value, revision: 3, desktop: { ...value.desktop, connected: true, connectionStatus: { code: 'ready', message: '已连接' } } };
    await publish(page, value);
    await page.getByRole('button', { name: '发送消息', exact: true }).waitFor();
    assert.equal(await page.getByRole('textbox', { name: '消息', exact: true }).inputValue(), '保留草稿');
    assert.equal(await page.locator('.desktop-welcome').count(), 0);
    assert.equal(await page.evaluate(() => window.commands.some(c => ['send', 'resumeTask', 'resumeQueue'].includes(c.type))), false);

    value = { ...value, revision: 4, desktop: { ...value.desktop, connected: false, connectionStatus: { code: 'connection_failed', message: 'USB 设备已断开' } } };
    await publish(page, value);
    await page.getByRole('heading', { name: '开始使用 BBUI' }).waitFor();
    await page.getByText('USB 设备已断开', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '连接手机', exact: true }).count(), 1);
    assert.equal(await page.getByRole('button', { name: /重新连接|恢复设备连接/ }).count(), 0);
    assert.equal(await page.getByRole('button', { name: '开始使用', exact: true }).isDisabled(), true);
  }));

  test(`${platform}: welcome does not auto-enter after reconnect; queued task permits only original-device reconnect`, async () => fixture(async page => {
    const value = state(platform);
    await page.evaluate(platform => {
      window.saved = { devicePlatform: platform, serial: 'original', deviceName: '我的手机', connections: [], tools: { search: {} } };
      window.devices = [{ serial: 'other', label: '另一台手机', devicePlatform: platform }];
    }, platform);
    await publish(page, { ...value, desktop: { ...value.desktop, connected: false, connectionStatus: { code: 'reconnecting', message: '正在重连 2/3' } } });
    await page.getByText('正在重连 2/3', { exact: true }).waitFor();
    await publish(page, { ...value, revision: 2 });
    assert.equal(await page.locator('.desktop-welcome').count(), 1);
    await publish(page, { ...value, revision: 3, queuePaused: true, queue: [{ id: 'q', sessionId: 's', text: '稍后执行' }], desktop: { ...value.desktop, connected: false, connectionStatus: { code: 'connection_failed', message: '设备断开' } } });
    await page.getByRole('button', { name: '连接手机', exact: true }).click();
    await page.getByRole('button', { name: '刷新设备', exact: true }).click();
    assert.equal(await page.getByRole('button', { name: '连接', exact: true }).isDisabled(), true, 'one unrelated phone must not be auto-selected');
    assert.equal(await page.getByRole('button', { name: 'Android', exact: true }).isDisabled(), true);
    assert.equal(await page.getByRole('button', { name: 'iPhone', exact: true }).isDisabled(), true);
    await page.evaluate(platform => { window.devices.push({ serial: 'original', label: '我的手机', devicePlatform: platform }); }, platform);
    await page.getByRole('button', { name: '刷新设备', exact: true }).click();
    assert.equal(await page.getByRole('combobox', { name: '设备' }).isDisabled(), true);
    await page.getByRole('button', { name: '连接', exact: true }).click();
    assert.deepEqual(await page.evaluate(() => window.calls.find(c => c.operation === 'connect').params), { devicePlatform: platform, serial: 'original', label: '我的手机' });
    await page.getByRole('button', { name: '数据与诊断', exact: true }).click();
    assert.equal(await page.getByRole('button', { name: '恢复设备连接' }).count(), 0);
  }));
}
