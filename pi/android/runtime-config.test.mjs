import test from 'node:test';
import assert from 'node:assert/strict';
import { getSupportedThinkingLevels } from '@earendil-works/pi-ai';
import { modelConfiguration, runtimeSettings, SUPPORTED_APIS, validateThinkingLevel } from './runtime-config.mjs';

test('saved model capabilities override every provider and known model name', () => {
  for (const provider of ['deepseek', 'openai', 'anthropic', 'bbui', 'openrouter']) {
    const config = { provider, model: 'deepseek-flash', input: ['text'], reasoning: false,
      contextWindow: 32000, maxTokens: 4000, apiKey: 'must-not-serialize' };
    const definition = modelConfiguration(config).providers[provider];
    assert.deepEqual(definition.models[0].input, ['text']);
    assert.equal(definition.models[0].reasoning, false);
    assert.equal(definition.models[0].contextWindow, 32000);
    assert.equal(definition.models[0].maxTokens, 4000);
    assert.equal(JSON.stringify(definition).includes('must-not-serialize'), false);
  }
});

test('session controls use common protocol levels unless endpoint metadata limits them', () => {
  const missing = modelConfiguration({ reasoning: true }).providers.bbui.models[0];
  assert.deepEqual(getSupportedThinkingLevels(missing), ['off', 'minimal', 'low', 'medium', 'high']);
  assert.equal(missing.thinkingLevelMap.off, undefined, 'off uses adapter encoding rather than a literal invalid effort');
  const explicit = modelConfiguration({ reasoning: true, thinkingLevels: [{ id: 'high', label: 'high' }] }).providers.bbui.models[0];
  assert.deepEqual(getSupportedThinkingLevels(explicit), ['high']);
});

test('unknown OpenAI-compatible models retain the explicit fallback contract', () => {
  const provider = modelConfiguration({ provider: 'custom', model: 'vision-model', reasoning: true }).providers.custom;
  assert.equal(provider.api, 'openai-completions');
  assert.equal(provider.models[0].id, 'vision-model');
  assert.equal(provider.models[0].reasoning, true);
  assert.deepEqual(provider.models[0].input, ['text']);
  assert.deepEqual(modelConfiguration({provider:'custom',model:'vision',input:['text','image']}).providers.custom.models[0].input,['text','image']);
});

test('bootstrap preserves the user thinking preferences and unrelated Pi settings', () => {
  const previous = { defaultThinkingLevel: 'high',
    modelThinkingLevels: { 'deepseek/deepseek-flash': 'max' },
    hideThinkingBlock: true, compaction: { enabled: true }, packages: ['old-package'] };
  const next = runtimeSettings(previous, 'deepseek', 'deepseek-flash');
  assert.equal(next.defaultThinkingLevel, 'high');
  assert.deepEqual(next.modelThinkingLevels, previous.modelThinkingLevels);
  assert.deepEqual(next.compaction, previous.compaction);
  assert.equal(next.hideThinkingBlock, true);
  assert.equal(next.defaultProvider, 'deepseek');
  assert.equal(next.defaultModel, 'deepseek-flash');
  assert.deepEqual(next.packages, []);
  assert.deepEqual(previous.packages, ['old-package']);
});

test('explicit protocol selects the adapter even for a built-in provider and model', () => {
  for (const api of SUPPORTED_APIS) {
    const definition = modelConfiguration({ provider: 'deepseek', model: 'deepseek-flash', api }).providers.deepseek;
    assert.equal(definition.api, api);
    assert.equal(definition.models[0].reasoning, false);
    assert.equal(definition.models[0].contextWindow, 128000);
    assert.equal(definition.models[0].compat, undefined);
    assert.equal(modelConfiguration({ provider: 'custom', model: 'vision', api }).providers.custom.api, api);
  }
  assert.throws(() => modelConfiguration({ api: 'guess-protocol' }), /不支持的 API/);
});

test('thinking validation preserves inheritance and rejects unsupported explicit values', async () => {
  await validateThinkingLevel({provider:'custom',model:'custom',thinkingLevel:''});
  await validateThinkingLevel({provider:'deepseek',model:'deepseek-flash',api:'openai-completions',reasoning:true,thinkingLevels:[{id:'high',label:'high'}],thinkingLevel:'high'});
  await assert.rejects(validateThinkingLevel({provider:'deepseek',model:'deepseek-flash',api:'openai-completions',thinkingLevel:'imaginary'}));
  await assert.rejects(validateThinkingLevel({provider:'custom',model:'custom',api:'openai-completions',thinkingLevel:'high'}));
});

test('explicit adaptive thinking metadata selects the wire format without model-name inference', () => {
  for (const api of SUPPORTED_APIS) {
    const model = modelConfiguration({ api, provider: 'arbitrary', model: 'arbitrary', reasoning: true, thinkingMode: 'adaptive' }).providers.arbitrary.models[0];
    assert.equal(model.compat?.forceAdaptiveThinking, api === 'anthropic-messages' ? true : undefined);
  }
});
