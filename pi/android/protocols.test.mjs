import { events } from './protocol-fixtures.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { ModelRuntime } from '@earendil-works/pi-coding-agent';
import { modelConfiguration, SUPPORTED_APIS } from './runtime-config.mjs';
import { budgetedFetch } from './request-budget.mjs';
import { probeWithRuntime } from './model-probe.mjs';

const pixel = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=';
const jpegPixel = '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD9U6KKKAP/2Q==';
const answer = '已查看，中文与 Emoji 👋。';
const routes = { 'openai-completions': '/v1/chat/completions', 'openai-responses': '/v1/responses', 'anthropic-messages': '/v1/messages' };

test('three real Pi adapters: routing, image serialization, streamed tool call and continuation; budget blocks outbound bytes', async () => {
  let api, count = 0, failure;
  const server = createServer(async (req, res) => {
    try {
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks).toString());
      count++;
      assert.equal(new URL(req.url, 'http://localhost').pathname, routes[api]);
      assert.equal(api === 'anthropic-messages' ? req.headers['x-api-key'] : req.headers.authorization,
        api === 'anthropic-messages' ? 'fixture-key' : 'Bearer fixture-key');
      assert.equal(body.stream, true);
      if (api === 'anthropic-messages') {
        assert.equal(body.thinking.type, 'adaptive');
        assert.equal(body.output_config.effort, 'high');
      }
      assert.ok(body.tools.length === 1);
      assert.ok(JSON.stringify(body).includes(pixel), 'real adapter includes the screenshot');
      if (count === 2) {
        assert.match(JSON.stringify(body), /call_fixture/);
        assert.ok(JSON.stringify(body).includes(jpegPixel), 'JPEG tool result bytes survive the adapter');
        assert.ok(JSON.stringify(body).includes('image/jpeg'), 'JPEG MIME type survives the adapter');
      }
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      for (const event of events(api, count === 1)) res.write(`${event.type ? `event: ${event.type}\n` : ''}data: ${JSON.stringify(event)}\n\n`);
      if (api === 'openai-completions') res.write('data: [DONE]\n\n');
      res.end();
    } catch (error) { failure = error; res.writeHead(400); res.end('{}'); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const root = `http://127.0.0.1:${server.address().port}`;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = budgetedFetch(originalFetch, root, 12000);
  try {
    const dir = await mkdtemp(path.join(tmpdir(), 'bbui-protocols-'));
    for (api of SUPPORTED_APIS) {
      count = 0; failure = undefined;
      const config = modelConfiguration({ provider: 'fixture', model: 'fixture', input: ['text', 'image'], api,
        ...(api === 'anthropic-messages' ? { reasoning: true, thinkingMode: 'adaptive', thinkingLevels: [{ id: 'high', label: 'high' }] } : {}),
        baseUrl: api === 'anthropic-messages' ? root : `${root}/v1` });
      await writeFile(path.join(dir, 'models.json'), JSON.stringify(config));
      const runtime = await ModelRuntime.create({ modelsPath: path.join(dir, 'models.json'), authPath: path.join(dir, 'auth.json'), refreshOnCreate: false });
      const model = runtime.getModel('fixture', 'fixture');
      assert.equal(model.api, api);
      const context = { messages: [{ role: 'user', content: [{ type: 'text', text: '请查看' }, { type: 'image', data: pixel, mimeType: 'image/png' }], timestamp: 1 }],
        tools: [{ name: 'observe', description: '读取画面', parameters: { type: 'object', properties: {} } }] };
      const options = { apiKey: 'fixture-key', maxRetries: 0, ...(api === 'anthropic-messages' ? { reasoning: 'high' } : {}) };
      const first = await runtime.completeSimple(model, context, options);
      if (failure) throw failure;
      assert.equal(first.stopReason, 'toolUse', `${api}: ${first.errorMessage}`);
      const call = first.content.find(block => block.type === 'toolCall');
      assert.equal(call.name, 'observe');
      context.messages.push(first, { role: 'toolResult', toolCallId: call.id, toolName: 'observe', isError: false, timestamp: 2,
        content: [{ type: 'text', text: '已观察' }, { type: 'image', data: jpegPixel, mimeType: 'image/jpeg' }] });
      const final = await runtime.completeSimple(model, context, options);
      if (failure) throw failure;
      assert.equal(final.stopReason, 'stop', `${api}: ${final.errorMessage}`);
      assert.equal(final.content.filter(block => block.type === 'text').map(block => block.text).join(''), answer);
      const blocked = await runtime.completeSimple(model, { messages: [{ role: 'user', content: '中'.repeat(5000), timestamp: 3 }] }, options);
      assert.equal(blocked.stopReason, 'error');
      assert.match(blocked.errorMessage, /大鸟手机助手本地请求预算超限/);
      assert.equal(count, 2, 'oversized request must never reach the server');
    }
  } finally {
    globalThis.fetch = originalFetch;
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});

test('Pi model registry honors explicit protocol overrides on a known model', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'bbui-model-routing-'));
  for (const api of SUPPORTED_APIS) {
    await writeFile(path.join(dir, 'models.json'), JSON.stringify(modelConfiguration({
      provider: 'deepseek', model: 'deepseek-flash', api, baseUrl: 'http://127.0.0.1:1' })));
    const runtime = await ModelRuntime.create({ modelsPath: path.join(dir, 'models.json'), authPath: path.join(dir, 'auth.json'), refreshOnCreate: false });
    assert.equal(runtime.getError(), undefined);
    const model = runtime.getModel('deepseek', 'deepseek-flash');
    assert.equal(model.api, api);
    assert.equal(model.baseUrl, 'http://127.0.0.1:1');
    assert.equal(model.reasoning, false);
    assert.deepEqual(model.input, ['text']);
    assert.equal(model.contextWindow, 128000);
    assert.equal(model.compat?.requiresReasoningContentOnAssistantMessages, undefined);
  }
});

