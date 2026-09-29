export function mergeDiscoveredModels(saved: any[], discovered: any[]) {
  const merged = saved.filter(model => model.id?.trim()).map(model => ({ ...discovered.find(item => item.id === model.id),
    ...Object.fromEntries(Object.entries(model).filter(([, value]) => value != null)) }));
  for (const model of discovered) if (!merged.some(item => item.id === model.id)) merged.push(model);
  return merged;
}

export function ModelCapabilities({ model, change }: { model: any; change(patch: any): void }) {
  return <details><summary>模型能力设置</summary>
    <p>获取模型会补全接口提供的能力；缺失项可按模型文档设置，已有设置会保留。</p>
    {(['input', 'reasoning'] as const).map(key => <label key={key}>{key === 'input' ? '图片输入' : '思考能力'}<select aria-label={key === 'input' ? '图片输入' : '思考能力'}
      value={model[key] == null ? '' : String(key === 'input' ? model.input.includes('image') : model.reasoning)}
      onChange={event => change({ [key]: event.target.value === '' ? undefined : key === 'input'
        ? event.target.value === 'true' ? ['text', 'image'] : ['text'] : event.target.value === 'true' })}>
      <option value="">未设置</option><option value="true">支持</option><option value="false">不支持</option>
    </select></label>)}
    {([['contextWindow', '上下文长度', 128000], ['maxTokens', '最大输出长度', 8192]] as const).map(([key, label, budget]) => <label key={key}>{label}<input type="number" min="1" step="1"
      placeholder={`未提供，应用预算 ${budget}`} value={model[key] ?? ''} onChange={event => change({ [key]: event.target.value === '' ? undefined : Number(event.target.value) })} /></label>)}
  </details>;
}
