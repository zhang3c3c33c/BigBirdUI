import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { chromium } from '../android/chat-ui/node_modules/playwright/index.mjs';
import { initialSnapshot } from '../android/chat-ui/src/contract.ts';
import { startPreviewDiagnostics, readPreviewDiagnostics } from './preview-diagnostics.mjs';

test('iPhone preview respects capabilities, cadence, visibility and stale input; settings bind platform with device', async () => {
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
    const context = await browser.newContext({ viewport: { width: 1400, height: 900 } });
    await context.addInitScript(() => {
      window.inputs = []; window.requests = []; window.pending = 0; window.maxPending = 0; window.rotated = false;
      window.settings = { serial: 'same', devicePlatform: 'android', blocked_packages: [], floatingStop: false,
        connections: [{ id: 'saved-model', name: '原名称', provider: 'bbui', api: 'openai-completions', baseUrl: 'https://example.invalid', apiKey: '', models: [] }] };
      window.settingsReads = 0;
      window.scans = []; window.scanFinished = []; window.scanDelay = {}; window.scanErrors = [];
      window.deviceRows = { android: [{ serial: 'same', devicePlatform: 'android', label: 'Android fixture' }], ios: [{ serial: 'same', devicePlatform: 'ios', label: 'iPhone fixture' }] };
      window.BBUI = { postMessage() {} };
      window.Desktop = { invoke: async (operation, params) => {
        if (operation === 'settings') { window.settingsReads++; return window.settings; }
        if (operation === 'devices') {
          window.scans.push(params.devicePlatform);
          await new Promise(resolve => setTimeout(resolve, window.scanDelay[params.devicePlatform] || 0));
          window.scanFinished.push(params.devicePlatform);
          return { devices: window.deviceRows[params.devicePlatform], errors: window.scanErrors };
        }
        if (operation === 'connect') {
          window.connectParams = params;
          window.settings = { ...window.settings, devicePlatform: params.devicePlatform, serial: params.serial, deviceName: params.label };
          window.testSnapshot.desktop = { ...window.testSnapshot.desktop, connected: false, devicePlatform: params.devicePlatform, deviceName: params.label, connectionStatus: { code: 'preparing_support', message: '正在准备开发者支持文件' } };
          window.dispatchEvent(new CustomEvent('bbui-message', { detail: { ...window.testSnapshot, revision: ++window.testSnapshot.revision } }));
          if (window.deferConnection) await new Promise(resolve => { window.finishConnection = resolve; });
          if (window.failConnectionAfterPersist) {
            window.testSnapshot.desktop = { ...window.testSnapshot.desktop, connected: false, connectionStatus: { code: 'connection_failed', message: '连接测试失败' } };
            window.dispatchEvent(new CustomEvent('bbui-message', { detail: { ...window.testSnapshot, revision: ++window.testSnapshot.revision } }));
            throw new Error('连接测试失败');
          }
          window.testSnapshot.desktop = { ...window.testSnapshot.desktop, connected: true, connectionStatus: { code: 'ready', message: '手机已连接' } };
          window.dispatchEvent(new CustomEvent('bbui-message', { detail: { ...window.testSnapshot, revision: ++window.testSnapshot.revision } }));
          return true;
        }
        if (operation === 'saveSettings') { window.saved = params; return params; }
        if (operation === 'input') { window.inputs.push(params); return {}; }
        if (operation === 'frame') {
          window.requests.push(performance.now()); window.pending++; window.maxPending = Math.max(window.maxPending, window.pending);
          await new Promise(resolve => setTimeout(resolve, window.frameDelay ?? 10)); window.pending--;
          const [width, height] = window.rotated ? [1200, 800] : [800, 1200];
          return { frameId: String(window.requests.length), screen: params.screen, width, height,
            image: 'data:image/svg+xml;base64,' + btoa(`<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><rect width="100%" height="100%" fill="green"/></svg>`) };
        }
        return {};
      } };
    });
    const page = await context.newPage(); page.setDefaultTimeout(7000);
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    const state = { ...initialSnapshot, revision: 1, sessionId: 'ios', sessionsReady: true,
      capabilities: { platform: 'windows', devicePlatform: 'ios', embeddedPreview: true, multipleScreens: false, systemTools: true,
        previewMode: 'screenshots', previewFps: 15, appRestrictions: false,
        phoneOperations: ['按键', '点击', '滑动', '长按', '输入内容', '全选'], phoneKeys: ['主页', '最近任务', '回车', '删除'] },
      control: { id: 'control', mode: 'manual', canResume: false },
      desktop: { connected: false, configured: true, devicePlatform: 'ios', deviceId: 'ios:SAME',
        connectionStatus: { code: 'trust_required', message: '请在 iPhone 上信任此电脑' },
        screens: [{ 屏幕会话: 'main', 显示屏编号: 0 }, { 屏幕会话: 'stale-android', 显示屏编号: 9 }] } };
    const publish = async () => page.evaluate(detail => { window.testSnapshot = detail; window.dispatchEvent(new CustomEvent('bbui-message', { detail })); }, state);
    await publish();
    await page.getByText('请在 iPhone 上信任此电脑', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '开始使用', exact: true }).isDisabled(), true);
    assert.equal(await page.getByRole('link', { name: 'Apple 设备安装指南' }).count(), 0);
    state.desktop.connectionStatus = { code: 'driver_missing', message: '未找到 Apple 设备支持组件' };
    state.revision++; await publish();
    await page.getByText('未找到 Apple 设备支持组件', { exact: true }).waitFor();
    const driverHelp = page.getByRole('link', { name: 'Apple 设备安装指南' });
    assert.equal(await driverHelp.getAttribute('href'), 'https://support.apple.com/guide/devices-windows/welcome/windows');
    assert.equal(await driverHelp.getAttribute('target'), '_blank');
    assert.ok((await driverHelp.getAttribute('rel')).includes('noopener'));
    state.desktop.connectionStatus = { code: 'trust_required', message: '请在 iPhone 上信任此电脑' };
    state.revision++; await publish();
    await page.getByRole('button', { name: '连接手机' }).click();
    const dialog = page.getByRole('dialog', { name: '设置' });
    const chooser = dialog.getByRole('combobox', { name: '设备' });
    assert.equal(await dialog.getByRole('button', { name: 'Android', exact: true }).getAttribute('aria-pressed'), 'true');
    assert.equal(await dialog.getByRole('button', { name: '连接', exact: true }).isDisabled(), true);
    assert.equal(await chooser.textContent(), '请先刷新设备⌄');
    assert.equal(await page.evaluate(() => window.scans.length), 0, 'opening settings does not scan automatically');
    state.desktop.connectionStatus = { code: 'preparing_support', message: '正在准备开发者支持文件' }; state.revision++; await publish();
    assert.equal(await dialog.getByRole('button', { name: '正在连接…' }).isDisabled(), true);
    assert.equal(await dialog.getByRole('button', { name: 'iPhone', exact: true }).isDisabled(), true);
    state.desktop.connectionStatus = { code: 'disconnected', message: '未连接' }; state.revision++; await publish();
    await dialog.getByRole('button', { name: 'Android', exact: true }).click();
    await page.evaluate(() => { window.scanDelay.android = 200; });
    await dialog.getByRole('button', { name: '刷新设备' }).click();
    await dialog.getByRole('button', { name: 'iPhone', exact: true }).click();
    await dialog.getByRole('button', { name: '刷新设备' }).click();
    await page.waitForFunction(() => window.scanFinished.includes('android') && window.scanFinished.includes('ios'));
    assert.equal(await chooser.textContent(), 'iPhone fixture⌄', 'late Android result cannot replace iPhone selection');
    await chooser.press('ArrowDown');
    assert.equal(await dialog.getByRole('option', { name: 'iPhone fixture', exact: true }).getAttribute('data-device-id'), 'ios:same');
    await chooser.press('Enter');
    assert.equal(await chooser.getAttribute('aria-expanded'), 'false');
    await page.evaluate(() => { window.deviceRows.ios.push({ serial: 'second', devicePlatform: 'ios', label: 'Second iPhone' }); });
    await dialog.getByRole('button', { name: '刷新设备' }).click();
    await page.waitForFunction(() => window.scanFinished.filter(value => value === 'ios').length >= 2);
    assert.equal(await chooser.textContent(), 'iPhone fixture⌄', 'refresh retains a still-present selection');
    await page.evaluate(() => { window.deviceRows.ios = []; window.scanErrors = [{ devicePlatform: 'ios', code: 'driver_missing', message: '未找到 Apple 设备支持组件' }]; });
    await dialog.getByRole('button', { name: '刷新设备' }).click();
    await page.waitForFunction(() => window.scanFinished.filter(value => value === 'ios').length >= 3);
    assert.equal(await dialog.getByRole('button', { name: '连接', exact: true }).isDisabled(), true);
    assert.equal(await chooser.textContent(), '未找到设备⌄');
    await dialog.getByRole('link', { name: 'Apple 设备安装指南' }).waitFor();
    assert.equal(await dialog.getByRole('alert').textContent(), '未找到 Apple 设备支持组件');
    await page.evaluate(() => { window.deviceRows.ios = [{ serial: 'same', devicePlatform: 'ios', label: 'iPhone fixture' }]; window.scanErrors = []; });
    await dialog.getByRole('button', { name: '刷新设备' }).click();
    await page.waitForFunction(() => window.scanFinished.filter(value => value === 'ios').length >= 4);
    state.queue = [{ id: 'queued', sessionId: 'ios', text: 'queued' }]; state.revision++; await publish();
    assert.equal(await dialog.getByRole('button', { name: 'Android', exact: true }).isDisabled(), true);
    assert.equal(await dialog.getByRole('button', { name: 'Android', exact: true }).getAttribute('aria-pressed'), 'true', 'queue restores the saved target instead of a temporary platform choice');
    assert.equal(await chooser.textContent(), '请先刷新设备⌄', 'restoring the bound platform clears the other platform device list');
    assert.equal(await dialog.getByRole('button', { name: '刷新设备' }).isEnabled(), true, 'a queued task may rediscover its original target');
    assert.equal(await dialog.getByRole('button', { name: '连接', exact: true }).isDisabled(), true);
    state.queue = []; state.revision++; await publish();
    await dialog.getByRole('button', { name: 'iPhone', exact: true }).click();
    await dialog.getByRole('button', { name: '刷新设备' }).click();
    await page.waitForFunction(() => window.scanFinished.filter(value => value === 'ios').length >= 5);
    assert.equal(await dialog.getByRole('link', { name: 'Apple 设备安装指南' }).count(), 0, 'help link is limited to missing driver failures');
    assert.equal(await page.getByLabel('禁止操作的应用（每行一个包名）').count(), 0);
    assert.equal(await page.getByLabel('悬浮停止按钮').count(), 0);
    assert.equal(await dialog.getByRole('button', { name: '保存设置' }).count(), 0);
    await page.evaluate(() => { window.deferConnection = true; window.failConnectionAfterPersist = true; });
    await dialog.getByRole('button', { name: '连接', exact: true }).click();
    await dialog.getByRole('button', { name: '模型', exact: true }).click();
    await dialog.getByRole('textbox', { name: '名称', exact: true }).fill('本地尚未保存的名称');
    await page.evaluate(() => window.finishConnection());
    await page.waitForFunction(() => window.settingsReads >= 2);
    assert.equal(await dialog.getByRole('textbox', { name: '名称', exact: true }).inputValue(), '本地尚未保存的名称', 'failed connection sync preserves dirty model edits');
    await dialog.getByRole('button', { name: '保存设置' }).click();
    assert.equal(await page.evaluate(() => window.saved.devicePlatform), 'ios', 'saving another page does not overwrite the persisted failed target');
    await dialog.getByRole('button', { name: '设备', exact: true }).click();
    assert.equal(await dialog.getByRole('button', { name: 'iPhone', exact: true }).getAttribute('aria-pressed'), 'true');
    await dialog.getByText('连接测试失败', { exact: true }).waitFor();
    await dialog.getByRole('button', { name: '刷新设备' }).click();
    await page.waitForFunction(() => window.scanFinished.filter(value => value === 'ios').length >= 6);
    await page.evaluate(() => { window.failConnectionAfterPersist = false; });
    await dialog.getByRole('button', { name: '连接', exact: true }).click();
    assert.deepEqual(await page.evaluate(() => window.connectParams), { serial: 'same', devicePlatform: 'ios', label: 'iPhone fixture' });
    await dialog.getByRole('button', { name: '模型', exact: true }).click();
    await dialog.getByRole('button', { name: '设备', exact: true }).click();
    assert.equal(await dialog.getByRole('button', { name: 'iPhone', exact: true }).getAttribute('aria-pressed'), 'true');
    assert.equal(await chooser.textContent(), 'iPhone fixture⌄', 'remount while preparing uses the actual connection target');
    await page.evaluate(() => window.finishConnection());
    assert.equal(await dialog.getByRole('button', { name: 'Android', exact: true }).isDisabled(), true);
    assert.equal(await chooser.isDisabled(), true);
    await dialog.getByRole('button', { name: '断开', exact: true }).waitFor();
    assert.equal(await dialog.getByRole('button', { name: 'iPhone', exact: true }).getAttribute('aria-pressed'), 'true');
    await page.getByRole('button', { name: '完成', exact: true }).click();
    state.desktop.connected = true; state.revision++; await publish();
    await page.getByRole('button', { name: '开始使用', exact: true }).click();
    const picture = page.getByAltText('main 实时画面'); await picture.waitFor();
    await startPreviewDiagnostics(page);
    assert.equal(await page.getByRole('tablist', { name: '手机屏幕' }).count(), 0);
    assert.equal(await page.locator('.phone-navigation button').count(), 2);
    assert.equal(await page.locator('.phone-navigation').getByRole('button', { name: '返回', exact: true }).count(), 0);
    await page.locator('.phone-video').focus(); await page.keyboard.press('Escape');
    assert.equal(await page.evaluate(() => window.inputs.length), 0);
    await page.waitForTimeout(500);
    const gaps = await page.evaluate(() => window.requests.slice(1).map((t, i) => t - window.requests[i]));
    assert.ok(gaps.length >= 5); assert.ok(gaps.every(gap => gap >= 55), `15 FPS cadence: ${gaps}`);
    const diagnostics = (await readPreviewDiagnostics(page)).phases.preview;
    assert.ok(diagnostics.committedFps >= 12, JSON.stringify(diagnostics));
    assert.ok(diagnostics.committedFrames > diagnostics.imageLoads, 'unchanged image bytes still commit fresh frame identities');
    assert.equal(diagnostics.nullFrames, 0);
    await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, value: true }); document.dispatchEvent(new Event('visibilitychange')); });
    const hiddenCount = await page.evaluate(() => window.requests.length);
    await page.waitForTimeout(180); assert.equal(await page.evaluate(() => window.requests.length), hiddenCount);
    await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, value: false }); document.dispatchEvent(new Event('visibilitychange')); });
    const box = await picture.boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2); await page.mouse.down();
    await page.evaluate(() => { window.rotated = true; });
    await page.waitForFunction(() => document.querySelector('.phone-video img').naturalWidth === 1200);
    await page.mouse.up(); assert.equal(await page.evaluate(() => window.inputs.length), 0, 'dimension change cancels pending gesture');
    const toggle = page.getByRole('button', { name: '手机画面', exact: true });
    await page.evaluate(() => { window.frameDelay = 200; });
    await page.waitForFunction(() => window.pending === 1);
    await toggle.click(); await toggle.click(); await page.waitForTimeout(500);
    assert.equal(await page.evaluate(() => window.maxPending), 1, 'reopening while a request is pending must not create a second capture');
    await toggle.click(); const collapsedCount = await page.evaluate(() => window.requests.length);
    await page.waitForTimeout(250); assert.equal(await page.evaluate(() => window.requests.length), collapsedCount);
  } finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
});