test('connection probe exercises all three Pi adapters with only fixed synthetic inputs', async () => {
  let api, count = 0, failure;
  const server = createServer(async (req, res) => {
    try {
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks).toString()); count++;
      assert.equal(new URL(req.url, 'http://localhost').pathname, routes[api]);
      if (count === 2) assert.ok(JSON.stringify(body).includes(pixel));
      if (count >= 3) assert.ok(JSON.stringify(body).includes('connection_test'));
      assert.ok(!JSON.stringify(body).includes('phone_action'));
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      for (const event of events(api, count === 3, 'connection_test')) res.write(`${event.type ? `event: ${event.type}\n` : ''}data: ${JSON.stringify(event)}\n\n`);
      if (api === 'openai-completions') res.write('data: [DONE]\n\n');
      res.end();
    } catch (error) { failure = error; res.writeHead(400); res.end('{}'); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const dir = await mkdtemp(path.join(tmpdir(), 'bbui-probe-protocols-'));
    for (api of SUPPORTED_APIS) {
      count = 0; failure = undefined;
      const root = `http://127.0.0.1:${server.address().port}`;
      await writeFile(path.join(dir, 'models.json'), JSON.stringify(modelConfiguration({ provider: 'fixture', model: 'fixture', api,
        baseUrl: api === 'anthropic-messages' ? root : `${root}/v1`, input: ['text', 'image'] })));
      const runtime = await ModelRuntime.create({ modelsPath: path.join(dir, 'models.json'), authPath: path.join(dir, 'auth.json'), refreshOnCreate: false });
      const result = await probeWithRuntime(runtime, runtime.getModel('fixture', 'fixture'), 'fixture-key');
      if (failure) throw failure;
      assert.equal(result.ok, true, `${api}: ${JSON.stringify(result)}`); assert.equal(count, 4);
    }
  } finally {
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
  }
});
