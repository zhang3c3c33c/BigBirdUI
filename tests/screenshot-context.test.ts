import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { buildSessionContext, SessionManager } from '@earendil-works/pi-coding-agent';
import { projectScreenshots, registerScreenshotContext } from '../pi/android/screenshot-context.ts';

function shot(id: string, size = 200): any {
  return { role: 'toolResult', toolName: 'phone_action', toolCallId: id, isError: false, timestamp: 1,
    content: [{ type: 'text', text: `已派发-${id}` }, { type: 'image', data: 'a'.repeat(size), mimeType: 'image/png' }] };
}
function imageCount(messages: any[]) { return messages.flatMap(m => Array.isArray(m.content) ? m.content : []).filter(b => b.type === 'image').length; }

test('each display retains its latest observation even when another display produces many frames', () => {
  const messages = [ { ...shot('main'), details: { 屏幕会话: 'main' } }, { ...shot('work'), details: { 屏幕会话: 'work' } },
    ...Array.from({ length: 10 }, (_, i) => ({ ...shot('other-' + i), details: { 屏幕会话: 'other' } })) ];
  const projected = projectScreenshots(messages);
  assert.equal(imageCount(projected), 3);
  assert.equal(projected[0].content[1].type, 'image'); assert.equal(projected[1].content[1].type, 'image');
  assert.equal(projected.at(-1)!.content[1].type, 'image');
});

test('50 images are bounded without changing text, reasoning, tool pairing or stored history', () => {
  const messages: any[] = [{ role: 'user', content: '中文👋用户要求' }];
  for (let i = 0; i < 50; i++) {
    messages.push({ role: 'assistant', content: [{ type: 'thinking', thinking: '保留思考', thinkingSignature: 'signature' },
      { type: 'toolCall', id: `call-${i}`, name: 'phone_action', arguments: { 操作: '查看' } }] }, shot(`call-${i}`));
  }
  const before = JSON.stringify(messages);
  const projected = projectScreenshots(messages);
  assert.equal(imageCount(projected), 3);
  assert.equal(projected.length, messages.length);
  projected.forEach((m, i) => {
    if (m.role !== 'toolResult') assert.deepEqual(m, messages[i]);
    else { assert.equal(m.toolCallId, messages[i].toolCallId); assert.deepEqual(m.content[0], messages[i].content[0]); }
  });
  assert.match(projected[2].content[1].text, /toolCallId=call-0、imageIndex=1/);
  assert.equal(JSON.stringify(messages), before);
  assert.deepEqual(projectScreenshots(projected), projected, 'projection is idempotent');
});

test('byte budget and history recall retain the newest actionable observation', () => {
  const messages = [shot('older'), shot('current'), ...[1, 2, 3, 4].map(i => ({ ...shot(`read-${i}`), toolName: 'phone_history' }))];
  const projected = projectScreenshots(messages, 3, 400);
  assert.equal(imageCount(projected), 2);
  assert.equal(projected[1].content[1].type, 'image');
  assert.equal(projected[5].content[1].type, 'image');
  assert.equal(imageCount(projectScreenshots([shot('large', 1000)], 3, 400)), 1, 'never silently remove the current view');
});

test('history tool only reads original images on the current branch, including across compaction', async () => {
  const manager = SessionManager.inMemory();
  manager.appendMessage(shot('old'));
  const firstKept = manager.appendMessage({ role: 'user', content: '继续', timestamp: 2 });
  manager.appendCompaction('已执行动作', firstKept, 10000);
  let tool: any;
  registerScreenshotContext({ on() {}, registerTool(value: any) { tool = value; } } as any);
  const ctx = { sessionManager: manager, model: { input: ['text', 'image'] } };
  const size = manager.getEntries().length;
  const result = await tool.execute('read', { toolCallId: 'old', imageIndex: 1 }, undefined, undefined, ctx);
  assert.equal(result.content[1].data, 'a'.repeat(200));
  assert.equal(result.details.historical, true);
  assert.match(result.content[0].text, /必须重新查看/);
  assert.equal(manager.getEntries().length, size, 'readback does not alter session or execute actions');
  await assert.rejects(tool.execute('read', { toolCallId: 'other-branch', imageIndex: 1 }, undefined, undefined, ctx));
  await assert.rejects(tool.execute('read', { toolCallId: 'old', imageIndex: 0 }, undefined, undefined, ctx));
  const abort = new AbortController(); abort.abort();
  await assert.rejects(tool.execute('read', { toolCallId: 'old', imageIndex: 1 }, abort.signal, undefined, ctx));
});

test('offline replay of the actual 413 session stays bounded and leaves its source intact', { skip: !existsSync('runs/413-diagnosis/session.jsonl') }, () => {
  const source = readFileSync('runs/413-diagnosis/session.jsonl', 'utf8');
  const entries = source.trim().split('\n').map(line => JSON.parse(line));
  const messages = buildSessionContext(entries).messages;
  assert.equal(imageCount(messages), 50);
  const projected = projectScreenshots(messages);
  assert.equal(imageCount(projected), 3);
  assert.ok(Buffer.byteLength(JSON.stringify(projected)) < 12 * 1024 * 1024);
  assert.equal(imageCount(messages), 50);
});
