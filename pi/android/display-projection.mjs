// Only explicitly produced presentation metadata may cross into chat snapshots.
export function displayMetadata(value) {
  if (!value || typeof value !== 'object') return undefined;
  const result = {};
  for (const key of ['kind', 'title', 'summary', 'status']) {
    if (typeof value[key] === 'string') result[key] = value[key].slice(0, key === 'summary' ? 1200 : 160);
  }
  if (Array.isArray(value.sources)) result.sources = value.sources.slice(0, 10).flatMap(source => {
    if (!source || typeof source.url !== 'string') return [];
    try {
      const url = new URL(source.url);
      if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password || source.url.length > 2048) return [];
      return [{ title: String(source.title ?? '').slice(0, 160), url: source.url }];
    } catch { return []; }
  });
  return result;
}

// UI transport only. Never write this projection back to Pi or its session files.
// Tool images/diagnostics belong to the agent context, not the chat renderer.
export function displayResult(result, toolName, failed, status) {
  if (!result || typeof result !== 'object') return result;
  if (toolName === 'read' || result.toolName === 'read' || result.details?.bbuiTool?.kind === 'skill') {
    const isError = failed ?? !!result.isError;
    const envelope = Object.fromEntries(['role', 'toolCallId', 'toolName', 'timestamp']
      .filter(key => Object.hasOwn(result, key)).map(key => [key, result[key]]));
    return { ...envelope, isError, content: [], details: {
      ...(isError ? { 错误: '操作指南读取失败' } : {}),
      bbuiTool: { kind: 'skill', title: '读取手机操作指南', summary: '', status: isError ? 'error' : status ?? 'complete' },
    } };
  }
  const details = { ...(result.details ?? result) };
  // Pi schema/extension failures may carry only text, with no structured details.
  if (result.isError && !details.错误) {
    const text = result.content?.find?.(block => block.type === 'text')?.text;
    if (typeof text === 'string') details.错误 = text.slice(0, 1000);
  }
  const envelope = Object.fromEntries(['role', 'toolCallId', 'toolName', 'isError', 'timestamp']
    .filter(key => Object.hasOwn(result, key)).map(key => [key, result[key]]));
  return { ...envelope, content: [], details: Object.fromEntries(
    ['成功', '错误', '执行', '观察', 'bbuiTask', 'bbuiTool']
      .filter(key => Object.hasOwn(details, key))
      .map(key => [key, key === '执行' || key === '观察'
        ? { 状态: details[key]?.状态 } : key === 'bbuiTool' ? displayMetadata(details[key])
          : key === '错误' ? String(details[key]).slice(0, 1000) : details[key]])),
  };
}

export function displayMessage(message) {
  if (!message || typeof message !== 'object') return message;
  if (message.role === 'toolResult') return displayResult(message);
  if (!Array.isArray(message.content)) return message;
  return { ...message, content: message.content.filter(block => block.type !== 'image')
    .map(block => block.type === 'toolCall' && block.name === 'read' ? { type: 'toolCall', id: block.id, name: block.name, arguments: {} } : block) };
}

export function displayEvent(event) {
  const copy = { ...event };
  if (event.message) copy.message = displayMessage(event.message);
  if (event.messages) copy.messages = event.messages.map(displayMessage);
  if (event.toolResults) copy.toolResults = event.toolResults.map(displayMessage);
  if (event.data?.messages) copy.data = { ...event.data, messages: event.data.messages.map(displayMessage) };
  if (event.toolName === 'read' && event.args) copy.args = {};
  if (event.type === 'tool_execution_end' && event.result) copy.result = displayResult(event.result, event.toolName, event.isError);
  if (event.type === 'tool_execution_update' && event.partialResult) copy.partialResult = displayResult(event.partialResult, event.toolName, undefined, 'running');
  if (event.assistantMessageEvent) {
    const delta = event.assistantMessageEvent;
    const read = delta.toolCall?.name === 'read' || delta.partial?.content?.[delta.contentIndex]?.name === 'read';
    copy.assistantMessageEvent = { ...delta,
      ...(delta.partial ? { partial: displayMessage(delta.partial) } : {}),
      ...(delta.message ? { message: displayMessage(delta.message) } : {}),
      ...(delta.error ? { error: displayMessage(delta.error) } : {}),
      ...(read && delta.toolCall ? { toolCall: { type: 'toolCall', id: delta.toolCall.id, name: 'read', arguments: {} } } : {}),
      ...(read && Object.hasOwn(delta, 'delta') ? { delta: '' } : {}),
    };
  }
  return copy;
}
