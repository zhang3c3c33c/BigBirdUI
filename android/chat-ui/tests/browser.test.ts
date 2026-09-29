import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createServer } from 'node:http';
import { mkdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { chromium, type Page } from 'playwright';
import { initialSnapshot, type Snapshot } from '../src/contract';

const dist = path.resolve('dist');
const state: Snapshot = { ...initialSnapshot, revision: 1, sessionId: 'browser-fixture', sessionsReady: true,
  sessions: [{ id: 'browser-fixture', title: '测试会话', modified: 0, running: false, queued: 0, state: 'idle' }],
  status: { phase: 'ready', message: '准备就绪' } };
const publish = async (page: Page, value: Snapshot) => {
  await page.evaluate((detail) => window.dispatchEvent(new CustomEvent('bbui-message', { detail })), value);
  await page.waitForFunction(revision => (window as unknown as { commands: Array<{ type: string; revision?: number }> }).commands.some(c => c.type === 'rendered' && c.revision === revision), value.revision);
};

test('bundled UI renders streaming content, preserves expansion and reading position, and sends only native commands', async () => {
  const server = createServer(async (request, response) => {
    const file = path.resolve(dist, '.' + (request.url === '/' ? '/index.html' : request.url!));
    if (!file.startsWith(dist + path.sep)) { response.writeHead(403).end(); return; }
    try {
      response.setHeader('Content-Type', file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html');
      response.end(await readFile(file));
    } catch { response.writeHead(404).end(); }
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  assert.ok(address && typeof address === 'object');
  const browser = await chromium.launch({ channel: process.env.BBUI_TEST_BROWSER || 'chrome', headless: true });
  try {
    const context = await browser.newContext({ viewport: { width: 393, height: 780 }, permissions: ['clipboard-read', 'clipboard-write'] });
    await context.addInitScript({ content: `window.commands = [];
      window.BBUI = { postMessage(raw) {
        const command = JSON.parse(raw); window.commands.push(command);
        if (command.type === 'ready') window.dispatchEvent(new CustomEvent('bbui-message', { detail: ${JSON.stringify(state)} }));
      } };` });
    const page = await context.newPage();
    page.setDefaultTimeout(7000);
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${address.port}/`);
    await page.getByRole('textbox', { name: '消息', exact: true }).fill('你好，手机助手');
    await page.getByRole('button', { name: '发送消息' }).click();
    assert.ok((await page.evaluate(() => (window as unknown as { commands: Array<{ type: string; text?: string }> }).commands)).some((item) => item.type === 'send' && item.text === '你好，手机助手'));
    const initialSend = await page.evaluate(() => (window as unknown as { commands: Array<{ type: string; submissionId: string }> }).commands.find(c => c.type === 'send')!);
    assert.equal(await page.getByRole('textbox', { name: '消息', exact: true }).inputValue(), '你好，手机助手', 'sending preserves draft until durable acceptance');
    await publish(page, { ...state, revision: 1.1, submissionResult: { id: initialSend.submissionId, sessionId: state.sessionId, accepted: false } });
    await page.waitForFunction(() => !document.querySelector<HTMLButtonElement>('.send-button')!.disabled);
    assert.equal(await page.getByRole('textbox', { name: '消息', exact: true }).inputValue(), '你好，手机助手', 'failed model binding cannot lose draft');
    await page.getByRole('button', { name: '发送消息' }).click();
    const retriedSend = await page.evaluate(() => (window as unknown as { commands: Array<{ type: string; submissionId: string }> }).commands.filter(c => c.type === 'send').slice(-1)[0]!);
    await publish(page, { ...state, revision: 1.2, submissionResult: { id: retriedSend.submissionId, sessionId: state.sessionId, accepted: true } });
    await page.waitForFunction(() => document.querySelector<HTMLTextAreaElement>('textarea')!.value === '');
    const streamed: Snapshot = { ...state, revision: 2, isRunning: true, messages: [{ id: 'a1', role: 'assistant', status: 'streaming', parts: [
      { id: 'r1', type: 'reasoning', state: 'complete', text: '真实思考内容', durationMs: 1200 },
      { id: 'p1', type: 'text', state: 'streaming', text: '你好 **世界** 👋' },
      { id: 't1', type: 'tool', state: 'error', title: '查看屏幕', error: '截图失败', toolCallId: 'call1' },
    ] }] };
    await publish(page, streamed);
    await page.getByText('你好 世界 👋', { exact: true }).waitFor();
    assert.equal(await page.locator('details[open]').count(), 0);
    assert.equal(await page.locator('.execution-note').count(), 0);
    assert.equal(await page.getByText('你好 世界 👋', { exact: true }).isVisible(), true);
    assert.equal(await page.locator('.tool-card.error').count(), 1);
    assert.equal(await page.locator('.reasoning .duration').textContent(), '1.2 秒');
    await page.locator('.reasoning summary').click();
    assert.equal(await page.locator('.reasoning[open]').count(), 1);
    const finished = structuredClone(streamed);
    finished.revision = 3; finished.isRunning = false;
    finished.messages[0].status = 'complete';
    finished.messages[0].parts[1].state = 'complete';
    finished.messages[0].parts[1].text = '你好 **世界** 👋\n\n```ts\nconst ready = true;\n```';
    await publish(page, finished);
    await page.getByText('const ready = true;').waitFor();
    assert.equal(await page.locator('.assistant-message').count(), 1);
    assert.equal(await page.locator('.reasoning[open]').count(), 1);
    await page.getByRole('button', { name: '复制回复' }).click();
    assert.match(await page.evaluate(() => navigator.clipboard.readText()), /你好 \*\*世界\*\* 👋/);
    assert.equal(await page.locator('.copied-label').isVisible(), true);

    const long = structuredClone(finished);
    long.revision = 4; long.hasOlder = true;
    long.messages = Array.from({ length: 30 }, (_, i) => ({ id: `history-${i}`, role: 'assistant' as const, status: 'complete' as const, parts: [{ id: `part-${i}`, type: 'text' as const, state: 'complete' as const, text: `历史消息 ${i}\n\n这里有一段需要保留的完整内容。` }] }));
    await publish(page, long);
    await page.locator('.assistant-message').nth(29).waitFor();
    await page.locator('.viewport').evaluate((element) => { element.scrollTop = 150; });
    await page.waitForTimeout(80);
    const top = await page.locator('.viewport').evaluate((element) => element.scrollTop);
    long.revision = 5; long.isRunning = true; long.runningSessionId = state.sessionId;
    long.messages[29].status = 'streaming';
    long.messages[29].parts[0].text += '\n新的增量文本';
    await publish(page, long);
    await page.waitForTimeout(100);
    assert.equal(await page.locator('.viewport').evaluate((element) => element.scrollTop), top);
    assert.equal(await page.getByRole('button', { name: '回到底部' }).isVisible(), true);
    await page.locator('.viewport').evaluate((element) => { element.scrollTop = 0; });
    await page.getByRole('button', { name: '查看更早的对话' }).click();
    const oldHeight = await page.locator('.viewport').evaluate((element) => element.scrollHeight);
    // Native pagination changes the projection window without a new state revision.
    long.messages = [{ id: 'older-page', role: 'assistant', status: 'complete', parts: [{ id: 'older-part', type: 'text', state: 'complete', text: '更早的一页\n\n加载历史时应留在原来阅读的位置。' }] }, ...long.messages];
    await publish(page, long);
    await page.getByText('加载历史时应留在原来阅读的位置。').waitFor();
    await page.waitForTimeout(100);
    const pagePosition = await page.locator('.viewport').evaluate((element) => ({ top: element.scrollTop, height: element.scrollHeight }));
    assert.ok(Math.abs(pagePosition.top - (pagePosition.height - oldHeight)) < 3, `history anchor drift: ${JSON.stringify(pagePosition)} old height ${oldHeight}`);
    await page.getByRole('button', { name: '停止', exact: true }).click();
    assert.ok((await page.evaluate(() => (window as unknown as { commands: Array<{ type: string }> }).commands)).some((item) => item.type === 'stop'));

    // assistant-ui adds a trailing Empty renderer after tool-only messages and
    // messages ending in a tool call. It must not become an eternal spinner.
    const toolsOnly: Snapshot = { ...state, revision: 6, messages: [
      { id: 'tool-only', role: 'assistant', status: 'complete', parts: [{ id: 'tool-end', type: 'tool', state: 'complete', toolCallId: 'call-end', title: '已完成的查看屏幕', summary: '屏幕已获取' }] },
      { id: 'text-tool', role: 'assistant', status: 'complete', parts: [
        { id: 'before-tool', type: 'text', state: 'complete', text: '我来查看当前屏幕。' },
        { id: 'second-tool', type: 'tool', state: 'complete', toolCallId: 'call-next', title: '已完成的打开应用', summary: '应用已打开' },
      ] },
    ] };
    await publish(page, toolsOnly);
    await page.locator('[data-message-id="text-tool"] .tool-card.complete').waitFor();
    assert.equal(await page.getByText('正在准备回复…', { exact: true }).count(), 0);
    assert.equal(await page.locator('[data-message-id="tool-only"] .copy-button').count(), 0);
    assert.equal(await page.locator('[data-message-id="text-tool"] .execution-note').count(), 0);
    assert.equal(await page.getByText('我来查看当前屏幕。', { exact: true }).isVisible(), true);
    const emptyBlocks = structuredClone(toolsOnly);
    emptyBlocks.revision = 7;
    emptyBlocks.messages[0].parts.unshift(
      { id: 'explicit-empty-text', type: 'text', state: 'complete', text: ' \n ' },
      { id: 'explicit-empty-thought', type: 'reasoning', state: 'complete', text: '' },
    );
    await publish(page, emptyBlocks);
    await page.waitForTimeout(80);
    assert.equal(await page.locator('[data-message-id="tool-only"] .execution-note').count(), 0);
    assert.equal(await page.locator('[data-message-id="tool-only"] .reasoning').count(), 0);
    assert.equal(await page.getByText('等待模型返回思考内容…', { exact: true }).count(), 0);
    assert.equal(await page.getByText('正在准备回复…', { exact: true }).count(), 0);
    const runningTool = structuredClone(toolsOnly);
    runningTool.revision = 8; runningTool.isRunning = true;
    runningTool.messages[1].status = 'streaming';
    runningTool.messages[1].parts[1].state = 'running';
    await publish(page, runningTool);
    await page.locator('[data-message-id="text-tool"] .tool-card.running').waitFor();
    assert.equal(await page.getByText('正在准备回复…', { exact: true }).count(), 0);
    const emptyStream: Snapshot = { ...state, revision: 9, isRunning: true, messages: [{ id: 'empty-new', role: 'assistant', status: 'streaming', parts: [] }] };
    await publish(page, emptyStream);
    await page.getByText('正在准备回复…', { exact: true }).waitFor();
    assert.equal(await page.getByText('正在准备回复…', { exact: true }).count(), 1);
    await publish(page, { ...emptyStream, revision: 10, isRunning: false, messages: [{ id: 'empty-new', role: 'assistant', status: 'complete', parts: [] }] });
    await page.getByRole('button', { name: '发送消息' }).waitFor();
    assert.equal(await page.getByText('正在准备回复…', { exact: true }).count(), 0);
    const thoughtStream: Snapshot = { ...state, revision: 11, isRunning: true, messages: [{ id: 'thought-new', role: 'assistant', status: 'streaming', parts: [{ id: 'actual-thought', type: 'reasoning', state: 'streaming', text: '只应出现在折叠区的思考', durationMs: 600 }] }] };
    await publish(page, thoughtStream);
    await page.locator('.reasoning summary').waitFor();
    assert.equal(await page.locator('.reasoning[open]').count(), 0);
    assert.equal(await page.getByText('只应出现在折叠区的思考', { exact: true }).isVisible(), false);
    assert.equal(await page.getByText('正在准备回复…', { exact: true }).count(), 0);
    const finalSummary: Snapshot = { ...state, revision: 12, messages: [...toolsOnly.messages,
      { id: 'final-summary', role: 'assistant', status: 'complete', parts: [{ id: 'summary-text', type: 'text', state: 'complete', text: '最终总结应直接可见。' }] },
    ] };
    await publish(page, finalSummary);
    await page.getByText('最终总结应直接可见。', { exact: true }).waitFor();
    assert.equal(await page.locator('[data-message-id="final-summary"] .execution-note').count(), 0);
    assert.equal(await page.getByText('我来查看当前屏幕。', { exact: true }).isVisible(), true);
    assert.equal(await page.getByText('正在准备回复…', { exact: true }).count(), 0);
    const factual: Snapshot = { ...state, revision: 13, messages: [{ id: 'factual-tools', role: 'assistant', status: 'complete', parts: [
      { id: 'not-sent', toolCallId: 'not-sent', type: 'tool', state: 'complete', title: '点击打开美团', executionState: '未派发', observationState: '已取得' },
      { id: 'partial', toolCallId: 'partial', type: 'tool', state: 'complete', title: '滑动列表', executionState: '部分派发', observationState: '失败' },
      { id: 'unknown', toolCallId: 'unknown', type: 'tool', state: 'complete', title: '打开应用', executionState: '未知', observationState: '未请求' },
      { id: 'sent', toolCallId: 'sent', type: 'tool', state: 'complete', title: '点击搜索', executionState: '已派发', observationState: '失败' },
      { id: 'seen', toolCallId: 'seen', type: 'tool', state: 'complete', title: '查看屏幕', executionState: '无需派发', observationState: '已取得' },
    ] }] };
    await publish(page, factual);
    await page.locator('.tool-state').filter({ hasText: '结果待确认' }).waitFor();
    assert.deepEqual(await page.locator('.tool-state').allTextContents(), ['未执行 · 已观察', '部分派发 · 观察失败', '结果待确认', '已派发 · 观察失败', '已观察']);
    assert.equal(await page.locator('.tool-card.outcome-uncertain').count(), 4);
    assert.equal(await page.locator('.tool-card.error').count(), 0);
    assert.equal(await page.locator('.tool-symbol').filter({ hasText: '✓' }).count(), 0);
    assert.equal(await page.locator('.message-notice').count(), 0);
    const taskSnapshot: Snapshot = { ...state, revision: 14, taskUpdatedThisRun: true,
      taskDisplay: { state: 'waiting_user', label: '等待你补充' }, task: {
        version: 1, id: 'order', goal: '查看默认美团中的订单', status: 'waiting_user', summary: '已进入订单列表，还需明确查询日期。',
        constraints: [{ text: '默认那个就行', source: 'user' }, { text: '优先最近一周', source: 'preference' }, { text: '可能是外卖订单', source: 'assumption' }],
        facts: ['已选择美团原版'], unknowns: [{ text: '是否还有下一页', resolveBy: 'observe' }, { text: '订单日期', resolveBy: 'user' }],
        steps: [{ id: 'open', title: '打开美团原版', status: 'completed' }, { id: 'find', title: '查看订单', status: 'in_progress' }],
        question: '你想查看哪一天的订单？',
      }, messages: [{ id: 'task-message', role: 'assistant', status: 'complete', parts: [
        { id: 'task-tool', type: 'tool', state: 'complete', toolCallId: 'record', toolName: 'task_state', taskAction: 'update', title: '更新任务记录', summary: '任务记录已更新' },
      ] }] };
    await publish(page, taskSnapshot);
    assert.equal(await page.locator('.task-overview,.task-record,.task-tool').count(), 0);
    await page.getByRole('button', { name: '进度', exact: true }).click();
    await page.getByRole('dialog', { name: '任务进度' }).waitFor();
    await page.locator('[data-task-state="waiting_user"]').waitFor();
    await page.getByText('你想查看哪一天的订单？', { exact: true }).waitFor();
    assert.deepEqual(await page.locator('.progress-steps small').allTextContents(), ['已完成', '进行中']);
    assert.equal(await page.getByText('默认那个就行', { exact: true }).count(), 0);
    const completeTask: Snapshot = { ...taskSnapshot, revision: 15, taskDisplay: { state: 'completed', label: '已完成' },
      task: { ...taskSnapshot.task!, status: 'completed', summary: '已找到你指定日期的订单。', unknowns: [], question: undefined,
        steps: taskSnapshot.task!.steps!.map((step) => ({ ...step, status: 'completed' as const })), completionEvidence: '订单列表中已看到指定日期的订单。' } };
    await publish(page, completeTask);
    await page.locator('[data-task-state="completed"]').waitFor();
    assert.equal(await page.getByRole('dialog', { name: '任务进度' }).isVisible(), true);
    await page.getByText('订单列表中已看到指定日期的订单。', { exact: true }).waitFor();
    await publish(page, { ...completeTask, revision: 16, isRunning: true, taskUpdatedThisRun: false,
      taskDisplay: { state: 'running', label: '进行中' } });
    await page.locator('[data-task-state="running"]').waitFor();
    assert.equal(await page.locator('.progress-state').textContent(), '此前任务');
    await publish(page, { ...completeTask, revision: 17, taskUpdatedThisRun: false,
      taskDisplay: { state: 'unconfirmed', label: '本轮回复结束 · 任务状态未确认' } });
    await page.locator('[data-task-state="unconfirmed"]').waitFor();
    await publish(page, { ...completeTask, revision: 18, taskDisplay: { state: 'stopped', label: '已停止 · 任务记录已保留' } });
    await page.locator('[data-task-state="stopped"]').waitFor();
    await page.reload();
    await publish(page, completeTask);
    await page.getByRole('button', { name: '进度', exact: true }).waitFor();
    assert.equal(await page.getByRole('dialog').count(), 0);
    const restoredCommands = await page.evaluate(() => (window as unknown as { commands: Array<{ type: string }> }).commands);
    assert.ok(restoredCommands.every((command) => ['ready', 'rendered', 'viewState'].includes(command.type)), 'reload must never send prompts or replay phone operations');
    await page.getByRole('button', { name: '进度', exact: true }).click();
    await page.locator('[data-task-state="completed"]').waitFor();
    await page.keyboard.press('Escape');
    assert.equal(await page.getByRole('dialog').count(), 0);
    assert.deepEqual(errors, []);
    assert.equal(await page.getByText('重新生成').count(), 0);
    // Browsing another session must leave the execution owner and global STOP intact.
    const multi: Snapshot = { ...long, revision: 100, sessionsReady: true,
      runningSessionId: state.sessionId, queuePaused: false,
      sessions: [
        { id: state.sessionId, title: '任务 A', modified: 2, running: true, queued: 0, state: 'running' },
        { id: 'session-b', title: '任务 B', modified: 1, running: false, queued: 1, state: 'idle' },
      ], queue: [{ id: 'queued-b', sessionId: 'session-b', text: '等待执行的任务' }] };
    await publish(page, multi);
    await page.getByRole('button', { name: '打开会话栏' }).click();
    await page.getByLabel('搜索会话标题').fill('任务 B');
    assert.equal(await page.locator('.session-row').count(), 1);
    await page.locator('.session-select').click();
    const second: Snapshot = { ...multi, revision: 101, sessionId: 'session-b', isRunning: false,
      messages: [], hasOlder: false, viewState: { text: 'B 的独立草稿', scrollTop: 0 } };
    await publish(page, second);
    await page.waitForFunction(() => (document.querySelector('textarea') as HTMLTextAreaElement)?.value === 'B 的独立草稿');
    await page.getByText('正在执行：任务 A', { exact: true }).waitFor();
    assert.equal(await page.locator('.assistant-message').count(), 0);
    await page.getByRole('button', { name: '加入队列', exact: true }).click();
    const sent = await page.evaluate(() => (window as unknown as { commands: Array<{ type: string; sessionId?: string; submissionId?: string; text?: string }> }).commands);
    assert.ok(sent.some(c => c.type === 'selectSession' && c.sessionId === 'session-b'));
    assert.ok(sent.some(c => c.type === 'send' && c.sessionId === 'session-b' && c.text === 'B 的独立草稿' && c.submissionId));
    const pendingB = sent.filter(c => c.type === 'send').slice(-1)[0]!;
    second.submissionResult = { id: pendingB.submissionId!, sessionId: 'session-b', accepted: true };
    await publish(page, { ...multi, revision: 102, viewState: { text: 'A 的草稿', scrollTop: 150 } });
    await page.waitForFunction(() => Math.abs((document.querySelector('.viewport') as HTMLElement).scrollTop - 150) < 2);
    await page.waitForFunction(() => (document.querySelector('textarea') as HTMLTextAreaElement).value === 'A 的草稿');
    await publish(page, { ...second, revision: 103 });
    await publish(page, { ...multi, revision: 104, viewState: { text: 'A 的草稿', scrollTop: 150 } });
    await page.waitForFunction(() => Math.abs((document.querySelector('.viewport') as HTMLElement).scrollTop - 150) < 2);
    await publish(page, { ...second, revision: 105, queue: Array.from({ length: 15 }, (_, i) => ({ id: `many-${i}`, sessionId: 'session-b', text: `等待任务 ${i}` })) });
    await page.setViewportSize({ width: 393, height: 440 });
    await page.waitForTimeout(100);
    const bounds = await page.locator('.composer').boundingBox();
    assert.ok(bounds && bounds.y + bounds.height <= 440, 'queued items must not push the composer out of view');
    await publish(page, { ...second, revision: 106 });
    await page.setViewportSize({ width: 393, height: 780 });
    await page.getByRole('button', { name: '打开会话栏' }).click();
    await page.getByRole('button', { name: '会话菜单 任务 B', exact: true }).click();
    await page.getByRole('menuitem', { name: '置顶', exact: true }).click();
    assert.ok((await page.evaluate(() => (window as any).commands)).some((c: any) => c.type === 'pinSession' && c.sessionId === 'session-b' && c.pinned === true));
    await page.getByRole('button', { name: '会话菜单 任务 B', exact: true }).click();
    await page.keyboard.press('Escape');
    assert.equal(await page.getByRole('menu').count(), 0);
    await page.getByRole('button', { name: '会话菜单 任务 B', exact: true }).click();
    await page.getByRole('menuitem', { name: '重命名', exact: true }).click();
    await page.getByLabel('会话标题', { exact: true }).fill('已重命名');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await page.getByRole('button', { name: '会话菜单 任务 B', exact: true }).click();
    await page.getByRole('menuitem', { name: '删除', exact: true }).click();
    await page.getByText('此会话的排队任务也将取消。', { exact: false }).waitFor();
    await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click();
    assert.equal(await page.getByRole('complementary').getByRole('region', { name: '任务队列' }).count(), 0);
    await page.getByRole('button', { name: '关闭会话栏', exact: true }).click();
    await page.getByRole('button', { name: '待发送 · 1', exact: true }).click();
    await page.getByRole('dialog', { name: '待发送', exact: true }).getByRole('button', { name: '取消排队 等待执行的任务', exact: true }).click();
    await page.getByRole('dialog').getByRole('button', { name: '暂停队列', exact: true }).click();
    await page.keyboard.press('Escape');
    await publish(page, { ...second, revision: 107, queuePaused: true });
    await page.getByRole('button', { name: '继续队列', exact: true }).click();
    assert.equal(await page.locator('.welcome').count(), 0);
    const queueCommands = await page.evaluate(() => (window as unknown as { commands: Array<{ type: string }> }).commands);
    assert.ok(['cancelQueued', 'pauseQueue', 'resumeQueue'].every(type => queueCommands.some(c => c.type === type)));
    await publish(page, { ...second, revision: 108, queue: [] });
    await page.locator('.queue-strip').waitFor({ state: 'detached' });
    assert.equal(await page.locator('.queue-strip').count(), 0);
    // Control belongs to the execution session even while browsing another one.
    const controlled: Snapshot = { ...second, revision: 109, queuePaused: true,
      control: { id: 'control-running', mode: 'running', sessionId: state.sessionId, canResume: false, canSteer: true } };
    await publish(page, controlled);
    await page.getByRole('button', { name: '我来操作', exact: true }).click();
    assert.equal(await page.getByRole('button', { name: '立即补充', exact: true }).count(), 0);
    await publish(page, { ...controlled, revision: 110, sessionId: state.sessionId, isRunning: true });
    await page.getByRole('textbox', { name: '消息', exact: true }).fill('优先查看最近一周');
    await page.getByRole('button', { name: '加入队列', exact: true }).click();
    const steerCommand = await page.evaluate(() => (window as unknown as { commands: Array<{ type: string; submissionId: string }> }).commands.filter(c => c.type === 'send').slice(-1)[0]!);
    assert.equal(await page.getByRole('textbox', { name: '消息', exact: true }).inputValue(), '优先查看最近一周', 'retain draft until Pi accepts');
    assert.equal(await page.getByRole('button', { name: '加入队列', exact: true }).isDisabled(), true);
    await publish(page, { ...controlled, revision: 111, sessionId: state.sessionId, isRunning: true,
      submissionResult: { id: steerCommand.submissionId, sessionId: state.sessionId, accepted: false } });
    assert.equal(await page.getByRole('textbox', { name: '消息', exact: true }).inputValue(), '优先查看最近一周', 'a rejected supplement must not be lost');
    await page.getByRole('button', { name: '加入队列', exact: true }).click();
    const acceptedSteer = await page.evaluate(() => (window as unknown as { commands: Array<{ type: string; submissionId: string }> }).commands.filter(c => c.type === 'send').slice(-1)[0]!);
    await publish(page, { ...controlled, revision: 112, sessionId: state.sessionId, isRunning: true,
      submissionResult: { id: acceptedSteer.submissionId, sessionId: state.sessionId, accepted: true } });
    await page.waitForFunction(() => (document.querySelector('textarea') as HTMLTextAreaElement).value === '');
    await page.getByRole('textbox', { name: '消息', exact: true }).fill('新的补充');
    await page.getByRole('button', { name: '加入队列', exact: true }).click();
    const editedSteer = await page.evaluate(() => (window as unknown as { commands: Array<{ type: string; submissionId: string }> }).commands.filter(c => c.type === 'send').slice(-1)[0]!);
    await page.getByRole('textbox', { name: '消息', exact: true }).fill('保留正在写的新内容');
    await publish(page, { ...controlled, revision: 113, sessionId: state.sessionId, isRunning: true,
      submissionResult: { id: editedSteer.submissionId, sessionId: state.sessionId, accepted: true } });
    assert.equal(await page.getByRole('textbox', { name: '消息', exact: true }).inputValue(), '保留正在写的新内容');
    await page.getByRole('textbox', { name: '消息', exact: true }).fill('跨会话补充');
    await page.getByRole('button', { name: '加入队列', exact: true }).click();
    const crossSessionSteer = await page.evaluate(() => (window as unknown as { commands: Array<{ type: string; submissionId: string }> }).commands.filter(c => c.type === 'send').slice(-1)[0]!);
    await publish(page, { ...controlled, revision: 114, viewState: { text: '不要清掉 B 草稿', scrollTop: 0 } });
    await page.waitForFunction(() => (document.querySelector('textarea') as HTMLTextAreaElement).value === '不要清掉 B 草稿');
    const crossSessionResult = { id: crossSessionSteer.submissionId, sessionId: state.sessionId, accepted: true };
    await publish(page, { ...controlled, revision: 115, viewState: { text: '不要清掉 B 草稿', scrollTop: 0 }, submissionResult: crossSessionResult });
    assert.equal(await page.getByRole('textbox', { name: '消息', exact: true }).inputValue(), '不要清掉 B 草稿');
    await publish(page, { ...controlled, revision: 116, sessionId: state.sessionId, isRunning: true,
      viewState: { text: '跨会话补充', scrollTop: 0 }, submissionResult: crossSessionResult });
    await page.waitForFunction(() => (document.querySelector('textarea') as HTMLTextAreaElement).value === '');
    await page.getByRole('textbox', { name: '消息', exact: true }).fill('之后再查其他月份');
    await page.getByRole('button', { name: '加入队列', exact: true }).click();
    const queuedRace = await page.evaluate(() => {
      const all = (window as unknown as { commands: Array<{ type: string; submissionId: string }> }).commands;
      return { command: all.filter(command => command.type === 'send').at(-1)!, count: all.length };
    });
    // Native has cleared A's submitted draft, but its ACK has not arrived.
    // Scrolling must not re-save the still-visible sent text before switching.
    await page.locator('.viewport').evaluate(element => element.dispatchEvent(new Event('scroll')));
    await publish(page, { ...controlled, revision: 117, viewState: { text: 'B 等待确认时的新草稿', scrollTop: 0 } });
    await page.waitForFunction(() => (document.querySelector('textarea') as HTMLTextAreaElement).value === 'B 等待确认时的新草稿');
    const queuedResult = { id: queuedRace.command.submissionId, sessionId: state.sessionId, accepted: true };
    await publish(page, { ...controlled, revision: 118, viewState: { text: 'B 等待确认时的新草稿', scrollTop: 0 }, submissionResult: queuedResult });
    const raceWrites = await page.evaluate(start => (window as unknown as { commands: Array<{ type: string; sessionId: string; text: string; scrollTop: number }> }).commands.slice(start).filter(command => command.type === 'viewState'), queuedRace.count);
    assert.equal(raceWrites.some(command => command.sessionId === state.sessionId && command.text === '之后再查其他月份'), false, 'sent A draft is never revived by its scroll or switch flush');
    const savedA = raceWrites.filter(command => command.sessionId === state.sessionId).at(-1);
    assert.equal(savedA?.text, '');
    assert.equal(await page.getByRole('textbox', { name: '消息', exact: true }).inputValue(), 'B 等待确认时的新草稿');
    await publish(page, { ...controlled, revision: 119, sessionId: state.sessionId, isRunning: true,
      viewState: { text: savedA!.text, scrollTop: savedA!.scrollTop }, submissionResult: queuedResult });
    await page.waitForFunction(() => (document.querySelector('textarea') as HTMLTextAreaElement).value === '');
    const manual: Snapshot = { ...controlled, revision: 120, runningSessionId: '',
      control: { id: 'control-manual', mode: 'manual', sessionId: state.sessionId, canResume: true, canSteer: false } };
    await publish(page, manual);
    await page.getByText('你正在操作：任务 A', { exact: true }).waitFor();
    await page.getByRole('button', { name: '交给 AI 继续', exact: true }).click();
    assert.equal(await page.getByRole('button', { name: '我来操作', exact: true }).count(), 0);
    assert.equal(await page.getByRole('button', { name: '紧急停止', exact: true }).isVisible(), true);
    const commandsBeforePreview = await page.evaluate(() => (window as unknown as { commands: unknown[] }).commands.length);
    await page.getByRole('button', { name: '查看执行画面', exact: true }).click();
    const previewCommands = await page.evaluate((start) => (window as unknown as { commands: Array<{ type: string }> }).commands.slice(start), commandsBeforePreview);
    assert.deepEqual(previewCommands, [{ type: 'openPreview' }]);
    await page.getByRole('button', { name: '打开会话栏', exact: true }).click();
    await page.getByLabel('搜索会话标题').fill('任务 A');
    await page.getByRole('button', { name: '会话菜单 任务 A', exact: true }).click();
    await page.getByRole('menuitem', { name: '删除', exact: true }).click();
    await page.getByText('人工操作与当前任务将结束，队列将暂停。', { exact: false }).waitFor();
    await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click();
    await page.getByRole('button', { name: '关闭会话栏', exact: true }).click();
    await publish(page, { ...manual, revision: 121, control: { ...manual.control!, id: 'control-idle-manual', sessionId: '', canResume: false } });
    await page.getByRole('button', { name: '结束操作', exact: true }).click();
    assert.equal(await page.getByRole('button', { name: '交给 AI 继续', exact: true }).count(), 0);
    await publish(page, { ...manual, revision: 122, control: { ...manual.control!, id: 'control-stopped', mode: 'stopped' } });
    await page.getByRole('button', { name: '继续任务', exact: true }).click();
    const handoffCommands = await page.evaluate(() => (window as unknown as { commands: Array<{ type: string; controlId?: string; sessionId?: string; text?: string; submissionId?: string }> }).commands);
    assert.ok(handoffCommands.some(c => c.type === 'takeOver' && c.controlId === 'control-running'));
    assert.ok(handoffCommands.some(c => c.type === 'resumeTask' && c.controlId === 'control-manual'));
    assert.ok(handoffCommands.some(c => c.type === 'resumeTask' && c.controlId === 'control-stopped'));
    assert.ok(handoffCommands.some(c => c.type === 'endManual' && c.controlId === 'control-idle-manual'));
    assert.ok(handoffCommands.some(c => c.type === 'send' && c.sessionId === state.sessionId && c.text === '优先查看最近一周' && c.submissionId));
    assert.ok(handoffCommands.some(c => c.type === 'send' && c.sessionId === state.sessionId && c.text === '之后再查其他月份'));
    assert.equal(handoffCommands.filter(c => c.type === 'resumeQueue').length, queueCommands.filter(c => c.type === 'resumeQueue').length, 'task handoff cannot resume queued work');
    await publish(page, { ...manual, revision: 123, control: { ...manual.control!, id: 'control-transition', mode: 'taking_over', canResume: false } });
    await page.getByText('正在交接：任务 A', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '交给 AI 继续', exact: true }).count(), 0);
    assert.equal(await page.getByRole('button', { name: '停止', exact: true }).isVisible(), true);
    const modelOptions: NonNullable<Snapshot['modelOptions']> = [
      { connectionId: 'official', connectionName: '官方', modelId: 'reasoner', modelName: 'Reasoner', thinkingLevels: [{ id: 'off', label: '关闭' }, { id: 'high', label: '高' }], defaultThinkingLevel: 'high' },
      { connectionId: 'company', connectionName: '公司', modelId: 'reasoner', modelName: 'Reasoner', thinkingLevels: [], defaultThinkingLevel: '' },
      { connectionId: 'company', connectionName: 'Company', modelId: 'fast', modelName: 'Fast', thinkingLevels: [{ id: 'enabled', label: '开启' }, { id: 'disabled', label: '关闭' }], defaultThinkingLevel: 'enabled' },
    ];
    const selectionSnapshot: Snapshot = { ...state, revision: 130, stateId: 'selection-store', modelOptions,
      modelSelection: { connectionId: 'official', modelId: 'reasoner', thinkingLevel: 'high' } };
    await publish(page, selectionSnapshot);
    await page.getByRole('button', { name: '选择模型：Reasoner · 官方', exact: true }).waitFor();
    await page.getByRole('textbox', { name: '消息', exact: true }).fill('保留模型选择时的草稿');
    await page.getByRole('button', { name: '思考：high' }).click();
    await page.getByRole('dialog', { name: '思考档位' }).getByRole('button', { name: 'off', exact: true }).click();
    const selectedCommand = () => page.evaluate(() => (window as unknown as { commands: Array<{ type: string; sessionId: string; requestId: string; thinkingLevel?: string; connectionId?: string; modelId?: string }> }).commands.filter(c => c.type === 'selectModel' || c.type === 'setThinkingLevel').slice(-1)[0]!);
    const thinkingCommand = await selectedCommand();
    assert.equal(thinkingCommand.thinkingLevel, 'off');
    assert.equal(await page.getByRole('button', { name: '发送消息' }).isDisabled(), true);
    await publish(page, { ...selectionSnapshot, revision: 131 });
    assert.equal(await page.getByRole('button', { name: '发送消息' }).isDisabled(), true, 'stream updates are not selection acknowledgements');
    const thinkingAck = { id: thinkingCommand.requestId, sessionId: state.sessionId, accepted: true };
    await publish(page, { ...selectionSnapshot, revision: 132, modelSelection: { ...selectionSnapshot.modelSelection!, thinkingLevel: 'off' }, modelSelectionResult: thinkingAck });
    await page.getByRole('button', { name: '思考：off' }).waitFor();
    await page.waitForFunction(() => !document.querySelector<HTMLButtonElement>('.send-button')!.disabled);
    assert.equal(await page.getByRole('textbox', { name: '消息', exact: true }).inputValue(), '保留模型选择时的草稿');
    await page.getByRole('button', { name: '选择模型：Reasoner · 官方', exact: true }).click();
    await page.getByRole('dialog', { name: '选择模型' }).getByRole('region', { name: '公司', exact: true }).getByRole('button', { name: /^Reasoner/ }).click();
    const modelCommand = await selectedCommand();
    assert.equal(modelCommand.connectionId, 'company');
    assert.equal(modelCommand.modelId, 'reasoner');
    await publish(page, { ...selectionSnapshot, revision: 133, modelSelectionResult: thinkingAck });
    assert.equal(await page.getByRole('button', { name: '发送消息' }).isDisabled(), true, 'old acknowledgement cannot release newer request');
    await publish(page, { ...selectionSnapshot, revision: 134, modelSelectionResult: { id: modelCommand.requestId, sessionId: state.sessionId, accepted: false, error: '连接已删除' } });
    await page.getByRole('alert').filter({ hasText: '连接已删除' }).waitFor();
    await page.waitForFunction(() => !document.querySelector<HTMLButtonElement>('.send-button')!.disabled);
    await page.getByRole('button', { name: '选择模型：Reasoner · 官方', exact: true }).click();
    await page.getByRole('dialog', { name: '选择模型' }).getByRole('region', { name: '公司', exact: true }).getByRole('button', { name: /^Reasoner/ }).click();
    const retry = await selectedCommand();
    const otherSession: Snapshot = { ...selectionSnapshot, revision: 135, sessionId: 'second', modelSelection: { connectionId: 'company', modelId: 'reasoner', thinkingLevel: '' }, viewState: { text: 'B 独立草稿', scrollTop: 0 } };
    await publish(page, otherSession);
    await page.getByRole('button', { name: '选择模型：Reasoner · 公司', exact: true }).waitFor();
    assert.equal(await page.locator('.thinking-chip').count(), 0, 'unknown or fixed thinking has no empty control');
    await page.waitForFunction(() => document.querySelector<HTMLTextAreaElement>('textarea')!.value === 'B 独立草稿');
    await publish(page, { ...otherSession, revision: 136, modelSelectionResult: { id: retry.requestId, sessionId: state.sessionId, accepted: true } });
    await page.waitForFunction(() => !document.querySelector<HTMLButtonElement>('.send-button')!.disabled);
    assert.equal(await page.getByRole('textbox', { name: '消息', exact: true }).inputValue(), 'B 独立草稿');
    await page.getByRole('button', { name: '选择模型：Reasoner · 公司', exact: true }).click();
    await mkdir('test-results', { recursive: true });
    await page.screenshot({ path: 'test-results/browser-model-picker.png' });
    await page.getByRole('dialog', { name: '选择模型' }).getByRole('button', { name: '管理模型', exact: true }).click();
    assert.ok(await page.evaluate(() => (window as unknown as { commands: Array<{ type: string; page?: string }> }).commands.some(c => c.type === 'openSettings' && c.page === 'models')));
    await publish(page, { ...selectionSnapshot, revision: 137, viewState: { text: 'A 草稿', scrollTop: 0 } });
    await page.getByRole('button', { name: '选择模型：Reasoner · 官方', exact: true }).waitFor();
    await page.waitForFunction(() => document.querySelector<HTMLTextAreaElement>('textarea')!.value === 'A 草稿');
    await page.screenshot({ path: 'test-results/browser-model-composer.png' });
    await page.getByRole('button', { name: '思考：high' }).click();
    await page.getByRole('dialog', { name: '思考档位' }).getByRole('button', { name: 'default', exact: true }).click();
    const inherited = await selectedCommand();
    assert.equal(inherited.thinkingLevel, '');
    await publish(page, { ...selectionSnapshot, revision: 138, modelSelection: { ...selectionSnapshot.modelSelection!, thinkingLevel: '' },
      modelSelectionResult: { id: inherited.requestId, sessionId: state.sessionId, accepted: true } });
    await page.getByRole('button', { name: '思考：default' }).waitFor();
    const queuedControls: Snapshot = { ...state, revision: 139, stateId: 'selection-store', isRunning: true,
      runId: 'queue-run-one', runningSessionId: state.sessionId,
      control: { id: 'queue-control-one', mode: 'running', sessionId: state.sessionId, canResume: false, canSteer: true },
      queue: [{ id: 'original-queued-id', sessionId: state.sessionId, text: '优先查近期资料' }, { id: 'other-queued-id', sessionId: 'other', text: '其他会话任务' }] };
    await publish(page, { ...queuedControls, sessionsReady: false });
    await page.getByRole('textbox', { name: '消息', exact: true }).fill('');
    const composerStop = page.locator('.composer').getByRole('button', { name: '停止任务', exact: true });
    assert.equal(await composerStop.isEnabled(), true, 'STOP does not wait for send readiness');
    const beforeStop = await page.evaluate(() => (window as unknown as { commands: unknown[] }).commands.length);
    await composerStop.click();
    assert.deepEqual(await page.evaluate(start => (window as unknown as { commands: Array<{ type: string }> }).commands.slice(start).filter(command => ['stop', 'send'].includes(command.type)), beforeStop), [{ type: 'stop' }]);
    await page.getByRole('textbox', { name: '消息', exact: true }).fill('正在写的草稿');
    assert.equal(await page.locator('.composer').getByRole('button', { name: '加入队列', exact: true }).isDisabled(), true);
    assert.equal(await page.locator('.composer .queued-steer').count(), 0);
    await page.locator('.global-execution').getByRole('button', { name: '停止', exact: true }).click();
    assert.equal(await page.getByRole('textbox', { name: '消息', exact: true }).inputValue(), '正在写的草稿', 'STOP does not clear drafts');
    await publish(page, { ...queuedControls, revision: 140 });
    await page.locator('.composer').getByRole('button', { name: '加入队列', exact: true }).click();
    const composerSend = await page.evaluate(() => (window as unknown as { commands: Array<{ type: string; submissionId: string }> }).commands.filter(command => command.type === 'send').at(-1)!);
    await publish(page, { ...queuedControls, revision: 141, submissionResult: { id: composerSend.submissionId, sessionId: state.sessionId, accepted: false } });
    assert.equal(await page.getByRole('textbox', { name: '消息', exact: true }).inputValue(), '正在写的草稿');
    await page.locator('.composer').getByRole('button', { name: '加入队列', exact: true }).click();
    const acceptedSend = await page.evaluate(() => (window as unknown as { commands: Array<{ type: string; submissionId: string }> }).commands.filter(command => command.type === 'send').at(-1)!);
    await publish(page, { ...queuedControls, revision: 142, submissionResult: { id: acceptedSend.submissionId, sessionId: state.sessionId, accepted: true } });
    await composerStop.waitFor();
    await page.locator('.pending-list').getByRole('button', { name: '立即补充 优先查近期资料', exact: true }).click();
    const queuedCommand = await page.evaluate(() => (window as unknown as { commands: Array<{ type: string }> }).commands.filter(command => command.type === 'steerQueued').at(-1));
    assert.deepEqual(queuedCommand, { type: 'steerQueued', sessionId: state.sessionId, submissionId: 'original-queued-id', controlId: 'queue-control-one', runId: 'queue-run-one' });
    await page.getByRole('button', { name: '待发送 · 2', exact: true }).click();
    assert.equal(await page.getByRole('dialog').locator('.queued-steer').count(), 1, 'other-session queue items cannot steer this run');
    await page.getByRole('dialog').getByRole('button', { name: '立即补充 优先查近期资料', exact: true }).click();
    await page.keyboard.press('Escape');
    await publish(page, { ...queuedControls, revision: 143, runId: 'queue-run-two', control: { ...queuedControls.control!, id: 'queue-control-two' } });
    await page.locator('.pending-list').getByRole('button', { name: '立即补充 优先查近期资料', exact: true }).click();
    const changedGeneration = await page.evaluate(() => (window as unknown as { commands: Array<{ type: string; controlId: string; runId: string }> }).commands.filter(command => command.type === 'steerQueued').at(-1)!);
    assert.equal(changedGeneration.controlId, 'queue-control-two'); assert.equal(changedGeneration.runId, 'queue-run-two');
    await publish(page, { ...queuedControls, revision: 144, pendingQuestion: { requestId: 'waiting', sessionId: state.sessionId, runId: 'queue-run-one', toolCallId: 'waiting', questions: [], status: 'pending' } });
    assert.equal(await page.locator('.queued-steer').count(), 0, 'waiting questions hide immediate supplement');
    await publish(page, { ...queuedControls, revision: 145, sessionId: 'other', runId: 'other-history', viewState: { text: '', scrollTop: 0 } });
    assert.equal(await page.locator('.composer').getByRole('button', { name: '停止任务', exact: true }).count(), 0, 'browsing another session never turns its composer into STOP');
    await page.getByRole('button', { name: '待发送 · 2', exact: true }).click();
    assert.equal(await page.getByRole('dialog').locator('.queued-steer').count(), 0, 'selected-session runId cannot steer a different running session');
    await page.keyboard.press('Escape');
    await publish(page, { ...queuedControls, revision: 146, interruptedTasks: [
      { id: 'not-sent', sessionId: state.sessionId, text: '未发原文', interruptedReason: 'steering', steeringStatus: 'not_sent' },
      { id: 'unconfirmed', sessionId: state.sessionId, text: '未确认原文', interruptedReason: 'steering', steeringStatus: 'unconfirmed' },
      { id: 'accepted', sessionId: state.sessionId, text: '收到原文', interruptedReason: 'steering', steeringStatus: 'accepted' },
    ] });
    await page.getByText('补充未发送，已保留内容：未发原文', { exact: true }).waitFor();
    await page.getByText('补充是否收到尚未确认，未自动重发：未确认原文', { exact: true }).waitFor();
    await page.getByText('补充已收到，原任务已结束：收到原文', { exact: true }).waitFor();
    await page.screenshot({ path: 'test-results/browser-composer-stop-queue.png' });
    await page.setViewportSize({ width: 1100, height: 780 });
    await page.getByRole('complementary', { name: '会话管理' }).waitFor();
    assert.deepEqual(errors, []);
    await mkdir('test-results', { recursive: true });
    await page.screenshot({ path: 'test-results/browser-chat-verification.png' });
  } finally {
    await browser.close();
    await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
});

