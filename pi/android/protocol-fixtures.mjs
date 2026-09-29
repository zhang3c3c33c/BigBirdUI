export function events(api, tool, toolName = 'observe', args = '{}', answer = '已查看，中文与 Emoji 👋。') {
  if (api === 'openai-completions') return [{ choices: [{ index: 0, delta: tool
    ? { tool_calls: [{ index: 0, id: 'call_fixture', type: 'function', function: { name: toolName, arguments: args } }] }
    : { content: answer }, finish_reason: null }] }, { choices: [{ index: 0, delta: {}, finish_reason: tool ? 'tool_calls' : 'stop' }] }];
  if (api === 'anthropic-messages') return [
    { type: 'message_start', message: { id: 'msg_fixture', type: 'message', role: 'assistant', model: 'fixture', content: [], usage: { input_tokens: 10, output_tokens: 0 } } },
    { type: 'content_block_start', index: 0, content_block: tool ? { type: 'tool_use', id: 'call_fixture', name: toolName, input: {} } : { type: 'text', text: '' } },
    { type: 'content_block_delta', index: 0, delta: tool ? { type: 'input_json_delta', partial_json: args } : { type: 'text_delta', text: answer } },
    { type: 'content_block_stop', index: 0 },
    { type: 'message_delta', delta: { stop_reason: tool ? 'tool_use' : 'end_turn', stop_sequence: null }, usage: { output_tokens: 8 } },
    { type: 'message_stop' },
  ];
  const item = tool ? { type: 'function_call', id: 'fc_fixture', call_id: 'call_fixture', name: toolName, arguments: args }
    : { type: 'message', id: 'msg_fixture', role: 'assistant', status: 'completed', content: [{ type: 'output_text', text: answer, annotations: [] }] };
  return [
    { type: 'response.created', response: { id: 'resp_fixture', status: 'in_progress', model: 'fixture', output: [] } },
    { type: 'response.output_item.added', output_index: 0, item: tool ? { ...item, arguments: '' } : { ...item, content: [] } },
    ...(tool ? [{ type: 'response.function_call_arguments.delta', item_id: item.id, output_index: 0, delta: args }]
      : [{ type: 'response.content_part.added', item_id: item.id, output_index: 0, content_index: 0, part: { type: 'output_text', text: '', annotations: [] } },
        { type: 'response.output_text.delta', item_id: item.id, output_index: 0, content_index: 0, delta: answer }]),
    { type: 'response.output_item.done', output_index: 0, item },
    { type: 'response.completed', response: { id: 'resp_fixture', status: 'completed', output: [item], usage: { input_tokens: 10, output_tokens: 8, total_tokens: 18 } } },
  ];
}
