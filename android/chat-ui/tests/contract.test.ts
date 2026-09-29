import assert from 'node:assert/strict';
import { test } from 'node:test';
import { acceptSnapshot, canAnswerQuestion, convertMessage, initialSnapshot, indexQuestions, questionAnswers, questionComplete, questionForPart, taskPresentation, toolPresentation, toolSources, toolTitle, type ChatMessage, type ChatPart, type PendingQuestion, type Snapshot } from '../src/contract';
import { coalescedWriter, viewStateSync } from '../src/view-state';

const message = (text: string, status: ChatMessage['status'] = 'streaming'): ChatMessage => ({
  id: 'assistant-1', role: 'assistant', status,
  parts: [{ id: 'text-1', type: 'text', state: status === 'streaming' ? 'streaming' : status, text }],
});
const snapshot = (revision: number, messages: ChatMessage[]): Snapshot => ({
  ...initialSnapshot, sessionId: 'session-1', runId: 'run-1', revision, messages,
});

test('text-only questions preserve draft text and require a nonblank answer', () => {
  const question: PendingQuestion = { requestId: 'text', sessionId: 's', runId: 'r', toolCallId: 'c', status: 'pending',
    questions: [{ id: 'q', header: '对象', question: '发给谁？', options: [], multiSelect: false }] };
  assert.equal(questionComplete(question, [{ questionId: 'q', selected: [], text: '  ' }]), false);
  assert.deepEqual(questionAnswers(question, [{ questionId: 'q', selected: ['fake'], text: '张三😀' }]), [{ questionId: 'q', selected: [], text: '张三😀' }]);
  assert.equal(questionComplete(question, [{ questionId: 'q', selected: [], text: '张三😀' }]), true);
  assert.equal(toolTitle({ id: 'read', type: 'tool', state: 'running', toolName: 'read' }), '读取手机操作指南');
});

test('generic tool display keeps provider content out of labels and rejects unsafe source links', () => {
  const part: ChatPart = { id: 'tool', type: 'tool', state: 'complete', toolName: 'web_search' };
  assert.equal(toolTitle(part), '搜索网页');
  assert.equal(toolTitle({ ...part, toolName: 'system_apps' }), '管理应用');
  assert.equal(toolTitle({ ...part, toolName: 'system_calendar' }), '管理日程');
  for (const [toolName, title] of Object.entries({ system_contacts: '管理联系人', system_sms: '读取短信', system_call_log: '查询通话记录', system_media: '检索媒体', system_clock: '闹钟与倒计时' })) {
    assert.equal(toolTitle({ ...part, toolName }), title);
    assert.equal(toolTitle({ ...part, toolName, title: '模型的具体操作意图' }), '模型的具体操作意图');
  }
  assert.equal(toolTitle({ ...part, toolName: 'memory_write' }), '更新记忆');
  assert.equal(toolTitle({ ...part, toolName: 'future_tool' }), '调用工具');
  assert.equal(toolTitle({ ...part, title: '搜索当地公交时刻' }), '搜索当地公交时刻');
  assert.deepEqual(toolSources({ ...part, sources: [
    { title: '公交', url: 'https://example.com/bus' },
    { title: 'bad', url: 'javascript:alert(1)' },
    { title: 'local', url: 'file:///private/key' },
    { title: 'credentials', url: 'https://key:secret@example.com' },
  ] }), [{ title: '公交', url: 'https://example.com/bus' }]);
  assert.equal(toolSources({ ...part, sources: Array.from({ length: 100 }, () => ({ title: 'x'.repeat(500), url: 'https://example.com' })) }).length, 10);
  assert.equal(toolPresentation(part).label, '已返回');
});

