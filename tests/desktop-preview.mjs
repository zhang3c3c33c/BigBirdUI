import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { chromium } from '../android/chat-ui/node_modules/playwright/index.mjs';
import { initialSnapshot } from '../android/chat-ui/src/contract.ts';

test('preview maps input and supports direct keyboard, IME and paste with owned-screen control', async () => {
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
    const context = await browser.newContext({ viewport: { width: 1400, height: 900 }, deviceScaleFactor: 2 });
    await context.addInitScript(() => {
      window.commands = []; window.inputs = []; window.rotated = false; window.rejectInput = false; window.frameDelay = 40;
      window.frameRequests = []; window.pendingFrames = 0; window.maxPendingFrames = 0;
      window.BBUI = { postMessage: raw => window.commands.push(JSON.parse(raw)) };
      window.Desktop = { invoke: async (operation, params) => {
        if (operation === 'input') { window.inputs.push(params); await new Promise(resolve => setTimeout(resolve, window.inputDelay || 0)); return window.rejectInput ? { 错误: '画面已变化' } : {}; }
        if (operation === 'frame') {
          window.frameRequests.push(params);
          window.pendingFrames++;
          window.maxPendingFrames = Math.max(window.maxPendingFrames, window.pendingFrames);
          await new Promise(resolve => setTimeout(resolve, window.frameDelay));
          window.pendingFrames--;
          const [width, height] = window.rotated ? [1200, 800] : [800, 1200];
          const frameId = params.screen + (window.rotated ? ':landscape' : ':portrait');
          if (params.afterFrameId === frameId) return null;
          return { frameId, screen: params.screen, width, height,
            image: 'data:image/svg+xml;base64,' + btoa(`<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><rect width="100%" height="100%" fill="green"/></svg>`) };
        }
      } };
    });
    const page = await context.newPage(); page.setDefaultTimeout(7000);
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.getByRole('heading', { name: '开始使用 BBUI' }).waitFor();
    await page.evaluate(detail => { window.desktopSnapshot = detail; window.dispatchEvent(new CustomEvent('bbui-message', { detail })); }, {
      ...initialSnapshot, revision: 1, sessionId: 'fixture', sessionsReady: true,
      control: { id: 'control', mode: 'manual', canResume: true },
      desktop: { connected: true, configured: true, screens: [{ 屏幕会话: 'main', 显示屏编号: 0 }] },
    });
    await page.getByRole('button', { name: '开始使用', exact: true }).click();
    const picture = page.getByAltText('main 实时画面'); await picture.waitFor();
    await page.waitForFunction(() => window.frameRequests.some(request => request.afterFrameId === 'main:portrait'));
    const toggle = page.getByRole('button', { name: '手机画面', exact: true });
    assert.equal(await toggle.getAttribute('aria-pressed'), 'true');
    assert.equal(await page.locator('.phone-pane select').count(), 0);
    assert.equal(await page.getByRole('button', { name: /新增虚拟屏|放大|还原/ }).count(), 0);
    assert.equal(await page.getByRole('tablist', { name: '手机屏幕' }).count(), 0, 'single screen needs no screen switcher');
    assert.equal(await picture.count(), 1, 'unchanged frame retains the visible image');
    async function checkPreview(width, height) {
      const box = await picture.boundingBox();
      const surround = await page.locator('.phone-video').boundingBox();
      assert.ok(Math.abs(box.width / box.height - width / height) < 0.01, 'image box must preserve the phone aspect ratio');
      assert.ok(box.width <= surround.width + 1 && box.height <= surround.height + 1, 'entire image stays within available space');
      assert.equal(await page.locator('.phone-video').evaluate(element => getComputedStyle(element).backgroundColor), 'rgba(0, 0, 0, 0)', 'surround uses the pane background');
      assert.equal(await picture.evaluate(element => getComputedStyle(element).borderRadius), '8px', 'corners belong to the actual phone image');
      const previous = await page.evaluate(() => window.inputs.length);
      await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
      await page.waitForFunction(count => window.inputs.length === count + 1, previous);
      assert.deepEqual(await page.evaluate(() => window.inputs.at(-1).params.位置), [width / 2, height / 2]);
      const blank = box.y - surround.y > 4
        ? [surround.x + surround.width / 2, surround.y + 2]
        : [surround.x + 2, surround.y + surround.height / 2];
      assert.ok(box.y - surround.y > 4 || box.x - surround.x > 4, 'fixture must have blank surround to exercise');
      await page.mouse.click(...blank);
      assert.equal(await page.evaluate(() => window.inputs.length), previous + 1, 'blank surround must not dispatch');
    }
    await checkPreview(800, 1200);
    assert.equal(await page.evaluate(() => window.inputs[0].controlId), 'control');
    await page.evaluate(() => { window.rotated = true; });
    await page.waitForFunction(() => document.querySelector('.phone-video img')?.naturalWidth === 1200);
    await checkPreview(1200, 800);
    await page.evaluate(() => document.documentElement.style.setProperty('--desktop-right', '650px'));
    await checkPreview(1200, 800);
    await page.evaluate(() => { window.rotated = false; });
    await page.waitForFunction(() => document.querySelector('.phone-video img')?.naturalWidth === 800);
    await checkPreview(800, 1200);
    await page.evaluate(() => document.documentElement.style.setProperty('--desktop-right', '340px'));
    const keyboard = page.getByLabel('手机键盘输入', { exact: true });
    const video = page.locator('.phone-video');
    for (const name of ['返回', '主页', '最近任务']) assert.equal((await page.locator('.phone-navigation').getByRole('button', { name, exact: true }).textContent()).trim(), '', 'navigation uses accessible icons without visible labels');
    assert.equal(await page.getByPlaceholder('输入中文或其他文字').count(), 0);
    assert.equal(await page.locator('.phone-pane').getByRole('button', { name: /^(全选|删除|输入手机|停止|停止任务)$/ }).count(), 0);
    async function paste(value) {
      await keyboard.evaluate((element, value) => { element.focus(); const data = new DataTransfer(); data.setData('text/plain', value); element.dispatchEvent(new ClipboardEvent('paste', { bubbles: true, cancelable: true, clipboardData: data })); }, value);
    }
    await keyboard.focus();
    await page.keyboard.type('hello');
    await page.waitForFunction(() => window.inputs.at(-1)?.params.内容 === 'hello');
    const beforeComposition = await page.evaluate(() => window.inputs.length);
    await keyboard.evaluate(element => {
      element.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
      element.value = '中文输入 👋';
      element.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertCompositionText', isComposing: true, data: element.value }));
    });
    await page.waitForTimeout(130);
    assert.equal(await page.evaluate(() => window.inputs.length), beforeComposition, 'IME interim text must not be sent');
    await keyboard.evaluate(element => element.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: element.value })));
    await page.waitForFunction(() => window.inputs.at(-1)?.params.内容 === '中文输入 👋');
    assert.equal(await page.evaluate(() => window.inputs.length), beforeComposition + 1);
    for (const [key, operation, name] of [['Control+a', '全选'], ['Backspace', '按键', '删除'], ['Delete', '按键', '向前删除'], ['Enter', '按键', '回车'], ['ArrowLeft', '按键', '左'], ['ArrowRight', '按键', '右'], ['ArrowUp', '按键', '上'], ['ArrowDown', '按键', '下']]) {
      await page.waitForFunction(() => !document.querySelector('.phone-navigation button').disabled);
      const previous = await page.evaluate(() => window.inputs.length);
      await page.keyboard.press(key);
      await page.waitForFunction(count => window.inputs.length > count, previous).catch(async error => {
        throw new Error(`Key ${key}: ${await page.locator('.desktop-error').allTextContents()}`, { cause: error });
      });
      const result = await page.evaluate(() => window.inputs.at(-1));
      assert.equal(result.operation, operation); if (name) assert.equal(result.params.键名, name);
    }
    await page.evaluate(() => { window.inputDelay = 200; });
    await paste('第一段');
    await page.waitForFunction(() => window.inputs.at(-1)?.params.内容 === '第一段');
    await page.keyboard.type('second');
    await page.waitForFunction(() => window.inputs.at(-1)?.params.内容 === 'second');
    await page.waitForTimeout(230);
    await page.evaluate(() => { window.inputDelay = 0; });
    const beforeClick = await page.evaluate(() => window.inputs.length);
    await paste('先输入再点击');
    const currentBox = await picture.boundingBox();
    await page.mouse.click(currentBox.x + currentBox.width / 2, currentBox.y + currentBox.height / 2);
    await page.waitForFunction(count => window.inputs.length === count + 2, beforeClick);
    assert.deepEqual(await page.evaluate(count => window.inputs.slice(count).map(item => item.operation), beforeClick), ['输入内容', '点击'], 'pending text must precede a focus-changing click');
    await page.evaluate(() => { window.rejectInput = true; });
    await paste('失败不重放');
    await page.getByRole('alert').filter({ hasText: '画面已变化' }).waitFor();
    const failedInputs = await page.evaluate(() => window.inputs.length);
    const requestsAfterRejection = await page.evaluate(() => window.frameRequests.length);
    await page.evaluate(() => { window.rotated = true; });
    await page.waitForFunction(count => window.frameRequests.length > count && document.querySelector('.phone-video img')?.naturalWidth === 1200, requestsAfterRejection);
    assert.equal(await page.getByRole('alert').textContent(), 'Error: 画面已变化', 'successful preview refresh must not clear rejected input feedback');
    assert.equal(await page.evaluate(() => window.inputs.length), failedInputs, 'failed text must never replay automatically');
    await page.evaluate(() => { window.rejectInput = false; });
    assert.equal(await page.evaluate(() => window.maxPendingFrames), 1, 'slow frame requests cannot overlap or accumulate');
    async function updateSnapshot(change) {
      await page.evaluate(change => { window.desktopSnapshot = { ...window.desktopSnapshot, ...change, desktop: { ...window.desktopSnapshot.desktop, ...change.desktop }, revision: window.desktopSnapshot.revision + 1 }; window.dispatchEvent(new CustomEvent('bbui-message', { detail: window.desktopSnapshot })); }, change);
    }
    await updateSnapshot({ desktop: { connected: true, screens: [{ 屏幕会话: 'main', 显示屏编号: 0 }, { 屏幕会话: 'work', 显示屏编号: 2 }] } });
    await page.evaluate(() => { window.frameDelay = 180; });
    await page.getByRole('tab', { name: '主屏', exact: true }).focus();
    await page.keyboard.press('ArrowRight');
    const workTab = page.getByRole('tab', { name: '任务屏 1（work）', exact: true });
    assert.equal(await workTab.getAttribute('aria-selected'), 'true');
    assert.equal(await picture.count(), 0, 'switching screens must not show the previous screen as the new one');
    await page.getByAltText('work 实时画面').waitFor();
    const beforePendingKey = await page.evaluate(() => window.inputs.length);
    await page.evaluate(() => { window.inputDelay = 200; });
    await paste('切屏前文字');
    await page.keyboard.press('Enter');
    await page.waitForFunction(count => window.inputs.length === count + 1, beforePendingKey);
    await page.getByRole('tab', { name: '主屏', exact: true }).click();
    await picture.waitFor();
    await page.waitForTimeout(230);
    assert.equal(await page.evaluate(() => window.inputs.length), beforePendingKey + 1, 'Enter waiting for text must not cross into a different screen');
    await page.evaluate(() => { window.inputDelay = 0; });
    await workTab.click();
    await page.getByAltText('work 实时画面').waitFor();
    await paste('收起时取消未发送文字');
    const beforeCollapse = await page.evaluate(() => window.inputs.length);
    await toggle.click();
    assert.equal(await toggle.getAttribute('aria-expanded'), 'false');
    assert.equal(await page.locator('.phone-pane').isVisible(), false);
    assert.equal(await page.locator('.session-layout').evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length), 2, 'hidden preview leaves no collapsed rail');
    const collapsedRequests = await page.evaluate(() => window.frameRequests.length);
    await page.waitForTimeout(180);
    assert.equal(await page.evaluate(() => window.frameRequests.length), collapsedRequests, 'collapsed preview stops requesting frames');
    await toggle.click();
    assert.equal(await toggle.getAttribute('aria-pressed'), 'true');
    assert.equal(await workTab.getAttribute('aria-selected'), 'true');
    assert.equal(await keyboard.inputValue(), '');
    assert.equal(await page.evaluate(() => window.inputs.length), beforeCollapse);
    await page.getByRole('alert').filter({ hasText: '未发送文字已取消' }).waitFor();
    await page.getByAltText('work 实时画面').waitFor();
    await updateSnapshot({ desktop: { connected: true, screens: [{ 屏幕会话: 'main', 显示屏编号: 0 }] } });
    assert.equal(await page.getByAltText('work 实时画面').count(), 0, 'removed screen frame is discarded immediately');
    await picture.waitFor();
    assert.equal(await page.getByRole('tablist', { name: '手机屏幕' }).count(), 0);
    const beforeStop = await page.evaluate(() => window.inputs.length);
    await paste('STOP后不发送');
    await updateSnapshot({ control: { id: 'stopped-generation', mode: 'stopped', canResume: false } });
    await page.waitForTimeout(150);
    assert.equal(await page.evaluate(() => window.inputs.length), beforeStop, 'STOP cancels pending text');
    await updateSnapshot({ control: { id: 'control', mode: 'manual', canResume: true } });
    await updateSnapshot({ desktop: { screens: [{ 屏幕会话: 'main', 显示屏编号: 0 }, { 屏幕会话: 'external:9:fixture', 显示屏编号: 9, external: true, readOnly: true, label: '外部屏幕 9' }] } });
    await page.getByRole('tab', { name: '外部屏幕 9（external:9:fixture）', exact: true }).click();
    await page.getByAltText('external:9:fixture 实时画面').waitFor();
    await page.getByText('只读 · 由其他端管理', { exact: true }).waitFor();
    assert.equal(await page.locator('.phone-controls,.phone-navigation').count(), 0, 'global manual mode does not authorize external-screen control');
    assert.equal(await keyboard.isDisabled(), true);
    const beforeExternalClick = await page.evaluate(() => window.inputs.length);
    await video.click();
    await page.keyboard.press('Enter');
    assert.equal(await page.evaluate(() => window.inputs.length), beforeExternalClick);
    await page.getByRole('tab', { name: '主屏', exact: true }).click();
    await picture.waitFor();
    await page.evaluate(() => {
      Object.defineProperty(document, 'hidden', { configurable: true, value: true });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    const hiddenRequests = await page.evaluate(() => window.frameRequests.length);
    await page.waitForTimeout(180);
    assert.equal(await page.evaluate(() => window.frameRequests.length), hiddenRequests, 'hidden preview stops requesting frames');
    await page.evaluate(() => {
      Object.defineProperty(document, 'hidden', { configurable: true, value: false });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await page.waitForFunction(count => window.frameRequests.length > count, hiddenRequests);
    const controls = page.locator('.phone-controls');
    for (const [mode, canResume, label, command] of [
      ['idle', false, '我来操作', 'takeOver'], ['running', false, '我来操作', 'takeOver'],
      ['manual', false, '结束操作', 'endManual'], ['manual', true, '交给 AI 继续', 'resumeTask'],
      ['stopped', false, '我来操作', 'takeOver'], ['stopped', true, '继续任务', 'resumeTask'],
      ['error', false, '我来操作', 'takeOver'], ['error', true, '继续任务', 'resumeTask'],
    ]) {
      await updateSnapshot({ control: { id: 'control', mode, canResume }, isRunning: mode === 'running' });
      await controls.getByRole('button', { name: label, exact: true }).click();
      assert.deepEqual(await page.evaluate(() => window.commands.at(-1)), { type: command, controlId: 'control' });
      assert.equal(await controls.getByRole('button', { name: '停止', exact: true }).count(), 0);
    }
    for (const [mode, label] of [['taking_over', '正在交接'], ['resuming', '正在恢复']]) {
      await updateSnapshot({ control: { id: 'control', mode, canResume: true } });
      assert.equal(await controls.getByRole('button', { name: label }).isDisabled(), true);
    }
    if (process.env.BBUI_PREVIEW_SCREENSHOT) {
      await updateSnapshot({ control: { id: 'control', mode: 'idle', canResume: false }, desktop: { connected: true, screens: [{ 屏幕会话: 'main', 显示屏编号: 0 }, { 屏幕会话: 'work', 显示屏编号: 2 }] } });
      await page.evaluate(() => { window.rotated = false; });
      await page.waitForFunction(() => document.querySelector('.phone-video img')?.naturalWidth === 800);
      await page.screenshot({ path: process.env.BBUI_PREVIEW_SCREENSHOT });
    }
  } finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
});
