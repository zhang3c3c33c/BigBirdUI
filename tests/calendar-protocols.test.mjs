import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createServer } from 'node:http';
import { createAgentSession, DefaultResourceLoader, ModelRuntime, SessionManager, SettingsManager } from '@earendil-works/pi-coding-agent';
import { events } from '../pi/android/protocol-fixtures.mjs';
import { SUPPORTED_APIS } from '../pi/android/runtime-config.mjs';

test('three real Pi adapters preserve calendar arguments and failed dispatch receipts through tool_result hook', async () => {
  const home = await mkdtemp(path.join(tmpdir(), 'bbui-calendar-protocol-'));
  process.env.PI_MEMORY_DIR = path.join(home, 'memory'); process.env.PI_CODING_AGENT_DIR = home;
  await writeFile(path.join(home, 'web-search.json'), '{}');
  let api, requests = 0, systems = 0, failure;
  const create = { calendarId: 3, title: '接口验收会议', startMs: 1800000000000, endMs: 1800003600000, timeZone: 'Asia/Shanghai', rrule: 'FREQ=DAILY;COUNT=3', reminderMinutes: [15] };
  const steps = [
    { operation: 'create', params: create, intent: '添加测试会议' },
    { operation: 'update', params: { eventId: 42, scope: 'series', rrule: null, reminderMinutes: [] }, intent: '调整测试会议' },
    { operation: 'details', params: { eventId: 42 }, intent: '核验会议实际状态' },
  ];
  const server = createServer(async (req, res) => {
    try {
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks).toString() || '{}');
      if (req.url === '/stop') assert.fail('a known business failure must not stop the channel');
      if (req.url === '/system') {
        assert.equal(body.group, 'calendar'); assert.equal(body.operation, steps[systems].operation);
        assert.deepEqual(body.params, steps[systems].params); assert.match(body.actionId, /^[a-f0-9]{64}$/);
        const index = systems++;
        const details = index === 1
          ? { 错误: 'Provider拒绝修改：fixture', 执行: { 状态: '已派发' }, receipt: { eventId: 42, calendarId: 3 } }
          : { 成功: true, 执行: { 状态: index === 0 ? '已派发' : '无需派发' }, data: { eventId: 42, event: { id: 42, ...create } } };
        res.end(JSON.stringify({ content: [{ type: 'text', text: JSON.stringify(details) }], details })); return;
      }
      const wire = JSON.stringify(body);
      assert.match(wire, /system_calendar/);
      if (requests >= 2) assert.match(wire, /Provider拒绝修改/);
      const step = steps[requests++];
      res.writeHead(200, { 'Content-Type': 'text/event-stream' });
      for (const event of events(api, Boolean(step), step ? 'system_calendar' : undefined, JSON.stringify(step || {}), '本地日程接口验收完成')) {
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
        model: runtime.getModel('fixture', 'fixture'), resourceLoader: loader, sessionManager: SessionManager.inMemory(home), settingsManager: settings, tools: ['system_calendar'] });
      assert.deepEqual(extensionsResult.errors, []);
      await session.bindExtensions({ mode: 'print' });
      try {
        await session.prompt('执行本地固定日程接口验收'); if (failure) throw failure;
        assert.equal(requests, 4, api); assert.equal(systems, 3);
        const results = session.messages.filter(message => message.role === 'toolResult');
        assert.equal(results.length, 3); assert.deepEqual(results.map(item => item.isError), [false, true, false]);
        assert.equal(results[1].details.执行.状态, '已派发');
        assert.deepEqual(results[1].details.receipt, { eventId: 42, calendarId: 3 });
        assert.equal(results[1].details.bbuiTool.title, '调整测试会议');
        assert.equal(results[1].details.bbuiTool.kind, 'calendar');
        assert.equal(results[0].details.data.eventId, 42);
      } finally { session.dispose(); }
    }
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});