test('question answers require selected live session and current run identity', () => {
  const question: PendingQuestion = { requestId: 'q', sessionId: 'session-1', runId: 'run-1', toolCallId: 'call', status: 'pending',
    questions: [{ id: 'city', header: '城市', question: '去哪个城市？', options: [{ label: '北京' }, { label: '上海' }] }] };
  const live: Snapshot = { ...snapshot(1, []), isRunning: true, runningSessionId: 'session-1', pendingQuestion: question };
  assert.equal(canAnswerQuestion(live, question), true);
  assert.equal(canAnswerQuestion({ ...live, sessionId: 'session-2' }, question), false);
  assert.equal(canAnswerQuestion({ ...live, runId: 'run-2' }, question), false);
  assert.equal(canAnswerQuestion({ ...live, runningSessionId: '' }, question), false);
  assert.equal(canAnswerQuestion({ ...live, isRunning: false }, question), false);
  for (const status of ['answered', 'cancelled', 'interrupted'] as const) {
    assert.equal(canAnswerQuestion(live, { ...question, status }), false);
  }
});

test('question drafts preserve Unicode free text, reject foreign options and require explicit answers', () => {
  const question: PendingQuestion = { requestId: 'q', sessionId: 's', runId: 'r', toolCallId: 'c', status: 'pending', questions: [
    { id: 'one', header: '城市', question: '城市？', options: [{ label: '北京' }, { label: '上海' }] },
    { id: 'many', header: '风格', question: '喜欢哪些？', options: [{ label: '山' }, { label: '海' }], multiSelect: true },
  ] };
  assert.equal(questionComplete(question, []), false);
  assert.deepEqual(questionAnswers(question), [{ questionId: 'one', selected: [], text: '' }, { questionId: 'many', selected: [], text: '' }]);
  const answers = questionAnswers(question, [
    { questionId: 'one', selected: ['invalid', '上海', '北京'], text: '👩🏽‍💻 中文补充' },
    { questionId: 'many', selected: ['海', '山', '海'], text: '' },
    { questionId: 'old', selected: ['ignored'], text: '不属于当前问题' },
  ]);
  assert.deepEqual(answers, [{ questionId: 'one', selected: ['上海'], text: '👩🏽‍💻 中文补充' }, { questionId: 'many', selected: ['海', '山'], text: '' }]);
  assert.equal(questionComplete(question, answers), true);
  assert.equal(questionComplete(question, [{ questionId: 'one', selected: [], text: '其他城市' }, { questionId: 'many', selected: [], text: '  ' }]), false);
  assert.equal(questionAnswers({ ...question, status: 'answered', answers, draft: [] })[0].text, '👩🏽‍💻 中文补充');
  const longDraft = questionAnswers(question, [{ questionId: 'one', selected: [], text: 'a'.repeat(9999) + '😀'.repeat(40000) }]);
  assert.equal(longDraft[0].text.length, 9999);
  assert.equal(longDraft[0].text.endsWith('\ud83d'), false);
});

test('question request identities disambiguate reused provider call IDs and ambiguous legacy calls stay unbound', () => {
  const old: PendingQuestion = { requestId: 'old', sessionId: 'session-1', runId: '1', toolCallId: 'reused', status: 'answered', questions: [] };
  const live = { ...old, requestId: 'new', runId: '2', status: 'pending' as const };
  const first: ChatPart = { id: 'first', toolCallId: 'reused', questionRequestId: 'old', type: 'tool', state: 'complete' };
  const second: ChatPart = { ...first, id: 'second', questionRequestId: 'new', state: 'running' };
  const state = { ...snapshot(1, [{ id: 'm', role: 'assistant', status: 'streaming', parts: [first, second] }]), questionHistory: [old, live], pendingQuestion: live };
  assert.equal(questionForPart(state, first)?.requestId, 'old');
  assert.equal(questionForPart(state, second)?.requestId, 'new');
  assert.equal(questionForPart({ ...state, messages: [] }, second), undefined);
  assert.equal(questionForPart(state, { ...first, questionRequestId: undefined }), undefined);
  const legacy = { ...snapshot(2, [{ id: 'm', role: 'assistant', status: 'complete', parts: [{ ...first, questionRequestId: undefined }] }]), questionHistory: [old], pendingQuestion: old };
  assert.equal(questionForPart(legacy, { ...first, questionRequestId: undefined })?.requestId, 'old');
  assert.equal(questionForPart({ ...legacy, pendingQuestion: { ...old, messageSourceKey: 'assistant:1' } }, { ...first, questionRequestId: undefined }), undefined);
});

