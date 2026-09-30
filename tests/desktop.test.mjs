import path from 'node:path';
import { APP_NAME, configureAppIdentity } from '../desktop/branding.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { bindSubmission, restoreState, validateAnswer, normalizeModelSelection } from '../desktop/state.mjs';
import { projectMessages } from '../desktop/projection.mjs';
import { semanticKey } from '../desktop/platform.mjs';

test('restart preserves interrupted work without automatically replaying the queue', () => {
  const state = restoreState({ active: { id: 'a' }, queue: [{ id: 'b' }], questions: [{ status: 'pending' }], views: { a: { text: '草稿', scrollTop: 21 } } });
  assert.deepEqual(state.queue, []); assert.equal(state.paused, true);
  assert.deepEqual(state.interrupted.map(t => t.id), ['a', 'b']);
  assert.equal(state.questions[0].status, 'interrupted'); assert.equal(state.views.a.text, '草稿');
  assert.throws(() => restoreState({ version: 2 }), /数据版本/);
});
test('FIFO entries bind a configuration revision and independent model selection without secrets', () => {
  const state = restoreState(); const settings = { revision: 'v1', connections: [{ id: 'c', apiKey: 'SECRET', baseUrl: 'https://example.test', models: [{ id: 'm1' }, { id: 'm2' }] }], defaultModel: { connectionId: 'c', modelId: 'm1' } };
  const first = bindSubmission(state, settings, { submissionId: 'a', sessionId: 's', text: '第一项' }); state.queue.push(first);
  settings.revision = 'v2'; settings.defaultModel.modelId = 'm2';
  state.queue.push(bindSubmission(state, settings, { submissionId: 'b', sessionId: 't', text: '第二项' }));
  assert.deepEqual(state.queue.map(t => t.configRevision), ['v1', 'v2']); assert.equal(first.selection.modelId, 'm1');
  assert.ok(!JSON.stringify(state).includes('SECRET'));
  assert.throws(() => bindSubmission(state, settings, { submissionId: 'a', sessionId: 's', text: '重复' }));
});
test('question rejects stale runs, duplicates, invalid labels and incomplete answers', () => {
  const q = { status: 'pending', requestId: 'q', sessionId: 's', runId: 'r', questions: [{ id: 'q1', options: [{ label: '是' }] }] };
  const command = { requestId: 'q', sessionId: 's', runId: 'r', answers: [{ questionId: 'q1', selected: [], text: '补充说明' }] };
  validateAnswer(q, command);
  assert.throws(() => validateAnswer(q, { ...command, runId: 'old' }));
  assert.throws(() => validateAnswer({ ...q, status: 'answered' }, command));
  assert.throws(() => validateAnswer(q, { ...command, answers: [{ questionId: 'q1', selected: ['未知'], text: '' }] }));
});

test('old generic thinking choices fall back to API default while declared choices and task revisions survive', () => {
  const settings = { revision: 'latest', connections: [{ id: 'c', baseUrl: 'https://fixture.invalid', apiKey: 'secret', models: [
    { id: 'deepseek', reasoning: true, thinkingLevels: [{ id: 'off' }, { id: 'low' }, { id: 'high' }, { id: 'max' }], defaultThinkingLevel: 'high' },
    { id: 'unknown', reasoning: true },
  ] }] };
  const stale = { connectionId: 'c', modelId: 'deepseek', thinkingLevel: 'medium' };
  const state = restoreState({ selections: { s: stale } });
  const task = bindSubmission(state, settings, { submissionId: 'new', sessionId: 's', text: 'new explicit task' });
  assert.equal(task.selection.thinkingLevel, '');
  assert.equal(task.configRevision, 'latest');
  assert.equal(state.selections.s.thinkingLevel, 'medium', 'normalization cannot rewrite historical records');
  assert.equal(state.paused, true, 'normalization cannot resume the queue');
  for (const level of ['off', 'low', 'high', 'max']) assert.equal(normalizeModelSelection({ ...stale, thinkingLevel: level }, settings).thinkingLevel, level);
  assert.equal(normalizeModelSelection({ ...stale, modelId: 'unknown', thinkingLevel: 'high' }, settings).thinkingLevel, '');
  const archived = { ...settings, revision: 'old', connections: [{ ...settings.connections[0], models: [{ id: 'deepseek', reasoning: true }] }] };
  assert.equal(normalizeModelSelection({ ...stale, thinkingLevel: 'max' }, archived).thinkingLevel, '', 'sealed task versions use their own capability declaration');
});
test('chat projection preserves facts while excluding screenshot and raw tool diagnostics', () => {
  const value = projectMessages([{ role: 'assistant', content: [{ type: 'toolCall', id: 'c', name: 'phone_action', arguments: { 意图: '查看页面', 内容: 'PRIVATE' } }] },
    { role: 'toolResult', toolCallId: 'c', content: [{ type: 'image', data: 'SECRET_IMAGE' }], details: { 执行: { 状态: '已派发' }, 观察: { 状态: '失败' }, raw: 'PRIVATE' } }]);
  assert.equal(value[0].parts[0].executionState, '已派发'); assert.equal(value[0].parts[0].observationState, '失败');
  assert.ok(!JSON.stringify(value).includes('PRIVATE')); assert.ok(!JSON.stringify(value).includes('SECRET_IMAGE'));
});
test('keyboard mapping emits Android semantics', () => {
  assert.deepEqual(semanticKey('a', true), { operation: '全选', params: {} });
  assert.equal(semanticKey('Escape', false).params.键名, '返回');
  assert.equal(semanticKey('v', true), null);
  assert.equal(semanticKey('Backspace', false).params.键名, '删除');
  assert.equal(semanticKey('Delete', false).params.键名, '向前删除');
  for (const [key, name] of [['ArrowLeft', '左'], ['ArrowRight', '右'], ['ArrowUp', '上'], ['ArrowDown', '下']]) assert.equal(semanticKey(key, false).params.键名, name);
});

for (const override of [undefined, path.resolve('runs/custom profile')]) {
  test(`renaming preserves encrypted profile and session paths (${override ? 'custom' : 'default'})`, () => {
    const calls = [];
    const appData = path.resolve('runs/roaming fixture');
    const app = {
      getPath(name) { assert.equal(name, 'appData'); return appData; },
      setName(name) { calls.push(['name', name]); },
      setPath(name, value) { calls.push([name, value]); },
    };
    configureAppIdentity(app, override ? { BBUI_DESKTOP_DATA: override } : {});
    const expected = override || path.join(appData, 'BBUI');
    assert.deepEqual(calls, [['name', APP_NAME], ['userData', expected], ['sessionData', expected]]);
  });
}
