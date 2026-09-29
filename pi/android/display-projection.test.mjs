import test from 'node:test';
import assert from 'node:assert/strict';
import { displayEvent } from './display-projection.mjs';
import { timedRpcWriter } from './rpc-timing.mjs';

test('skill reading strips paths, argument deltas, documents and raw failures from every UI carrier', () => {
  const call = { type: 'toolCall', id: 'skill', name: 'read', arguments: { path: '/private/SKILL.md' }, partialJson: '/private/' };
  const assistant = { role: 'assistant', content: [call] };
  const result = { role: 'toolResult', toolCallId: 'skill', toolName: 'read', isError: true,
    content: [{ type: 'text', text: 'ENOENT /private/SKILL.md secret-document' }], details: { 错误: '/private/raw' } };
  const events = [
    { type: 'tool_execution_start', toolName: 'read', args: call.arguments },
    { type: 'tool_execution_update', toolName: 'read', partialResult: result },
    { type: 'tool_execution_end', toolName: 'read', isError: true, result },
    { type: 'message_end', message: result },
    { type: 'message_end', message: assistant },
    { type: 'response', data: { messages: [assistant, result] } },
    { type: 'agent_end', messages: [assistant, result] },
    { type: 'turn_end', message: assistant, toolResults: [result] },
    ...['toolcall_start', 'toolcall_delta', 'toolcall_end'].map(type => ({ type: 'message_update',
      assistantMessageEvent: { type, contentIndex: 0, delta: '/private/', toolCall: call, partial: assistant } })),
  ];
  for (const event of events) {
    const before = JSON.stringify(event);
    const projected = displayEvent(event);
    assert.doesNotMatch(JSON.stringify(projected), /private|secret-document|partialJson|ENOENT/);
    assert.equal(JSON.stringify(event), before, 'Pi source objects remain unchanged');
  }
  const failed = displayEvent(events[2]).result;
  assert.equal(failed.isError, true);
  assert.equal(failed.details.bbuiTool.status, 'error');
  assert.equal(failed.details.bbuiTool.title, '读取手机操作指南');
  const running = displayEvent({ type: 'tool_execution_update', toolName: 'read', partialResult: { content: [{ type: 'text', text: 'secret-document' }] } });
  assert.equal(running.partialResult.details.bbuiTool.status, 'running');
});

test('history and all live result carriers exclude images and raw diagnostics without mutating agent data', () => {
  const result = { role: 'toolResult', toolCallId: 'call-1', toolName: 'phone_action', isError: true,
    content: [{ type: 'text', text: 'raw private diagnostic' }, { type: 'image', data: 'a'.repeat(12 * 1024 * 1024), mimeType: 'image/jpeg' }],
    details: { 成功: false, 错误: '观察失败', 执行: { 状态: '已派发', raw: 'private' },
      观察: { 状态: '失败', raw: 'private' }, 截图: 'private', bbuiTask: null }, timestamp: 3 };
  const assistant = { role: 'assistant', content: [{ type: 'thinking', thinking: '真实思考👋\u2028' },
    { type: 'text', text: '正文\n```未闭合' }, { type: 'toolCall', id: 'call-1', name: 'phone_action', arguments: { 意图: '查看屏幕' } }], timestamp: 2 };
  for (const event of [
    { type: 'response', data: { messages: [assistant, result] } },
    { type: 'agent_end', messages: [assistant, result] },
    { type: 'message_end', message: result },
    { type: 'turn_end', message: assistant, toolResults: [result] },
    { type: 'tool_execution_end', result },
    { type: 'tool_execution_update', partialResult: result },
  ]) {
    const copy = displayEvent(event);
    assert.ok(JSON.stringify(copy).length < 2000);
    const projected = copy.result ?? copy.partialResult ?? copy.toolResults?.[0] ?? copy.messages?.[1] ?? copy.data?.messages[1] ?? copy.message;
    assert.equal(projected.isError, true);
    assert.deepEqual(projected.content, []);
    assert.deepEqual(projected.details, { 成功: false, 错误: '观察失败', 执行: { 状态: '已派发' }, 观察: { 状态: '失败' }, bbuiTask: null });
  }
  assert.equal(result.content[1].data.length, 12 * 1024 * 1024);
  assert.equal(result.details.截图, 'private');
  assert.deepEqual(displayEvent({ type: 'message_end', message: assistant }).message, assistant);
});

test('RPC wire filters images even when event already has an emission timestamp', () => {
  let wire;
  const callback = () => {};
  const writer = timedRpcWriter((value, cb) => { wire = value; assert.equal(cb, callback); return false; });
  assert.equal(writer(JSON.stringify({ type: 'message_end', piEmittedAtMs: 42,
    message: { role: 'user', content: [{ type: 'image', data: 'private' }, { type: 'text', text: '你好' }] } }) + '\n', callback), false);
  assert.deepEqual(JSON.parse(wire), { type: 'message_end', piEmittedAtMs: 42,
    message: { role: 'user', content: [{ type: 'text', text: '你好' }] } });
});

test('generic presentation is bounded and strips credentials, documents and unsafe source URLs', () => {
  const raw = { rawDocument: 'private-top-level', content: [{ type: 'text', text: 'private document'.repeat(10000) }], details: {
    bbuiTool: { kind: 'search', title: '搜索', summary: '字'.repeat(9000), apiKey: 'secret',
      sources: [{ title: '来源', url: 'https://example.org/a' }, { url: 'javascript:alert(1)' }, { url: 'https://key:secret@example.org' }] },
    rawHtml: '<html>private</html>' } };
  const projected = displayEvent({ type: 'tool_execution_end', result: raw }).result;
  assert.equal(projected.details.bbuiTool.summary.length, 1200);
  assert.deepEqual(projected.details.bbuiTool.sources, [{ title: '来源', url: 'https://example.org/a' }]);
  assert.ok(!JSON.stringify(projected).includes('secret'));
  assert.ok(!JSON.stringify(projected).includes('private-top-level'));
  assert.equal(raw.details.bbuiTool.summary.length, 9000);
});

test('Pi schema errors retain a bounded human-readable reason without raw tool content', () => {
  const result = displayEvent({ type: 'tool_execution_end', result: { isError: true, content: [{ type: 'text', text: '意图必填'.repeat(1000) }] } }).result;
  assert.equal(result.details.错误.length, 1000);
  assert.deepEqual(result.content, []);
});
