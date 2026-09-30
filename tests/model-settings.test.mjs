import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { discoveredModel, discoverModels } from '../desktop/model-discovery.mjs';
import { applyPreset, providerPresets } from '../android/chat-ui/src/provider-presets.ts';
import { mergeDiscoveredModels } from '../android/chat-ui/src/model-settings.tsx';

test('endpoint metadata agrees with native Android fixtures and never guesses by name', async () => {
  const cases = JSON.parse(await readFile(new URL('../pi/android/fixtures/model-metadata.json', import.meta.url), 'utf8'));
  for (const { row, connection, expected } of cases) {
    const { id, name, ...actual } = discoveredModel(row, connection);
    assert.deepEqual(actual, expected);
  }
});

test('only the documented native DeepSeek endpoint adds a separate off control', () => {
  const row = { id: 'arbitrary-name', effort: { supported_levels: ['low', 'high', 'max'], default_level: 'high' } };
  for (const baseUrl of ['https://api.deepseek.com', 'https://api.deepseek.com/v1/']) {
    const model = discoveredModel(row, { api: 'openai-completions', baseUrl });
    assert.deepEqual(model.thinkingLevels.map(level => level.id), ['off', 'low', 'high', 'max']);
    assert.equal(model.defaultThinkingLevel, 'high');
  }
  for (const baseUrl of ['https://api.deepseek.com.evil/v1', 'https://api.deepseek.com@evil/v1', 'https://user@api.deepseek.com/v1', 'https://api.deepseek.com/proxy', 'https://custom.invalid', 'http://api.deepseek.com', 'https://api.deepseek.com:444', 'https://api.deepseek.com?x=1']) {
    const model = discoveredModel(row, { api: 'openai-completions', provider: 'deepseek', baseUrl });
    assert.deepEqual(model.thinkingLevels.map(level => level.id), ['low', 'high', 'max']);
  }
  assert.deepEqual(discoveredModel(row, { api: 'openai-responses', baseUrl: 'https://api.deepseek.com' }).thinkingLevels.map(level => level.id), ['low', 'high', 'max']);
  assert.equal(discoveredModel({ id: 'deepseek-chat', reasoning: true }, { api: 'openai-completions', baseUrl: 'https://api.deepseek.com' }).thinkingLevels, undefined);
});

test('refresh fills missing fields while preserving custom settings, models and names', () => {
  const saved = [{ id: 'm', name: '我的模型', input: ['text'], reasoning: false, contextWindow: 32000 }, { id: 'private' }];
  const remote = [{ id: 'm', name: 'renamed', input: ['text', 'image'], reasoning: true, contextWindow: 200000, maxTokens: 8000 }, { id: 'new' }];
  assert.deepEqual(mergeDiscoveredModels(saved, remote), [{ ...remote[0], ...saved[0] }, saved[1], remote[1]]);
  assert.equal(saved[0].contextWindow, 32000);
});

test('refresh only fills a discovered default supported by the final saved levels', () => {
  const max = { id: 'max', label: 'max' };
  const remote = [{ id: 'm', reasoning: true, thinkingLevels: [{ id: 'high', label: 'high' }, max], defaultThinkingLevel: 'high' }];
  assert.equal(mergeDiscoveredModels([{ id: 'm', thinkingLevels: [max] }], remote)[0].defaultThinkingLevel, undefined);
  assert.equal(mergeDiscoveredModels([{ id: 'm', thinkingLevels: [max], defaultThinkingLevel: 'max' }], remote)[0].defaultThinkingLevel, 'max');
  assert.equal(mergeDiscoveredModels([{ id: 'm' }], remote)[0].defaultThinkingLevel, 'high');
});

test('all ten presets change connection fields without changing model capabilities', () => {
  assert.equal(providerPresets.length, 10);
  const original = { name: '我的连接', provider: 'custom', api: 'openai-completions', baseUrl: 'https://original.invalid/v1', apiKey: 'fixture', models: [{ id: 'm', input: ['text'], reasoning: false }] };
  for (const preset of providerPresets) {
    const next = applyPreset(original, preset.id);
    assert.deepEqual(next.models, original.models);
    assert.equal(next.name, '我的连接'); assert.equal(next.apiKey, '');
    assert.equal(next.baseUrl, preset.baseUrl);
  }
  assert.deepEqual(applyPreset(original, '').models, original.models);
});

test('discovery handles Anthropic pagination and rejects redirects before forwarding keys', async t => {
  const requests = [];
  const server = createServer((request, response) => {
    requests.push(request.url);
    if (request.url.startsWith('/redirect')) { response.writeHead(302, { location: '/leak' }).end(); return; }
    assert.equal(request.headers['x-api-key'], 'fixture');
    assert.equal(request.headers.authorization, undefined);
    const second = request.url.includes('after_id=a');
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify({ data: [{ id: second ? 'b' : 'a', capabilities: { image_input: { supported: true } } }], has_more: !second, last_id: second ? 'b' : 'a' }));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const address = server.address(); assert.ok(address && typeof address === 'object');
  const connection = { baseUrl: `http://127.0.0.1:${address.port}`, api: 'anthropic-messages', apiKey: 'fixture' };
  const models = await discoverModels(connection);
  assert.deepEqual(models.map(model => model.id), ['a', 'b']);
  assert.deepEqual(models[0].input, ['text', 'image']);
  assert.equal(requests[0], '/v1/models?limit=1000');
  await assert.rejects(discoverModels({ ...connection, baseUrl: connection.baseUrl + '/redirect' }));
  assert.equal(requests.some(url => url === '/leak'), false);
});
