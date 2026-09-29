import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { ModelRuntime } from '@earendil-works/pi-coding-agent';
import { modelConfiguration } from './runtime-config.mjs';

const pixel = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=';
const text = value => ({ role: 'user', content: value, timestamp: Date.now() });
const valid = message => !['error', 'aborted'].includes(message.stopReason);

/** Fixed compatibility checks, with no Agent loop, phone bridge or session history. */
export async function probeWithRuntime(runtime, model, apiKey, signal) {
  const checks = {};
  const options = { apiKey, maxRetries: 0, timeoutMs: 15000, maxTokens: 512, signal };
  try {
    const answer = await runtime.completeSimple(model, { messages: [text('Reply with OK. This is a connection test.')] }, options);
    checks.text = valid(answer) && answer.content.some(block => block.type === 'text' && block.text.trim());
    if (!checks.text) return { ok: false, message: '文字请求未完成，请检查地址、密钥和模型。', checks };
    if (model.input.includes('image')) {
      const answer = await runtime.completeSimple(model, { messages: [text([
        { type: 'text', text: 'This is a synthetic test pixel. Reply with OK.' },
        { type: 'image', data: pixel, mimeType: 'image/png' },
      ])] }, options);
      checks.image = valid(answer) && answer.content.some(block => block.type === 'text' && block.text.trim());
    }
    const context = { messages: [text('Call the connection_test tool once with no arguments. This tool only returns a fixed test string.')],
      tools: [{ name: 'connection_test', description: 'Returns a fixed test string; performs no operation.', parameters: { type: 'object', properties: {}, required: [] } }] };
    const toolAnswer = await runtime.completeSimple(model, context, options);
    const call = valid(toolAnswer) && toolAnswer.content.find(block => block.type === 'toolCall' && block.name === 'connection_test');
    checks.tool = !!call;
    if (call) {
      // Only the named synthetic result is returned. No returned tool can execute code.
      context.messages.push(toolAnswer, { role: 'toolResult', toolCallId: call.id, toolName: call.name,
        content: [{ type: 'text', text: 'Connection test OK.' }], isError: false, timestamp: Date.now() });
      const reply = await runtime.completeSimple(model, context, options);
      checks.toolResult = valid(reply) && reply.content.some(block => block.type === 'text' && block.text.trim());
    }
    const ok = checks.text && checks.tool && checks.toolResult === true && checks.image !== false;
    return { ok, message: ok ? '本次文字、工具往返及已声明的图片输入测试通过。' : '文字请求通过，部分能力未通过本次测试。', checks };
  } catch {
    // Provider errors can include request payloads, endpoints or credentials.
    return { ok: false, message: signal?.aborted ? '连接测试超时。' : '连接测试未完成，请检查网络与模型配置。', checks };
  }
}

export async function probeModel(config, home) {
  const directory = await mkdtemp(path.join(home, 'connection-test-'));
  try {
    await writeFile(path.join(directory, 'models.json'), JSON.stringify(modelConfiguration(config)), { mode: 0o600 });
    const runtime = await ModelRuntime.create({ modelsPath: path.join(directory, 'models.json'),
      authPath: path.join(directory, 'auth.json'), refreshOnCreate: false });
    const model = runtime.getModel(config.provider || 'bbui', config.model);
    if (!model) return { ok: false, message: '未找到所选模型。', checks: {} };
    return await probeWithRuntime(runtime, model, config.apiKey, AbortSignal.timeout(35000));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}
