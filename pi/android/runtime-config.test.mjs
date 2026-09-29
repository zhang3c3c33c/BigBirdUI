import test from 'node:test';
import assert from 'node:assert/strict';
import { DEEPSEEK_MODELS } from '@earendil-works/pi-ai/providers/deepseek.models';
import { modelConfiguration, runtimeSettings, SUPPORTED_APIS, validateThinkingLevel } from './runtime-config.mjs';

test('known DeepSeek models keep the original Pi capabilities and reasoning protocol', () => {
  for (const id of Object.keys(DEEPSEEK_MODELS)) {
    const provider = modelConfiguration({ provider: 'deepseek', model: id,
      baseUrl: 'http://127.0.0.1/mock', apiKey: 'must-not-serialize' }).providers.deepseek;
    assert.equal(provider.models, undefined);
    assert.equal(provider.api, undefined);
    assert.equal(provider.baseUrl, 'http://127.0.0.1/mock');
    assert.equal(provider.apiKey, '$BBUI_MODEL_KEY');
    assert.equal(JSON.stringify(provider).includes('must-not-serialize'), false);
    assert.equal(DEEPSEEK_MODELS[id].reasoning, true);
    assert.equal(DEEPSEEK_MODELS[id].compat.requiresReasoningContentOnAssistantMessages, true);
  }
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
    if (api !== 'openai-completions') {
      assert.equal(definition.models[0].contextWindow, DEEPSEEK_MODELS['deepseek-flash'].contextWindow);
      assert.equal(definition.models[0].compat, undefined, 'do not carry Chat-specific compatibility to other protocols');
    }
    assert.equal(modelConfiguration({ provider: 'custom', model: 'vision', api }).providers.custom.api, api);
  }
  assert.throws(() => modelConfiguration({ api: 'guess-protocol' }), /不支持的 API/);
});

test('thinking validation preserves inheritance and rejects unsupported explicit values', async () => {
  await validateThinkingLevel({provider:'custom',model:'custom',thinkingLevel:''});
  await validateThinkingLevel({provider:'deepseek',model:'deepseek-flash',api:'openai-completions',thinkingLevel:'high'});
  await assert.rejects(validateThinkingLevel({provider:'deepseek',model:'deepseek-flash',api:'openai-completions',thinkingLevel:'imaginary'}));
  await assert.rejects(validateThinkingLevel({provider:'custom',model:'custom',api:'openai-completions',thinkingLevel:'high'}));
});
