import { displayMessage } from '../pi/android/display-projection.mjs';

export function projectMessages(messages, streaming = false) {
  const output = [], calls = new Map();
  for (const [index, raw] of messages.entries()) {
    const message = displayMessage(raw);
    if (message.role === 'toolResult') {
      const part = calls.get(message.toolCallId);
      if (part) {
        const details = message.details || {};
        Object.assign(part, { state: message.isError || details.错误 ? 'error' : 'complete',
          title: details.bbuiTool?.title || part.title, summary: details.bbuiTool?.summary,
          sources: details.bbuiTool?.sources, error: details.错误,
          executionState: details.执行?.状态, observationState: details.观察?.状态 });
      }
      continue;
    }
    if (!['user', 'assistant'].includes(message.role)) continue;
    const active = streaming && index === messages.length - 1 && message.role === 'assistant';
    const id = `${message.timestamp || index}-${index}`;
    const parts = (Array.isArray(message.content) ? message.content : [{ type: 'text', text: String(message.content || '') }]).flatMap((block, n) => {
      const base = { id: `${id}-${n}`, state: active ? 'streaming' : 'complete' };
      if (block.type === 'text') return [{ ...base, type: 'text', text: block.text }];
      if (block.type === 'thinking') return [{ ...base, type: 'reasoning', text: block.thinking }];
      if (block.type === 'toolCall') {
        const part = { ...base, type: 'tool', state: streaming ? 'running' : 'stopped', toolName: block.name,
          toolCallId: block.id, title: block.arguments?.意图 || block.arguments?.intent,
          taskAction: block.name === 'task_state' ? block.arguments?.action : undefined };
        calls.set(block.id, part); return [part];
      }
      return [];
    });
    output.push({ id, role: message.role, status: active ? 'streaming' : message.stopReason === 'error' ? 'error' : 'complete', parts });
  }
  return output;
}
