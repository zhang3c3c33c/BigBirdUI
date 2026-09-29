import test from 'node:test';
import assert from 'node:assert/strict';
import { timedRpcWriter } from './rpc-timing.mjs';

test('RPC timing preserves original content and write callbacks/backpressure', () => {
  const original = { type: 'message_update', assistantMessageEvent: {
    type: 'thinking_delta', contentIndex: 0, delta: '中文👋\u2028\n未闭合```' } };
  const line = JSON.stringify(original) + '\n';
  const callback = () => {};
  let forwarded;
  const write = timedRpcWriter((...args) => { forwarded = args; return false; }, () => 12345);
  assert.equal(write(line, 'utf8', callback), false);
  assert.equal(forwarded[1], 'utf8');
  assert.equal(forwarded[2], callback);
  const parsed = JSON.parse(forwarded[0]);
  assert.equal(parsed.piEmittedAtMs, 12345);
  delete parsed.piEmittedAtMs;
  assert.deepEqual(parsed, original);
  assert.equal(line, JSON.stringify(original) + '\n');
});

test('non-RPC, partial, binary and multi-record writes are never buffered or altered', () => {
  for (const chunk of ['', 'hello\n', '{"type":"event"}', '{broken}\n',
    '{"other":true}\n', '{"type":"a"}\n{"type":"b"}\n', Buffer.from('{"type":"a"}\n')]) {
    let forwarded;
    const write = timedRpcWriter(value => { forwarded = value; return true; });
    assert.equal(write(chunk), true);
    assert.equal(forwarded, chunk);
  }
});

test('an already stamped record retains its first emission time', () => {
  const line = '{"type":"agent_start","piEmittedAtMs":12}\n';
  let forwarded;
  timedRpcWriter(value => { forwarded = value; }, () => 99)(line);
  assert.equal(forwarded, line);
});