test('tool result labels distinguish completed transport from actual dispatch and observation', () => {
  const part: ChatPart = { id: 'fact', type: 'tool', state: 'complete', title: '打开美团' };
  for (const [executionState, observationState, label] of [
    ['未派发', '已取得', '未执行 · 已观察'],
    ['部分派发', '失败', '部分派发 · 观察失败'],
    ['未知', '未请求', '结果待确认'],
    ['已派发', '失败', '已派发 · 观察失败'],
    ['无需派发', '已取得', '已观察'],
    ['已派发', '已取得', '已派发 · 已观察'],
  ] as const) {
    const factual = { ...part, executionState, observationState };
    assert.equal(toolPresentation(factual).label, label);
    assert.equal(toolPresentation(factual).running, false);
    const converted = convertMessage({ id: 'm', role: 'assistant', status: 'complete', parts: [factual] });
    assert.deepEqual(converted.status, { type: 'complete', reason: 'stop' });
    const tool = converted.content[0] as { isError: boolean; artifact: ChatPart };
    assert.equal(tool.isError, false);
    assert.equal(tool.artifact.title, '打开美团');
    assert.equal(tool.artifact.executionState, executionState);
  }
  for (const executionState of ['未派发', '部分派发', '未知'] as const) {
    assert.equal(toolPresentation({ ...part, executionState }).tone, 'uncertain');
  }
  assert.equal(toolPresentation(part).label, '已返回');
  assert.equal(toolPresentation({ ...part, state: 'error' }).label, '调用失败');
  assert.equal(toolPresentation({ ...part, state: 'stopped' }).label, '已停止');
});

test('task record tools are distinct from phone actions and only native taskDisplay declares status', () => {
  const update: ChatPart = { id: 'record', type: 'tool', state: 'complete', toolName: 'task_state', taskAction: 'update', title: '更新任务记录' };
  assert.equal(toolPresentation(update).label, '已更新');
  assert.equal(toolPresentation({ ...update, taskAction: 'read' }).label, '已读取');
  assert.equal(toolPresentation({ ...update, state: 'error' }).label, '记录失败');
  const legacy = { ...snapshot(1, [message('本轮回复已结束', 'complete')]), task: {
    version: 1 as const, id: 'previous', goal: '旧任务', status: 'completed' as const, summary: '旧记录',
  } };
  assert.equal(taskPresentation(legacy).state, 'unconfirmed');
  assert.equal(taskPresentation({ ...legacy, isRunning: true }).state, 'running');
  assert.equal(taskPresentation({ ...legacy, status: { phase: 'stopped', message: '已停止' } }).state, 'stopped');
  assert.equal(taskPresentation({ ...legacy, taskDisplay: { state: 'waiting_user', label: '等待你补充' } }).state, 'waiting_user');
});

test('incremental snapshots replace one stable message without losing Chinese or split emoji', () => {
  const original = '你好，世界 👩🏽‍💻\n\n- 一项\n- **二项**\n```ts\nconst value = "完整";\n```';
  let state = initialSnapshot;
  for (let i = 1; i <= original.length; i++) {
    state = acceptSnapshot(state, snapshot(i, [message(original.slice(0, i))]));
    assert.equal(state.messages.length, 1);
    const adapted = convertMessage(state.messages[0]);
    assert.equal(adapted.id, 'assistant-1');
    assert.equal((adapted.content[0] as { text: string }).text, original.slice(0, i));
  }
  state = acceptSnapshot(state, snapshot(1000, [message(original, 'complete')]));
  assert.equal(state.messages[0].parts[0].text, original);
  assert.deepEqual(convertMessage(state.messages[0]).status, { type: 'complete', reason: 'stop' });
});

test('retains complete long history and references across updates, ignores old revisions', () => {
  const text = '长回复'.repeat(10000);
  const first = message(text, 'complete');
  let state = acceptSnapshot(initialSnapshot, snapshot(3, [first]));
  state = acceptSnapshot(state, snapshot(4, [structuredClone(first), { ...message('继续'), id: 'assistant-2' }]));
  assert.equal(state.messages[0], first);
  assert.equal(state.messages[0].parts[0].text?.length, text.length);
  assert.equal(acceptSnapshot(state, snapshot(2, [])), state);
  assert.equal(acceptSnapshot(state, snapshot(4, [])), state);
});

