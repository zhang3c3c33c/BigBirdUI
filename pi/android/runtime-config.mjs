export const SUPPORTED_APIS = ['openai-completions', 'openai-responses', 'anthropic-messages'];
export const THINKING_LEVELS = ['off', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'];

export function thinkingLevels(model) {
  if (!model.reasoning) return [];
  return (model.thinkingLevels ?? [])
    .filter(level => THINKING_LEVELS.includes(level.id));
}

export function validateModelSettings(model) {
  for (const key of ['contextWindow', 'maxTokens']) if (model[key] != null && (!Number.isInteger(model[key]) || model[key] <= 0 || model[key] > 2147483647)) throw new Error('模型长度必须为正整数');
  if (model.reasoning != null && typeof model.reasoning !== 'boolean') throw new Error('思考能力设置无效');
  if (model.input != null && (!Array.isArray(model.input) || !model.input.length || model.input.some(type => !['text', 'image'].includes(type)))) throw new Error('模型输入类型无效');
  if (model.thinkingLevels != null && (!Array.isArray(model.thinkingLevels) || model.thinkingLevels.some(level => !THINKING_LEVELS.includes(level?.id)))) throw new Error('思考档位设置无效');
  if (model.defaultThinkingLevel != null && model.defaultThinkingLevel !== '' && !(model.thinkingLevels ?? []).some(level => level.id === model.defaultThinkingLevel)) throw new Error('默认思考档位设置无效');
}

/** This identifies a wire protocol, not a model capability or provider label. */
export function isDeepSeekEndpoint(config) {
  if ((config.api || 'openai-completions') !== 'openai-completions') return false;
  try {
    const url = new URL(config.baseUrl);
    return url.origin === 'https://api.deepseek.com' && ['', '/v1'].includes(url.pathname.replace(/\/+$/, ''))
      && !url.username && !url.password && !url.search && !url.hash;
  } catch { return false; }
}

function completionsCompatibility(config) {
  if (isDeepSeekEndpoint(config)) return { thinkingFormat: 'deepseek', supportsReasoningEffort: true,
    requiresReasoningContentOnAssistantMessages: true, supportsStore: false, supportsDeveloperRole: false, maxTokensField: 'max_tokens' };
  let host = '';
  try { host = new URL(config.baseUrl).hostname; } catch {}
  // Preserve the pinned SDK's URL-based wire formats without its provider-label
  // shortcuts. No entry supplies reasoning capability or selectable levels.
  const zai = ['api.z.ai', 'open.bigmodel.cn'].includes(host);
  const together = ['api.together.ai', 'api.together.xyz'].includes(host);
  const antLing = host === 'api.ant-ling.com';
  const omitEffort = zai || together || antLing || ['api.moonshot.cn', 'api.moonshot.ai', 'api.x.ai',
    'gateway.ai.cloudflare.com', 'integrate.api.nvidia.com'].includes(host);
  // An arbitrary provider label must not turn an OpenAI-compatible endpoint into DeepSeek.
  return { thinkingFormat: zai ? 'zai' : together ? 'together' : antLing ? 'ant-ling' : host === 'openrouter.ai' ? 'openrouter' : 'openai', supportsReasoningEffort: !omitEffort,
    requiresReasoningContentOnAssistantMessages: false };
}

/** Capabilities come only from saved model settings, never a provider catalog. */
export function modelConfiguration(config) {
  validateModelSettings(config);
  const provider = config.provider || 'bbui';
  const model = config.model || 'bbui-unconfigured';
  const api = config.api || 'openai-completions';
  if (!SUPPORTED_APIS.includes(api)) throw new Error('不支持的 API 协议：' + api);
  return { providers: { [provider]: {
    baseUrl: config.baseUrl || (api === 'anthropic-messages' ? 'https://api.anthropic.com' : 'https://api.openai.com/v1'),
    apiKey: '$BBUI_MODEL_KEY', api,
    models: [{ id: model, name: model, input: config.input ?? ['text'], reasoning: config.reasoning ?? false,
      contextWindow: config.contextWindow || 128000, maxTokens: config.maxTokens || 8192,
      ...(api === 'anthropic-messages' && config.thinkingMode === 'adaptive' ? { compat: { forceAdaptiveThinking: true } } : {}),
      ...(api === 'openai-completions' ? { compat: completionsCompatibility(config) } : {}),
      // Keep the adapter's "off" encoding (e.g. OpenRouter's "none").
      thinkingLevelMap: Object.fromEntries(THINKING_LEVELS.filter(id => id !== 'off' || !thinkingLevels(config).some(level => level.id === id))
        .map(id => [id, thinkingLevels(config).some(level => level.id === id) ? id : null])),
    }],
  } } };
}

export function runtimeSettings(previous, provider, model) {
  return { ...previous, defaultProvider: provider, defaultModel: model, packages: [],
    enableSkillCommands: false, quietStartup: true };
}

export async function validateThinkingLevel(config) {
  if (!config.thinkingLevel || (!config.reasoning && config.thinkingLevel === 'off')) return;
  if (!thinkingLevels(config).some(level => level.id === config.thinkingLevel)) throw new Error('此模型未启用思考，或所选思考档位不可用');
}
