import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { chromium } from '../android/chat-ui/node_modules/playwright/index.mjs';
import { initialSnapshot } from '../android/chat-ui/src/contract.ts';

test('desktop bounds the chat viewport, follows streaming growth and preserves reading positions', async () => {
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
    const state = { ...initialSnapshot, revision: 1, sessionId: 'one', sessionsReady: true,
      sessions: [{ id: 'one', title: '第一会话', modified: 0, running: true, queued: 0, state: 'running' }, { id: 'two', title: '第二会话', modified: 0, running: false, queued: 0, state: 'idle' }],
      isRunning: true, runningSessionId: 'one', control: { id: 'control', mode: 'running', canResume: false },
      desktop: { configured: true, connected: true, screens: [{ 屏幕会话: 'main', 显示屏编号: 0 }] },
      viewState: { sessionId: 'one', text: '', scrollTop: 0 } };
    await page.addInitScript(snapshot => {
      window.commands = [];
      window.BBUI = { postMessage: raw => {
        const command = JSON.parse(raw); window.commands.push(command);
        if (command.type === 'ready') window.dispatchEvent(new CustomEvent('bbui-message', { detail: snapshot }));
      } };
      window.Desktop = { invoke: async () => null };
    }, state);
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.getByRole('button', { name: '开始使用', exact: true }).click();
    const viewport = page.locator('.viewport');
    await viewport.waitFor();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    async function publish(value = state) {
      value.revision = ++state.revision;
      await page.evaluate(detail => window.dispatchEvent(new CustomEvent('bbui-message', { detail })), value);
      await page.waitForFunction(revision => window.commands.some(command => command.type === 'rendered' && command.revision === revision), value.revision);
    }
    async function assertLayout() {
      const bounds = await page.evaluate(() => {
        const box = selector => { const rect = document.querySelector(selector).getBoundingClientRect(); return { top: rect.top, bottom: rect.bottom, height: rect.height }; };
        const viewport = document.querySelector('.viewport');
        return { windowHeight: innerHeight, chat: box('.chat-shell'), composer: box('.composer-footer'), viewport: box('.viewport'), clientHeight: viewport.clientHeight, scrollHeight: viewport.scrollHeight };
      });
      assert.ok(bounds.chat.height <= bounds.windowHeight + 1, JSON.stringify(bounds));
      assert.ok(bounds.composer.bottom <= bounds.windowHeight + 1 && bounds.composer.top >= 0, 'composer stays in the window: ' + JSON.stringify(bounds));
      assert.ok(bounds.viewport.bottom <= bounds.composer.top + 1 && bounds.clientHeight > 100, JSON.stringify(bounds));
      assert.ok(bounds.scrollHeight > bounds.clientHeight, 'message viewport must scroll instead of stretching the grid');
    }
    async function assertFollowing() {
      await page.waitForFunction(() => { const element = document.querySelector('.viewport'); return element.scrollHeight - element.clientHeight - element.scrollTop < 3; });
      await assertLayout();
    }
    state.messages = Array.from({ length: 45 }, (_, index) => ({ id: `message-${index}`, role: 'assistant', status: 'complete', parts: [{ id: `text-${index}`, type: 'text', state: 'complete', text: `历史消息 ${index}\n\n这是用于验证内容增长不会撑高聊天栏的消息。` }] }));
    const last = state.messages.at(-1); last.status = 'streaming'; last.parts[0].state = 'streaming';
    await publish();
    await assertFollowing();
    last.parts.push({ id: 'reasoning', type: 'reasoning', state: 'streaming', text: '正在分析' }, { id: 'tool', type: 'tool', toolCallId: 'tool-call', title: '查看屏幕', state: 'running', summary: '获取画面' });
    await publish();
    await page.locator('[data-message-id="message-44"] details').evaluateAll(elements => elements.forEach(element => { element.open = true; }));
    await assertFollowing();
    for (let index = 0; index < 3; index++) {
      last.parts[0].text += '\n\n持续输出的新段落。'.repeat(8);
      last.parts[1].text += '\n补充思考内容。'.repeat(8);
      last.parts[2].summary += '\n新的执行事实。'.repeat(8);
      await publish();
      await assertFollowing();
    }
    await viewport.evaluate(element => { element.scrollTop = 180; });
    await page.getByRole('button', { name: '回到底部' }).waitFor();
    const readingTop = await viewport.evaluate(element => element.scrollTop);
    last.parts[0].text += '\n\n用户阅读历史时继续输出。'.repeat(12);
    await publish();
    await page.waitForTimeout(100);
    assert.ok(Math.abs(await viewport.evaluate(element => element.scrollTop) - readingTop) < 3, 'streaming must not pull the reader to the bottom');
    await assertLayout();
    const second = { ...state, sessionId: 'two', viewState: { sessionId: 'two', text: '第二会话草稿', scrollTop: 320 }, messages: state.messages.map(message => ({ ...message, id: 'second-' + message.id })) };
    await publish(second);
    await page.waitForFunction(() => Math.abs(document.querySelector('.viewport').scrollTop - 320) < 3);
    assert.equal(await page.getByRole('textbox', { name: '消息', exact: true }).inputValue(), '第二会话草稿');
    state.viewState = { sessionId: 'one', text: '第一会话草稿', scrollTop: readingTop };
    await publish();
    await page.waitForFunction(top => Math.abs(document.querySelector('.viewport').scrollTop - top) < 3, readingTop);
    await assertLayout();
    await page.getByRole('button', { name: '回到底部' }).click();
    await assertFollowing();
    await page.getByRole('button', { name: '手机画面', exact: true }).click();
    await assertFollowing();
    await page.setViewportSize({ width: 1100, height: 640 });
    await assertFollowing();
  } finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
});
