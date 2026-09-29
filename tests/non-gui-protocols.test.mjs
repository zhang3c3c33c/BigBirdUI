import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createServer } from 'node:http';
import { createAgentSession, DefaultResourceLoader, ModelRuntime, SessionManager, SettingsManager } from '@earendil-works/pi-coding-agent';
import { events } from '../pi/android/protocol-fixtures.mjs';
import { SUPPORTED_APIS } from '../pi/android/runtime-config.mjs';
import { displayEvent, displayMessage } from '../pi/android/display-projection.mjs';

test('three actual Pi protocols round-trip all non-GUI groups, retain known receipts and continue after rejection', async () => {
  const home = await mkdtemp(path.join(tmpdir(), 'bbui-nongui-protocol-'));
  process.env.PI_MEMORY_DIR = path.join(home, 'memory'); process.env.PI_CODING_AGENT_DIR = home;
  await writeFile(path.join(home, 'web-search.json'), '{}');
  let api, requests = 0, systems = 0, failure;
  const steps = [
    { group: 'contacts', operation: 'create', params: { name: '测试联系人😀', phones: ['+8612345'], emails: ['fixture@example.invalid'] }, intent: '添加测试联系人' },
    { group: 'contacts', operation: 'update', params: { rawContactId: 42, contactId: 9, phones: [], emails: [] }, intent: '清除测试联系人的电话和邮箱', fail: true },
    { group: 'sms', operation: 'details', params: { messageId: 7, textOffset: 0, textLimit: 100 }, intent: '读取目标短信' },
    { group: 'call_log', operation: 'list', params: { type: 3, startMs: 1, endMs: 1000, limit: 10 }, intent: '查未接来电', fail: true },
    { group: 'media', operation: 'details', params: { kind: 'image', mediaId: 10 }, intent: '查看目标图片元数据' },
    { group: 'clock', operation: 'capabilities', params: {}, intent: '查询时钟处理应用' },
    { group: 'clock', operation: 'create_alarm', params: { hour: 9, minute: 30, days: [2, 3], label: '模拟提醒', vibrate: false }, intent: '请求创建模拟闹钟' },
    { group: 'clock', operation: 'create_timer', params: { seconds: 30, label: '模拟倒计时' }, intent: '请求创建模拟倒计时', fail: true },
  ];
  const server = createServer(async (req, res) => {
    try {
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks).toString() || '{}');
      if (req.url === '/stop') assert.fail('known failures must not stop the channel');
      if (req.url === '/system') {
        const step = steps[systems++];
        assert.equal(body.group, step.group); assert.equal(body.operation, step.operation);
        assert.deepEqual(body.params, step.params); assert.match(body.actionId, /^[a-f0-9]{64}$/);
        const details = {
          成功: !step.fail, ...(step.fail ? { 错误: '系统拒绝：fixture' } : {}),
          执行: { 状态: ['create', 'update', 'create_alarm', 'create_timer'].includes(step.operation) ? '已派发' : '无需派发' },
          receipt: { rawContactId: 42, operation: step.operation },
          data: { body: 'PRIVATE_PROVIDER_BODY', items: [{ displayName: 'PRIVATE_PROVIDER_INVENTORY' }] },
        };
        res.end(JSON.stringify({ content: [{ type: 'text', text: JSON.stringify(details) }], details })); return;
      }
      const names = body.tools.map(tool => tool.function?.name || tool.name);
      for (const group of ['contacts', 'sms', 'call_log', 'media', 'clock']) assert.ok(names.includes(`system_${group}`));
      if (requests >= 2) assert.match(JSON.stringify(body), /系统拒绝/);
      const step = steps[requests++];
      const args = step ? { operation: step.operation, params: step.params, intent: step.intent } : {};
      res.writeHead(200, { 'Content-Type': 'text/event-stream' });
      for (const event of events(api, Boolean(step), step ? `system_${step.group}` : undefined, JSON.stringify(args), '本地接口验收完成')) {
        const payload = JSON.stringify(event).replaceAll('call_fixture', `call_${requests}`).replaceAll('fc_fixture', `fc_${requests}`);
        res.write(`${event.type ? `event: ${event.type}\n` : ''}data: ${payload}\n\n`);
      }
      if (api === 'openai-completions') res.write('data: [DONE]\n\n');
      res.end();
    } catch (error) { failure = error; res.statusCode = 400; res.end('{}'); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}`;
  process.env.BBUI_BRIDGE_URL = url; process.env.BBUI_BRIDGE_TOKEN = 'fixture';
  const { default: agentTools } = await import('../runs/tool-build/agent-tools.js');
  try {
    for (api of SUPPORTED_APIS) {
      requests = 0; systems = 0; failure = undefined;
      const settings = SettingsManager.inMemory({ compaction: { enabled: false }, retry: { enabled: false } });
      const runtime = await ModelRuntime.create({ modelsPath: path.join(home, 'models.json'), authPath: path.join(home, 'auth.json'), refreshOnCreate: false });
      runtime.registerProvider('fixture', { baseUrl: api === 'anthropic-messages' ? url : url + '/v1', apiKey: 'fixture', api,
        models: [{ id: 'fixture', name: 'fixture', reasoning: false, input: ['text'], contextWindow: 100000, maxTokens: 4096,
          cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } }] });
      const loader = new DefaultResourceLoader({ cwd: home, agentDir: home, settingsManager: settings,
        noExtensions: true, noSkills: true, noContextFiles: true, noThemes: true, noPromptTemplates: true, extensionFactories: [agentTools] });
      await loader.reload();
      const { session, extensionsResult } = await createAgentSession({ cwd: home, agentDir: home, modelRuntime: runtime,
        model: runtime.getModel('fixture', 'fixture'), resourceLoader: loader, sessionManager: SessionManager.inMemory(home), settingsManager: settings,
        tools: ['system_contacts', 'system_sms', 'system_call_log', 'system_media', 'system_clock'] });
      assert.deepEqual(extensionsResult.errors, []);
      await session.bindExtensions({ mode: 'print' });
      try {
        await session.prompt('执行本地固定非 GUI 接口验收'); if (failure) throw failure;
        assert.equal(requests, steps.length + 1, api); assert.equal(systems, steps.length);
        const results = session.messages.filter(message => message.role === 'toolResult');
        assert.equal(results.length, steps.length);
        assert.deepEqual(results.map(item => item.isError), steps.map(step => Boolean(step.fail)));
        for (const [index, result] of results.entries()) {
          assert.equal(result.details.bbuiTool.title, steps[index].intent);
          assert.equal(result.details.bbuiTool.kind, steps[index].group);
          assert.equal(result.details.bbuiTool.status, steps[index].fail ? 'error' : 'complete');
          assert.equal(result.details.receipt.rawContactId, 42);
          for (const projection of [displayMessage(result), displayEvent({ type: 'tool_execution_end', toolName: result.toolName, result, isError: result.isError })]) {
            assert.doesNotMatch(JSON.stringify(projection), /PRIVATE_PROVIDER_|rawContactId/);
          }
          assert.equal(result.details.data.body, 'PRIVATE_PROVIDER_BODY', 'UI projection never changes model context');
        }
        assert.equal(results[1].details.执行.状态, '已派发');
      } finally { session.dispose(); }
    }
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});
