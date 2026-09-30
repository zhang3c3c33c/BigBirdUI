/** Read only explicit metadata returned by the configured endpoint. No name/provider matching. */
export function discoveredModel(row, connection = {}) {
  if (typeof row?.id !== 'string' || !row.id.trim()) throw new Error('接口返回了无效的模型 ID');
  const name = [row.display_name, row.name].find(value => typeof value === 'string' && value.trim());
  const result = { id: row.id.trim(), name: name || row.id.trim() };
  const modalities = row.input_modalities ?? row.architecture?.input_modalities;
  if (Array.isArray(modalities) && modalities.length) result.input = modalities.includes('image') ? ['text', 'image'] : ['text'];
  const image = row.capabilities?.image_input?.supported;
  if (typeof image === 'boolean') result.input = image ? ['text', 'image'] : ['text'];
  const reasoning = row.capabilities?.thinking?.supported;
  if (typeof reasoning === 'boolean') result.reasoning = reasoning;
  else if (Array.isArray(row.supported_parameters) && row.supported_parameters.some(p => ['reasoning', 'include_reasoning'].includes(p))) result.reasoning = true;
  if (reasoning === true && row.capabilities?.thinking?.types?.adaptive?.supported === true) result.thinkingMode = 'adaptive';
  const positive = value => Number.isInteger(value) && value > 0 && value <= 2147483647;
  const context = row.context_window ?? row.max_input_tokens ?? row.context_length;
  const output = row.max_output_tokens ?? row.max_tokens ?? row.top_provider?.max_completion_tokens;
  if (positive(context)) result.contextWindow = context;
  if (positive(output)) result.maxTokens = output;
  const effort = row.capabilities?.effort;
  if (result.reasoning && effort?.supported === true) {
    result.thinkingLevels = ['low', 'medium', 'high', 'xhigh', 'max']
      .filter(id => effort[id]?.supported === true).map(id => ({ id, label: id }));
  }
  const explicitLevels = row.effort?.supported_levels;
  if (Array.isArray(explicitLevels)) {
    const supported = new Set(['off', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max']);
    const levels = [...new Set(explicitLevels.filter(id => supported.has(id)))];
    if (levels.length && reasoning !== false) result.reasoning = true;
    if (result.reasoning) {
      result.thinkingLevels = levels.map(id => ({ id, label: id === 'off' ? '关闭' : id }));
      if (levels.includes(row.effort.default_level)) result.defaultThinkingLevel = row.effort.default_level;
      // The documented native DeepSeek protocol controls disabling separately from effort.
      let nativeDeepSeek = false;
      try {
        const endpoint = new URL(connection.baseUrl);
        nativeDeepSeek = connection.api === 'openai-completions' && endpoint.origin === 'https://api.deepseek.com'
          && ['', '/v1'].includes(endpoint.pathname.replace(/\/+$/, ''))
          && !endpoint.username && !endpoint.password && !endpoint.search && !endpoint.hash;
      } catch {}
      if (nativeDeepSeek && levels.length && !levels.includes('off')) result.thinkingLevels.unshift({ id: 'off', label: '关闭' });
    }
  }
  return result;
}

export async function discoverModels(connection) {
  const base = new URL(connection.baseUrl.trim().replace(/\/+$/, ''));
  if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) throw new Error('请输入有效 API 地址');
  const anthropic = connection.api === 'anthropic-messages';
  const endpoint = base.href.replace(/\/$/, '') + (anthropic && !base.pathname.endsWith('/v1') ? '/v1/models' : '/models');
  const found = new Map(), cursors = new Set();
  const signal = AbortSignal.timeout(60000);
  let after;
  do {
    const url = new URL(endpoint);
    if (anthropic) { url.searchParams.set('limit', '1000'); if (after) url.searchParams.set('after_id', after); }
    const response = await fetch(url, { redirect: 'error', signal, headers: anthropic
      ? { 'x-api-key': connection.apiKey, 'anthropic-version': '2023-06-01' }
      : { Authorization: `Bearer ${connection.apiKey}` } });
    if (!response.ok) throw new Error(`获取模型失败：HTTP ${response.status}`);
    const chunks = []; let length = 0;
    for await (const chunk of response.body) {
      length += chunk.length;
      if (length > 2 * 1024 * 1024) throw new Error('模型列表响应过大');
      chunks.push(chunk);
    }
    const page = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (!Array.isArray(page.data)) throw new Error('模型列表格式无效');
    for (const row of page.data) { const model = discoveredModel(row, connection); if (!found.has(model.id)) found.set(model.id, model); }
    after = anthropic && page.has_more ? page.last_id : undefined;
    if (anthropic && page.has_more && (typeof after !== 'string' || !after || cursors.has(after))) throw new Error('模型列表分页游标无效');
    if (after) cursors.add(after);
  } while (after);
  return [...found.values()].sort((a, b) => a.id.localeCompare(b.id));
}
