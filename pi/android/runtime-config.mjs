import { createRequire } from 'node:module';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

// npm's production install nests pi-ai under coding-agent (the root pi-ai is
// dev-only). Resolve from the owner package, not from this APK bootstrap file.
const piRequire = createRequire(import.meta.resolve('@earendil-works/pi-coding-agent'));
const catalogPath = piRequire.resolve.paths('@earendil-works/pi-ai')
  .map(directory => path.join(directory, '@earendil-works/pi-ai/dist/providers/deepseek.models.js'))
  .find(candidate => existsSync(candidate));
if (!catalogPath) throw new Error('Bundled Pi DeepSeek model catalog is missing');
const { DEEPSEEK_MODELS } = await import(pathToFileURL(catalogPath).href);
const { OPENAI_MODELS } = await import(pathToFileURL(path.join(path.dirname(catalogPath), 'openai.models.js')).href);
const { ANTHROPIC_MODELS } = await import(pathToFileURL(path.join(path.dirname(catalogPath), 'anthropic.models.js')).href);
const catalogs = { deepseek: DEEPSEEK_MODELS, openai: OPENAI_MODELS, anthropic: ANTHROPIC_MODELS };
export const SUPPORTED_APIS = ['openai-completions', 'openai-responses', 'anthropic-messages'];

/** Known models retain Pi's capability and reasoning protocol metadata. */
export function modelConfiguration(config) {
  const provider = config.provider || 'bbui';
  const model = config.model || 'bbui-unconfigured';
  const known = catalogs[provider]?.[model];
  // Old custom configurations used Chat Completions, including provider=openai.
  const api = config.api || (provider === 'deepseek' ? known?.api : undefined) || 'openai-completions';
  if (!SUPPORTED_APIS.includes(api)) throw new Error(`不支持的 API 协议：${api}`);
  const definition = {
    baseUrl: config.baseUrl || known?.baseUrl || (api === 'anthropic-messages' ? 'https://api.anthropic.com' : 'https://api.openai.com/v1'),
    apiKey: '$BBUI_MODEL_KEY',
  };
  if (known && api === known.api) {
    // Let Pi retain all provider-specific metadata for the original protocol.
    if (config.api) definition.api = api;
  } else {
    definition.api = api;
    definition.models = [{ id: model, name: model, input: config.input ?? known?.input ?? ['text'],
      reasoning: config.reasoning ?? known?.reasoning ?? false,
      contextWindow: config.contextWindow || known?.contextWindow || 128000,
      maxTokens: config.maxTokens || known?.maxTokens || 8192 }];
  }
  return { providers: { [provider]: definition } };
}

export function runtimeSettings(previous, provider, model) {
  return { ...previous, defaultProvider: provider, defaultModel: model, packages: [],
    enableSkillCommands: false, quietStartup: true };
}

export async function validateThinkingLevel(config) {
  if (!config.thinkingLevel) return;
  const known = catalogs[config.provider]?.[config.model];
  if (!known || known.api !== config.api) throw new Error('模型思考档位缺少可验证元数据');
  const { getSupportedThinkingLevels } = await import(pathToFileURL(path.join(path.dirname(catalogPath), '../models.js')).href);
  if (!getSupportedThinkingLevels(known).includes(config.thinkingLevel)) throw new Error('模型不支持该思考档位');
}
