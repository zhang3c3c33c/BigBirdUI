import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createServer } from 'node:http';
import { mkdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { chromium, type Page } from 'playwright';
import { initialSnapshot, type Command, type PendingQuestion, type Snapshot } from '../src/contract';

test('questions preserve native drafts, session identity and explicit submission', async () => {
  const dist = path.resolve('dist');
  const server = createServer(async (request, response) => {
    const file = path.resolve(dist, '.' + (request.url === '/' ? '/index.html' : request.url!));
    if (!file.startsWith(dist + path.sep)) { response.writeHead(403).end(); return; }
    try {
      response.setHeader('Content-Type', file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html');
      response.end(await readFile(file));
    } catch { response.writeHead(404).end(); }
  });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  assert.ok(address && typeof address === 'object');
  const browser = await chromium.launch({ channel: process.env.BBUI_TEST_BROWSER || 'chrome', headless: true });
  const publish = (page: Page, detail: Snapshot) => page.evaluate(value => window.dispatchEvent(new CustomEvent('bbui-message', { detail: value })), detail);
  const commands: Command[] = [];
  const question: PendingQuestion = { requestId: 'request-a', sessionId: 'a', runId: 'run-a', toolCallId: 'ask-call', status: 'pending', questions: [
    { id: 'city', header: '城市', question: '去哪个城市？', options: [{ label: '北京' }, { label: '上海', description: '游览外滩' }] },
    { id: 'style', header: '风格', question: '喜欢哪些景色？', multiSelect: true, options: [{ label: '山' }, { label: '海' }] },
    { id: 'other', header: '补充', question: '还有什么安排？', options: [{ label: '自由安排' }, { label: '没有' }] },
    { id: 'recipient', header: '联系人', question: '发给谁？', multiSelect: false, options: [] },
  ] };
  const waiting: Snapshot = { ...initialSnapshot, stateId: 'questions', revision: 1, sessionId: 'a', runId: 'run-a', isRunning: true, sessionsReady: true,
    runningSessionId: 'a', control: { id: 'control', mode: 'running', sessionId: 'a', canResume: false, canSteer: true }, pendingQuestion: question,
    sessions: [{ id: 'a', title: '旅行', modified: 0, running: true, queued: 0, state: 'running' }, { id: 'b', title: '其他', modified: 0, running: false, queued: 0, state: 'idle' }],
    messages: [{ id: 'ask-message', role: 'assistant', status: 'streaming', parts: [{ id: 'ask-part', type: 'tool', state: 'running', toolName: 'ask_user_question', toolCallId: 'ask-call' }] }],
  };
  let nativeState = structuredClone(waiting);
  try {
    const context = await browser.newContext({ viewport: { width: 393, height: 850 } });
    await context.exposeBinding('nativeCommand', async ({ page }, raw: string) => {
      const command: Command = JSON.parse(raw); commands.push(command);
      if (command.type === 'ready') await publish(page, nativeState);
      if (command.type === 'questionDraft' && nativeState.pendingQuestion?.requestId === command.requestId) {
        nativeState = { ...nativeState, pendingQuestion: { ...nativeState.pendingQuestion, draft: command.answers } };
      }
    });
    await context.addInitScript({ content: 'window.BBUI = { postMessage(raw) { window.nativeCommand(raw); } };' });
    const page = await context.newPage();
    await page.clock.install();
    page.setDefaultTimeout(7000);
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${address.port}/`);
    const card = page.getByRole('form', { name: '回答问题' });
    await card.waitFor();
    assert.equal(await card.count(), 1);
    assert.equal(await card.locator('input:checked').count(), 0);
    assert.equal(await card.getByRole('button', { name: '提交回答' }).isDisabled(), true);
    await card.getByRole('radio', { name: '上海 游览外滩' }).check();
    await card.getByRole('checkbox', { name: '山', exact: true }).check();
    await card.getByRole('checkbox', { name: '海', exact: true }).check();
    await card.getByRole('radio', { name: '补充：其他答案' }).check();
    await card.getByRole('textbox', { name: '补充：其他答案' }).fill('带孩子 👨‍👩‍👧，下午出发');
    assert.equal(await card.getByRole('radio', { name: '联系人：其他答案' }).count(), 0);
    await card.getByRole('textbox', { name: '联系人：答案' }).fill('  ');
    assert.equal(await card.getByRole('button', { name: '提交回答' }).isDisabled(), true);
    await card.getByRole('textbox', { name: '联系人：答案' }).fill('张三😀');
    assert.equal(commands.filter(command => command.type === 'answerQuestion').length, 0);
    await publish(page, { ...waiting, revision: 2 });
    assert.equal(await card.getByRole('textbox', { name: '补充：其他答案' }).inputValue(), '带孩子 👨‍👩‍👧，下午出发');
    await page.getByRole('textbox', { name: '消息', exact: true }).fill('稍后再做下一件事');
    assert.equal(await page.getByRole('button', { name: '立即补充' }).count(), 0);
    await publish(page, { ...waiting, revision: 3, sessionId: 'b', runId: '', isRunning: false, messages: [] });
    await page.getByText('等待回答：旅行', { exact: true }).waitFor();
    assert.equal(await card.count(), 0);
    await page.getByRole('button', { name: '返回', exact: true }).click();
    assert.ok(commands.some(command => command.type === 'selectSession' && command.sessionId === 'a'));
    assert.ok(commands.some(command => command.type === 'questionDraft' && command.answers[2].text === '带孩子 👨‍👩‍👧，下午出发'));
    nativeState = { ...nativeState, revision: 4 };
    await publish(page, nativeState);
    await card.waitFor();
    await page.reload();
    await card.waitFor();
    assert.equal(await card.getByRole('textbox', { name: '联系人：答案' }).inputValue(), '张三😀');
    assert.equal(await card.getByRole('textbox', { name: '补充：其他答案' }).inputValue(), '带孩子 👨‍👩‍👧，下午出发');
    assert.equal(await card.locator('input:checked').count(), 4);
    await card.getByRole('radio', { name: '城市：其他答案' }).check();
    await card.getByRole('textbox', { name: '城市：其他答案' }).fill('深圳');
    await card.getByRole('radio', { name: '北京', exact: true }).check();
    assert.equal(await card.getByRole('textbox', { name: '城市：其他答案' }).count(), 0);
    await card.getByRole('radio', { name: '城市：其他答案' }).check();
    assert.equal(await card.getByRole('textbox', { name: '城市：其他答案' }).inputValue(), '深圳');
    await card.getByRole('button', { name: '提交回答' }).click();
    await card.getByRole('button', { name: '正在提交' }).waitFor();
    const answer = commands.find(command => command.type === 'answerQuestion');
    assert.ok(answer?.type === 'answerQuestion');
    assert.equal(commands.filter(command => command.type === 'answerQuestion').length, 1);
    assert.equal(answer.sessionId, 'a'); assert.equal(answer.runId, 'run-a'); assert.equal(answer.requestId, 'request-a');
    assert.deepEqual(answer.answers[1].selected, ['山', '海']);
    assert.deepEqual(answer.answers[0], { questionId: 'city', selected: [], text: '深圳' });
    assert.deepEqual(answer.answers[3], { questionId: 'recipient', selected: [], text: '张三😀' });
    await publish(page, { ...nativeState, revision: 5 });
    assert.equal(await card.getByRole('button', { name: '正在提交' }).isDisabled(), true);
    await page.clock.fastForward(10001);
    await card.getByRole('alert').filter({ hasText: '尚未收到提交确认' }).waitFor();
    assert.equal(await card.getByRole('textbox', { name: '联系人：答案' }).inputValue(), '张三😀');
    assert.equal(commands.filter(command => command.type === 'answerQuestion').length, 1, 'timeout does not auto-submit');
    await card.getByRole('button', { name: '提交回答' }).click();
    const rejection = { requestId: 'request-a', runId: 'run-a', sessionId: 'a', revision: 1, accepted: false, error: '请重新提交' };
    await publish(page, { ...nativeState, revision: 6, questionResult: rejection });
    await card.getByRole('alert').waitFor();
    await card.getByRole('button', { name: '提交回答' }).click();
    await publish(page, { ...nativeState, revision: 7, questionResult: rejection });
    assert.equal(await card.getByRole('button', { name: '正在提交' }).isDisabled(), true);
    await publish(page, { ...waiting, revision: 8, pendingQuestion: null, questionHistory: [{ ...question, draft: answer.answers, answers: answer.answers, status: 'answered' }] });
    const records = page.locator('details.question-summary');
    await records.getByText('已回答', { exact: true }).waitFor();
    assert.equal(await card.count(), 0);
    assert.equal(await records.getAttribute('open'), null);
    assert.equal(await records.locator('input, textarea, button').count(), 0);
    await records.locator('summary').click();
    await records.locator('dd').filter({ hasText: '张三😀' }).waitFor();
    // A later reply stays below the original question, and answering does not
    // append another copy of the card to the bottom of the conversation.
    const answeredState: Snapshot = { ...waiting, revision: 8.1, isRunning: false,
      pendingQuestion: { ...question, answers: answer.answers, status: 'answered' },
      messages: [...waiting.messages, { id: 'answer-followup', role: 'assistant', status: 'complete', parts: [{ id: 'answer-text', type: 'text', state: 'complete', text: '收到你的选择，开始安排。' }] }] };
    await publish(page, answeredState);
    await page.getByText('收到你的选择，开始安排。', { exact: true }).waitFor();
    assert.equal(await records.count(), 1);
    assert.equal(await records.evaluate(element => !!(element.compareDocumentPosition(document.querySelector('[data-message-id="answer-followup"]')!) & Node.DOCUMENT_POSITION_FOLLOWING)), true);
    await publish(page, { ...answeredState, revision: 8.2, messages: answeredState.messages.slice(1), hasOlder: true });
    await page.waitForFunction(() => !document.querySelector('.question-card'));
    await publish(page, { ...answeredState, revision: 8.3, hasOlder: false });
    await records.waitFor();
    assert.equal(await records.getAttribute('open'), null, 'paged-in record is compact');
    const next: PendingQuestion = { ...question, requestId: 'next', runId: 'run-next', toolCallId: 'call-next' };
    const nextState: Snapshot = { ...waiting, revision: 9, runId: 'run-next', pendingQuestion: next, messages: [] };
    await publish(page, nextState);
    await card.getByRole('radio', { name: '补充：其他答案' }).check();
    await card.getByRole('textbox', { name: '补充：其他答案' }).fill('长'.repeat(70000));
    assert.equal((await card.getByRole('textbox', { name: '补充：其他答案' }).inputValue()).length, 10000);
    await card.getByRole('button', { name: '取消回答' }).click();
    const cancellation = commands.find(command => command.type === 'answerQuestion' && command.requestId === 'next' && command.cancelled);
    assert.ok(cancellation?.type === 'answerQuestion');
    assert.deepEqual(cancellation.answers, []);
    assert.ok(commands.filter(command => command.type === 'questionDraft' || command.type === 'answerQuestion').every(command => JSON.stringify(command).length < 65536));
    await publish(page, { ...nextState, revision: 10, questionResult: { requestId: 'next', runId: 'run-next', sessionId: 'a', revision: 3, accepted: true } });
    await records.getByText('已提交，等待确认', { exact: true }).waitFor();
    await page.clock.fastForward(10001);
    assert.equal(await card.count(), 0, 'accepted acknowledgement cancels timeout without claiming answered');
    await publish(page, { ...nextState, revision: 11, isRunning: false, pendingQuestion: { ...next, status: 'interrupted' }, control: { ...waiting.control!, mode: 'stopped' } });
    await page.waitForFunction(() => !document.querySelector('.question-card'));
    await publish(page, { ...nextState, revision: 11.1, isRunning: false, pendingQuestion: { ...next, status: 'interrupted' },
      messages: [{ id: 'interrupted-message', role: 'assistant', status: 'stopped', parts: [{ id: 'interrupted-part', type: 'tool', state: 'stopped', toolName: 'ask_user_question', toolCallId: next.toolCallId, questionRequestId: next.requestId }] }] });
    await records.getByText('提问已中断', { exact: true }).waitFor();
    assert.equal(await records.locator('input, textarea, button').count(), 0);
    const repeatedQuestions: PendingQuestion[] = ['北京', '上海'].map((city, index) => ({
      ...question, requestId: `history-${index}`, runId: `history-run-${index}`, toolCallId: 'provider-reused-id', status: 'answered',
      messageSourceKey: `assistant:${index + 1}`,
      questions: [{ ...question.questions[0], question: `第 ${index + 1} 次选择城市` }],
      answers: [{ questionId: 'city', selected: [city], text: '' }],
    }));
    const repeated: Snapshot = { ...waiting, revision: 12, isRunning: false, pendingQuestion: null, questionHistory: repeatedQuestions,
      messages: repeatedQuestions.map((item, index) => ({ id: `history-message-${index}`, role: 'assistant', status: 'complete',
        parts: [{ id: `history-part-${index}`, type: 'tool', state: 'complete', toolName: 'ask_user_question', toolCallId: item.toolCallId, questionRequestId: item.requestId }] })),
    };
    await publish(page, repeated);
    await records.filter({ hasText: '第 2 次选择城市' }).waitFor();
    assert.equal(await records.count(), 2);
    assert.equal(await records.locator('[open]').count(), 0);
    await records.nth(0).locator('summary').click();
    await records.nth(1).locator('summary').click();
    assert.equal(await records.filter({ hasText: '第 1 次选择城市' }).locator('dd').textContent(), '北京');
    assert.equal(await records.filter({ hasText: '第 2 次选择城市' }).locator('dd').textContent(), '上海');
    const unboundLive = { ...repeatedQuestions[1], status: 'pending' as const, draft: [] };
    await publish(page, { ...repeated, revision: 13, runId: unboundLive.runId, isRunning: true, pendingQuestion: unboundLive,
      messages: repeated.messages.map(message => ({ ...message, parts: message.parts.map(part => ({ ...part, questionRequestId: undefined })) })),
    });
    await card.getByText('等待回答', { exact: true }).waitFor();
    assert.equal(await card.count(), 1, 'ambiguous legacy calls do not render duplicate live cards');

    const longState: Snapshot = { ...repeated, revision: 14, hasOlder: true,
      pendingQuestion: repeatedQuestions[1], viewState: { text: '', scrollTop: 120 },
      messages: Array.from({ length: 45 }, (_, index) => ({ id: `long-${index}`, role: 'assistant', status: 'complete',
        parts: [{ id: `long-text-${index}`, type: 'text', state: 'complete', text: `第 ${index} 条记录\n\n${'用于验证连续阅读和草稿。'.repeat(6)}` }] })) };
    await publish(page, longState);
    await page.getByText('第 44 条记录', { exact: true }).waitFor();
    assert.equal(await page.locator('.question-card').count(), 0, 'ended question outside current page never falls back to the bottom');
    await page.clock.fastForward(1100);
    const viewCount = () => commands.filter(command => command.type === 'viewState').length;
    const beforeScroll = viewCount();
    for (let index = 0; index < 80; index++) {
      await page.locator('.viewport').evaluate((element, top) => { element.scrollTop = top; element.dispatchEvent(new Event('scroll')); }, 200 + index * 10);
      await page.clock.fastForward(100);
    }
    await page.clock.fastForward(300);
    const writes = viewCount() - beforeScroll;
    assert.ok(writes >= 2 && writes <= 10, `80 scroll updates coalesced into ${writes} bridge writes`);
    const lastScroll = commands.filter(command => command.type === 'viewState').at(-1);
    assert.equal(lastScroll?.type === 'viewState' && lastScroll.scrollTop, 990);
    await page.getByRole('textbox', { name: '消息', exact: true }).fill('A 最后草稿');
    await publish(page, { ...longState, revision: 15, sessionId: 'b', runId: '', messages: [], pendingQuestion: null, viewState: { text: 'B 独立草稿', scrollTop: 0 } });
    await page.waitForFunction(() => document.querySelector<HTMLTextAreaElement>('.composer-input')?.value === 'B 独立草稿');
    assert.ok(commands.some(command => command.type === 'viewState' && command.sessionId === 'a' && command.text === 'A 最后草稿' && command.scrollTop === 990));
    await page.getByRole('textbox', { name: '消息', exact: true }).fill('B 最新草稿');
    await page.evaluate(() => window.dispatchEvent(new Event('pagehide')));
    await page.clock.fastForward(1);
    const savedB = commands.filter(command => command.type === 'viewState').at(-1);
    assert.deepEqual(savedB, { type: 'viewState', sessionId: 'b', text: 'B 最新草稿', scrollTop: 0 });
    nativeState = { ...longState, revision: 16, viewState: { text: 'A 最后草稿', scrollTop: 990 } };
    await publish(page, nativeState);
    await page.waitForFunction(() => document.querySelector<HTMLTextAreaElement>('.composer-input')?.value === 'A 最后草稿');
    await page.reload();
    await page.waitForFunction(() => document.querySelector<HTMLTextAreaElement>('.composer-input')?.value === 'A 最后草稿' && document.querySelector('.viewport')?.scrollTop === 990);
    const movingQuestion: PendingQuestion = { ...question, requestId: 'moving-request', runId: 'moving-run', toolCallId: 'moving-call',
      questions: [question.questions[3]] };
    const movingState: Snapshot = { ...waiting, revision: 17, runId: movingQuestion.runId, pendingQuestion: movingQuestion, messages: [] };
    await publish(page, movingState);
    await card.getByRole('textbox', { name: '联系人：答案' }).fill('不能丢失的新草稿😀');
    const answersBeforeMove = commands.filter(command => command.type === 'answerQuestion').length;
    await publish(page, { ...movingState, revision: 18, messages: [{ id: 'moving-message', role: 'assistant', status: 'streaming',
      parts: [{ id: 'moving-part', type: 'tool', state: 'running', toolName: 'ask_user_question', toolCallId: movingQuestion.toolCallId, questionRequestId: movingQuestion.requestId }] }] });
    await page.waitForFunction(() => document.querySelector('[data-message-id="moving-message"] form') !== null);
    assert.equal(await card.getByRole('textbox', { name: '联系人：答案' }).inputValue(), '不能丢失的新草稿😀');
    assert.equal(await card.count(), 1);
    assert.equal(commands.filter(command => command.type === 'answerQuestion').length, answersBeforeMove);
    const focusQuestion = { id: 'overlay-navigation', sessionId: 'a', requestId: movingQuestion.requestId };
    const navigated: Snapshot = { ...movingState, revision: 19, focusQuestion, messages: [
      ...longState.messages.slice(0, 20),
      { id: 'navigation-question', role: 'assistant', status: 'streaming', parts: [{ id: 'navigation-part', type: 'tool', state: 'running',
        toolName: 'ask_user_question', toolCallId: movingQuestion.toolCallId, questionRequestId: movingQuestion.requestId }] },
      ...longState.messages.slice(20),
    ] };
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
    await publish(page, navigated);
    await page.waitForFunction(() => {
      const card = document.querySelector('.question-card[data-request-id="moving-request"]')?.getBoundingClientRect();
      const viewport = document.querySelector('.viewport')?.getBoundingClientRect();
      return card && viewport && Math.abs(card.top - viewport.top) < 6;
    });
    assert.equal(await page.evaluate(() => document.activeElement?.tagName === 'TEXTAREA'), false, 'opening an overlay question does not summon keyboard');
    assert.equal(commands.filter(command => command.type === 'answerQuestion').length, answersBeforeMove, 'navigation never submits an answer');
    await page.locator('.viewport').evaluate(element => { element.scrollTop = 120; });
    await publish(page, { ...navigated, revision: 20 });
    await page.clock.fastForward(200);
    assert.equal(await page.locator('.viewport').evaluate(element => element.scrollTop), 120, 'later snapshots do not refocus the same navigation');
    assert.deepEqual(commands.filter(command => command.type === 'questionFocused'), [{ type: 'questionFocused', ...focusQuestion }]);
    assert.deepEqual(errors, []);
    await mkdir('test-results', { recursive: true });
    await page.screenshot({ path: 'test-results/browser-questions.png' });
  } finally {
    await browser.close();
    await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
});
