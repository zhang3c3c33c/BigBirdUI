import { isDeepSeekEndpoint } from './runtime-config.mjs';

const paths = { 'openai-completions': 'chat/completions', 'openai-responses': 'responses', 'anthropic-messages': 'v1/messages' };

function removeControls(body) {
  for (const key of ['reasoning_effort', 'thinking', 'enable_thinking', 'thinking_budget', 'thinking_budget_tokens', 'thinking_token_budget']) delete body[key];
  for (const [key, controls] of Object.entries({ reasoning: ['effort', 'enabled', 'max_tokens'], output_config: ['effort'],
    chat_template_kwargs: ['enable_thinking', 'preserve_thinking', 'thinking_budget'],
    chat_template_args: ['enable_thinking', 'preserve_thinking', 'thinking_budget'] })) {
    if (!body[key] || typeof body[key] !== 'object') continue;
    for (const control of controls) delete body[key][control];
    if (!Object.keys(body[key]).length) delete body[key];
  }
}

/** Pi has its own saved/default effort. Only the app's explicit session choice
 * may override the server default, including resumed tasks and compaction. */
export function reasoningFetch(fetchImpl, config) {
  const api = config.api || 'openai-completions';
  const base = new URL(config.baseUrl);
  const prefix = base.pathname.replace(/\/+$/, '');
  const suffix = api === 'anthropic-messages' && prefix.endsWith('/v1') ? 'messages' : paths[api];
  const pathname = `${prefix}/${suffix}`;
  return async (input, init) => {
    const url = new URL(input instanceof Request ? input.url : String(input));
    if (url.origin !== base.origin || url.pathname !== pathname || (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase() !== 'POST') return fetchImpl(input, init);
    const text = typeof init?.body === 'string' ? init.body : input instanceof Request && init?.body === undefined ? await input.clone().text() : null;
    if (text === null) return fetchImpl(input, init);
    let body;
    try { body = JSON.parse(text); } catch { return fetchImpl(input, init); }
    if (body?.model !== config.model) return fetchImpl(input, init);
    if (!config.thinkingLevel) removeControls(body);
    else if (isDeepSeekEndpoint(config)) {
      removeControls(body);
      body.thinking = { type: config.thinkingLevel === 'off' ? 'disabled' : 'enabled' };
      if (config.thinkingLevel !== 'off') body.reasoning_effort = config.thinkingLevel;
    }
    return fetchImpl(input, { ...init, body: JSON.stringify(body) });
  };
}
