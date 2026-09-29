import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { generateSessionTitle, sessionTitleText, titleWithRuntime } from './session-title.mjs';
import { SUPPORTED_APIS } from './runtime-config.mjs';
import { events } from './protocol-fixtures.mjs';

const reply = text => ({ stopReason: 'stop', content: [{ type: 'text', text }] });

test('title completion sends one bounded text request without tools or history', async () => {
  let calls = 0;
  const runtime = { completeSimple: async (model, context, options) => {
    calls++;
    assert.equal(model.id, 'bound-model');
    assert.equal(context.messages.length, 1);
    assert.equal(context.messages[0].content, '中'.repeat(4000));
    assert.equal(context.tools, undefined);
    assert.equal(options.apiKey, 'bound-key');
    assert.equal(options.maxRetries, 0);
    assert.equal(options.maxTokens, 512);
    assert.equal(options.timeoutMs, 20000);
    return reply('“北京旅行攻略与景点收藏”');
  } };
  assert.equal(await titleWithRuntime(runtime, { id: 'bound-model' }, '中'.repeat(5000), 'bound-key'), '北京旅行攻略与景点收藏');
  assert.equal(calls, 1);
});

test('empty, incomplete and malformed titles fall back; timeout and errors never escape or retry', async () => {
  for (const value of ['', '第一行\n第二行', '长'.repeat(41)]) assert.equal(sessionTitleText(reply(value)), null);
  assert.equal(sessionTitleText({ ...reply('半截标题'), stopReason: 'length' }), null);
  assert.equal(sessionTitleText({ ...reply('错误内容'), stopReason: 'error' }), null);
  assert.equal(await generateSessionTitle('', { model: 'fixture', apiKey: 'key' }), null);
  let calls = 0;
  const runtime = { completeSimple: async (_, __, { signal }) => {
    calls++;
    await new Promise((_, reject) => {
      signal.addEventListener('abort', () => reject(new Error('private-key private-prompt')), { once: true });
    });
  } };
  const controller = new AbortController();
  const pending = titleWithRuntime(runtime, {}, '任务', 'private-key', controller.signal);
  controller.abort();
  assert.equal(await pending, null);
  assert.equal(calls, 1);
});

test('real Pi adapters title concurrently with isolated submitted keys and models', async () => {
  const observed = [];
  const home = await mkdtemp(path.join(tmpdir(), 'bbui-title-test-'));
  const originalSettings = '{"activeTaskSetting":true}';
  await writeFile(path.join(home, 'settings.json'), originalSettings);
  let failure;
  const server = createServer(async (req, res) => {
    try {
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks).toString());
      const pathname = new URL(req.url, 'http://localhost').pathname;
      const api = pathname.endsWith('/messages') ? 'anthropic-messages' : pathname.endsWith('/responses') ? 'openai-responses' : 'openai-completions';
      observed.push(api);
      assert.equal(api === 'anthropic-messages' ? req.headers['x-api-key'] : req.headers.authorization,
        api === 'anthropic-messages' ? `key-${api}` : `Bearer key-${api}`);
      assert.equal(body.model, `model-${api}`);
      assert.ok(!body.tools?.length);
      assert.match(JSON.stringify(body), /本次测试任务/);
      assert.ok(!JSON.stringify(body).includes('phone_action'));
      for (const name of await readdir(home)) {
        if (!name.startsWith('bbui-title-')) continue;
        const configuration = await readFile(path.join(home, name, 'models.json'), 'utf8').catch(() => '');
        assert.ok(!configuration.includes('key-'), 'temporary model metadata must not contain credentials');
      }
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      for (const event of events(api, false)) res.write(`${event.type ? `event: ${event.type}\n` : ''}data: ${JSON.stringify(event)}\n\n`);
      if (api === 'openai-completions') res.write('data: [DONE]\n\n');
      res.end();
    } catch (error) { failure = error; res.writeHead(400); res.end('{}'); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const beforeKey = process.env.BBUI_MODEL_KEY;
  const beforeHome = process.env.PI_CODING_AGENT_DIR;
  process.env.BBUI_MODEL_KEY = 'active-task-key';
  process.env.PI_CODING_AGENT_DIR = home;
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  try {
    const results = await Promise.all(SUPPORTED_APIS.map(api => generateSessionTitle('本次测试任务', {
      provider: 'fixture', model: `model-${api}`, api, apiKey: `key-${api}`,
      baseUrl: api === 'anthropic-messages' ? baseUrl : `${baseUrl}/v1`,
    })));
    if (failure) throw failure;
    assert.equal(observed.length, 3);
    assert.deepEqual(results, Array(3).fill('已查看，中文与 Emoji 👋。'));
    assert.equal(process.env.BBUI_MODEL_KEY, 'active-task-key', 'naming never replaces active task credentials');
    assert.equal(await readFile(path.join(home, 'settings.json'), 'utf8'), originalSettings);
    assert.deepEqual(await readdir(home), ['settings.json'], 'temporary model metadata is removed');
  } finally {
    if (beforeKey === undefined) delete process.env.BBUI_MODEL_KEY; else process.env.BBUI_MODEL_KEY = beforeKey;
    if (beforeHome === undefined) delete process.env.PI_CODING_AGENT_DIR; else process.env.PI_CODING_AGENT_DIR = beforeHome;
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
    await rm(home, { recursive: true, force: true });
  }
});

test('real provider failure returns null with no automatic retry', async () => {
  let requests = 0;
  const server = createServer(async (req, res) => {
    for await (const _ of req) { /* Consume only the synthetic request. */ }
    requests++;
    res.writeHead(500, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: { message: 'private-key private-prompt' } }));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    assert.equal(await generateSessionTitle('本次测试任务', { provider: 'fixture', model: 'fixture',
      api: 'openai-completions', apiKey: 'private-key', baseUrl: `http://127.0.0.1:${server.address().port}/v1` }), null);
    assert.equal(requests, 1);
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});

test('title RPC stays parallel to catalogue and conceals naming errors', async () => {
  const source = `
    import { installSessionRouter } from ${JSON.stringify(new URL('./sessions.mjs', import.meta.url).href)};
    let complete;
    installSessionRouter({ execute: async () => { complete(); return { sessions: [] }; } }, {
      catalogOnly: true,
      titleCommand: async text => {
        if (text === 'fail') throw new Error('private-key private-prompt');
        await new Promise(resolve => { complete = resolve; });
        return '后台标题';
      }
    });
  `;
  const child = spawn(process.execPath, ['--input-type=module', '-e', source], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
  let output = '', errors = '';
  child.stdout.on('data', chunk => { output += chunk; });
  child.stderr.on('data', chunk => { errors += chunk; });
  const deadline = setTimeout(() => child.kill(), 10000);
  try {
    child.stdin.write(JSON.stringify({ type: 'bbui_title', id: 'title', text: 'task', config: {} }) + '\n');
    child.stdin.write(JSON.stringify({ type: 'bbui_sessions', id: 'list', action: 'list' }) + '\n');
    child.stdin.end(JSON.stringify({ type: 'bbui_title', id: 'error', text: 'fail' }) + '\n');
    const [code] = await once(child, 'exit');
    assert.equal(code, 0, errors);
    const replies = output.trim().split('\n').map(line => JSON.parse(line));
    assert.deepEqual(replies.find(item => item.id === 'list').data, { sessions: [] });
    assert.deepEqual(replies.find(item => item.id === 'title').data, { title: '后台标题' });
    assert.deepEqual(replies.find(item => item.id === 'error').data, { title: null });
    assert.ok(!output.includes('private-'));
  } finally { clearTimeout(deadline); child.kill(); }
});
