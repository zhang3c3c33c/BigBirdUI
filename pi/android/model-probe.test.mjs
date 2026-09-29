import test from 'node:test';
import assert from 'node:assert/strict';
import { probeWithRuntime } from './model-probe.mjs';

const text = { stopReason: 'stop', content: [{ type: 'text', text: 'OK' }] };
test('probe uses fixed synthetic inputs and only simulates the named tool', async () => {
  const calls = [];
  const runtime = { completeSimple: async (_, context, options) => {
    calls.push(structuredClone(context));
    assert.equal(options.apiKey, 'secret'); assert.equal(options.maxRetries, 0);
    return calls.length === 3 ? { stopReason: 'toolUse', content: [{ type: 'toolCall', id: 'fixture', name: 'connection_test', arguments: {} }] } : text;
  } };
  const result = await probeWithRuntime(runtime, { input: ['text', 'image'] }, 'secret');
  assert.equal(result.ok, true); assert.equal(calls.length, 4);
  assert.equal(calls[1].messages[0].content[1].mimeType, 'image/png');
  assert.equal(calls[3].messages[2].role, 'toolResult');
  assert.ok(!JSON.stringify(result).includes('secret'));
});
test('probe does not claim tools work when model only returns text, and skips undeclared images', async () => {
  let calls = 0;
  const result = await probeWithRuntime({ completeSimple: async () => { calls++; return text; } }, { input: ['text'] }, 'secret');
  assert.equal(calls, 2); assert.equal(result.ok, false); assert.equal(result.checks.tool, false);
  assert.equal(result.checks.image, undefined);
});
test('probe never echoes raw provider errors', async () => {
  const result = await probeWithRuntime({ completeSimple: async () => { throw new Error('secret and private body'); } }, { input: ['text'] }, 'secret');
  assert.equal(result.ok, false); assert.ok(!JSON.stringify(result).includes('secret'));
});
test('unrecognized tools cannot execute or get synthetic results', async () => {
  let calls = 0;
  const result = await probeWithRuntime({ completeSimple: async () => ++calls === 1 ? text :
    { stopReason: 'toolUse', content: [{ type: 'toolCall', id: 'bad', name: 'phone_action', arguments: {} }] } }, { input: ['text'] }, 'secret');
  assert.equal(result.ok, false); assert.equal(calls, 2);
});
