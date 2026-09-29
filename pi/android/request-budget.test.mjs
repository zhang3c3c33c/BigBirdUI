import test from 'node:test';
import assert from 'node:assert/strict';
import { budgetedFetch } from './request-budget.mjs';

test('final UTF-8 bytes are checked before transmission, with a non-retryable response', async () => {
  let calls = 0;
  const fetch = budgetedFetch(async () => { calls++; return new Response('{}'); }, 'https://model.example/v1', 10);
  assert.equal((await fetch('https://model.example/v1/responses', { body: '中'.repeat(4) })).status, 413);
  assert.equal(calls, 0);
  const request = new Request('https://model.example/v1/messages', { method: 'POST', body: 'x'.repeat(11) });
  assert.equal((await fetch(request)).status, 413);
  assert.equal(request.bodyUsed, false);
  assert.equal(calls, 0);
  assert.equal((await fetch('https://model.example/v1/chat/completions', { body: 'x'.repeat(10) })).status, 200);
  assert.equal(calls, 1);
  await fetch('http://127.0.0.1:1234/stop', { body: 'x'.repeat(11) });
  assert.equal(calls, 2, 'model budget never interferes with STOP');
});