test('unrelated snapshot updates reuse the entire message array and question references', () => {
  const previous = snapshot(1, [message('不变的消息', 'complete')]);
  const next = acceptSnapshot(previous, { ...structuredClone(previous), revision: 2, queuePaused: true });
  assert.equal(next.messages, previous.messages);
  assert.equal(next.queuePaused, true);
  const changed = acceptSnapshot(next, { ...next, revision: 3, messages: [message('新增文字', 'complete')] });
  assert.notEqual(changed.messages, next.messages);
});

test('only a live unbound question falls back; terminal paged-out records stay absent', () => {
  const question: PendingQuestion = { requestId: 'req', sessionId: 'session-1', runId: 'run-1', toolCallId: 'call', status: 'pending', questions: [] };
  const state = { ...snapshot(1, []), pendingQuestion: question, runningSessionId: 'session-1', isRunning: true };
  assert.equal(indexQuestions(state).fallback, question);
  for (const status of ['answered', 'cancelled', 'interrupted'] as const) {
    assert.equal(indexQuestions({ ...state, pendingQuestion: { ...question, status } }).fallback, undefined);
  }
  assert.equal(indexQuestions({ ...state, isRunning: false }).fallback, undefined);
  const part: ChatPart = { id: 'p', type: 'tool', state: 'running', toolCallId: 'call', questionRequestId: 'req' };
  const index = indexQuestions({ ...state, messages: [{ id: 'm', role: 'assistant', status: 'streaming', parts: [part] }] });
  assert.equal(index.fallback, undefined);
  assert.equal(index.forPart(part), question);
  assert.equal(index.forPart({ ...part, questionRequestId: 'old' }), undefined);
});

test('bridge writer coalesces bursts, bounds sustained activity, and flushes the captured identity', context => {
  context.mock.timers.enable({ apis: ['setTimeout'] });
  const writes: Array<{ sessionId: string; top: number }> = [];
  const writer = coalescedWriter<(typeof writes)[number]>(value => writes.push(value));
  for (let index = 0; index < 20; index++) {
    writer.schedule({ sessionId: 'a', top: index });
    context.mock.timers.tick(50);
  }
  assert.deepEqual(writes, [{ sessionId: 'a', top: 19 }]);
  writer.schedule({ sessionId: 'a', top: 99 });
  writer.flush(); // Switch to B flushes the captured A coordinates.
  writer.schedule({ sessionId: 'b', top: 0 });
  context.mock.timers.tick(250);
  assert.deepEqual(writes.slice(1), [{ sessionId: 'a', top: 99 }, { sessionId: 'b', top: 0 }]);
  writer.flush();
  assert.equal(writes.length, 3);
  writer.schedule({ sessionId: 'b', top: 12 }); writer.cancel();
  context.mock.timers.tick(1000);
  assert.equal(writes.length, 3);
});

test('send ACK on another session cannot restore sent draft and never clears later edits', context => {
  context.mock.timers.enable({ apis: ['setTimeout'] });
  const writes: Array<{ sessionId: string; text: string; scrollTop: number }> = [];
  const sync = viewStateSync(value => writes.push(value));
  const first = { type: 'viewState' as const, sessionId: 'a', text: '已发送', scrollTop: 20 };
  sync.save(first); sync.begin('one', first);
  sync.save({ ...first, scrollTop: 55 }); sync.flush();
  assert.equal(writes.length, 1, 'scroll while waiting does not save the submitted text again');
  sync.save({ ...first, sessionId: 'b', text: 'B 草稿', scrollTop: 0 });
  sync.settle({ id: 'one', sessionId: 'a', accepted: true });
  assert.deepEqual(writes.at(-1), { type: 'viewState', sessionId: 'a', text: '', scrollTop: 55 });
  assert.equal(sync.shouldClear('one'), true);
  assert.ok(writes.some(value => value.sessionId === 'b' && value.text === 'B 草稿'));
  sync.begin('two', first);
  sync.save({ ...first, text: '新的编辑' });
  sync.save(first); // Even changing back is a new edit; it is not an old scroll event.
  sync.settle({ id: 'two', sessionId: 'a', accepted: true }); sync.flush();
  assert.equal(sync.shouldClear('two'), false);
  assert.equal(writes.at(-1)?.text, '已发送');
  sync.begin('three', first); sync.save({ ...first, scrollTop: 99 });
  sync.settle({ id: 'three', sessionId: 'a', accepted: false });
  assert.equal(sync.shouldClear('three'), false);
  assert.equal(writes.at(-1)?.text, '已发送');
  assert.equal(writes.at(-1)?.scrollTop, 99);
});

test('new session accepts revision reset; loading older prepends without duplicating current messages', () => {
  const last = message('当前');
  let state = acceptSnapshot(initialSnapshot, snapshot(100, [last]));
  state = acceptSnapshot(state, { ...snapshot(101, [{ ...message('更早'), id: 'older' }, last, last]), hasOlder: true });
  assert.deepEqual(state.messages.map((item) => item.id), ['older', 'assistant-1']);
  assert.equal(state.messages[1], last);
  const replaced = acceptSnapshot(state, { ...snapshot(1, []), sessionId: 'session-2' });
  assert.equal(replaced.messages.length, 0);
});

test('pagination accepts expanded snapshots at the same native revision', () => {
  const last = message('当前');
  const previous = { ...snapshot(20, [last]), hasOlder: true };
  const paged = acceptSnapshot(previous, { ...snapshot(20, [{ ...message('更早'), id: 'older' }, last]), hasOlder: true });
  assert.equal(paged.revision, 20);
  assert.deepEqual(paged.messages.map((item) => item.id), ['older', 'assistant-1']);
  assert.equal(paged.messages[1], last);
  assert.equal(acceptSnapshot(paged, { ...paged, hasOlder: false }).hasOlder, false);
});

test('service-wide revisions reject late snapshots across sessions and accept process restart', () => {
  const current = { ...snapshot(20, [message('A')]), stateId: 'service-1', sessionId: 'A' };
  assert.equal(acceptSnapshot(current, { ...snapshot(19, []), stateId: 'service-1', sessionId: 'B' }), current);
  const restarted = acceptSnapshot(current, { ...snapshot(1, []), stateId: 'service-2', sessionId: 'B' });
  assert.equal(restarted.sessionId, 'B');
});

test('thought, text, failed tool, next tool and summary preserve chronological separation', () => {
  const converted = convertMessage({ id: 'mixed', role: 'assistant', status: 'complete', parts: [
    { id: 'thought', type: 'reasoning', state: 'complete', text: '分析界面', durationMs: 1400 },
    { id: 'text', type: 'text', state: 'complete', text: '我来查看。' },
    { id: 'tool-1', toolCallId: 'call-1', type: 'tool', state: 'error', title: '查看屏幕', error: '截图失败' },
    { id: 'tool-2', toolCallId: 'call-2', type: 'tool', state: 'complete', title: '打开应用', summary: '已打开' },
    { id: 'summary', type: 'text', state: 'complete', text: '处理结果。' },
  ] });
  assert.ok(Array.isArray(converted.content));
  const parts = converted.content as Array<Record<string, unknown>>;
  assert.deepEqual(parts.map((part) => part.type), ['reasoning', 'text', 'tool-call', 'tool-call', 'text']);
  assert.equal(parts[2].isError, true);
  assert.equal(parts[3].isError, false);
  assert.deepEqual(parts[2].args, {});
  assert.equal(parts[2].argsText, '');
  assert.equal(parts[2].toolCallId, 'call-1');
  assert.deepEqual(parts[0].providerMetadata, { bbui: { id: 'thought', state: 'complete', durationMs: 1400 } });
});

test('stop and stream failures keep partial text and distinct terminal status', () => {
  for (const status of ['stopped', 'error'] as const) {
    const converted = convertMessage(message('尚未完成的回复', status));
    assert.deepEqual(converted.status, { type: 'incomplete', reason: status === 'stopped' ? 'cancelled' : 'error' });
    assert.equal((converted.content[0] as { text: string }).text, '尚未完成的回复');
  }
});
