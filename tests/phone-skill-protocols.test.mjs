import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createServer } from 'node:http';
import { createAgentSession, DefaultResourceLoader, ModelRuntime, SessionManager, SettingsManager } from '@earendil-works/pi-coding-agent';
import androidExtension from '../pi/android/extension.ts';
import { PHONE_SKILL_ROOT } from '../pi/phone-skill.ts';
import { events } from '../pi/android/protocol-fixtures.mjs';
import { SUPPORTED_APIS } from '../pi/android/runtime-config.mjs';

// Real Pi loop and adapters, deterministic loopback provider; no external account is used.
test('three protocols execute packaged-guide, task, text-question and phone tools with one Pi loop', async () => {
  const home = await mkdtemp(path.join(tmpdir(), 'bbui-skill-protocol-'));
  process.env.PI_MEMORY_DIR = path.join(home, 'memory');
  process.env.PI_CODING_AGENT_DIR = home;
  await writeFile(path.join(home, 'web-search.json'), '{}');
  let api, requests = 0, actions = 0, questions = 0, failure;
  const steps = [
    { name: 'read', args: { path: path.join(PHONE_SKILL_ROOT, 'SKILL.md') } },
    { name: 'task_state', args: { action: 'update', task: { version: 1, id: 'fixture', goal: '本地接口验收', status: 'waiting_user', summary: '等待收件人', unknowns: [{ text: '收件人', resolveBy: 'user' }] } } },
    { name: 'ask_user_question', args: { questions: [{ header: '收件人', question: '发给谁？', multiSelect: false, options: [] }] } },
    { name: 'phone_action', args: { 操作: '查看', 意图: '查看本地模拟画面', 参数: { 屏幕会话: 'virtual' } } },
    { name: 'read', args: { path: path.join(home, 'private.md') } },
  ];
  const server = createServer(async (req, res) => {
    try {
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks).toString() || '{}');
      if (req.url === '/stop') { res.end('{}'); return; }
      if (req.url === '/questions') {
        questions++; assert.equal(actions, 0); assert.deepEqual(body.questions[0].options, []);
        res.end(JSON.stringify({ cancelled: false, answers: [{ questionId: 'q1', selected: [], text: '张三' }] })); return;
      }
      if (req.url === '/action') {
        actions++; assert.equal(questions, 1); assert.equal(body.操作, '查看');
        res.end(JSON.stringify({ content: [{ type: 'text', text: 'fixture observation' }], details: { 状态: '已观察', 屏幕会话: 'virtual', 截图编号: 'fixture' } })); return;
      }
      const wire = JSON.stringify(body);
      assert.match(wire, /BBUI手机助手/); assert.match(wire, /<skills>/);
      assert.doesNotMatch(wire, /expert coding assistant/);
      if (requests === 0) assert.doesNotMatch(wire, /name: phone-operation/);
      if (requests > 0) assert.match(wire, /name: phone-operation/, 'actual read body reaches the model');
      if (requests >= 3) assert.match(wire, /张三/);
      if (requests === steps.length) assert.match(wire, /指南读取失败/);
      const step = steps[requests++];
      res.writeHead(200, { 'Content-Type': 'text/event-stream' });
      for (const event of events(api, Boolean(step), step?.name, JSON.stringify(step?.args || {}), '本地接口验收完成')) {
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
      requests = 0; actions = 0; questions = 0; failure = undefined;
      const settings = SettingsManager.inMemory({ compaction: { enabled: false }, retry: { enabled: false } });
      const runtime = await ModelRuntime.create({ modelsPath: path.join(home, 'models.json'), authPath: path.join(home, 'auth.json'), refreshOnCreate: false });
      runtime.registerProvider('fixture', { baseUrl: api === 'anthropic-messages' ? url : url + '/v1', apiKey: 'fixture', api,
        models: [{ id: 'fixture', name: 'fixture', reasoning: false, input: ['text', 'image'], contextWindow: 100000, maxTokens: 4096,
          cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } }] });
      const loader = new DefaultResourceLoader({ cwd: home, agentDir: home, settingsManager: settings,
        noExtensions: true, noSkills: true, noContextFiles: true, noThemes: true, noPromptTemplates: true,
        additionalSkillPaths: [PHONE_SKILL_ROOT], extensionFactories: [androidExtension, agentTools] });
      await loader.reload();
      const { session, extensionsResult } = await createAgentSession({ cwd: home, agentDir: home, modelRuntime: runtime,
        model: runtime.getModel('fixture', 'fixture'), resourceLoader: loader, sessionManager: SessionManager.inMemory(home), settingsManager: settings,
        tools: ['read', 'task_state', 'ask_user_question', 'phone_action'] });
      assert.deepEqual(extensionsResult.errors, []);
      await session.bindExtensions({ mode: 'print' });
      try {
        await session.prompt('执行本地固定接口验收');
        if (failure) throw failure;
        assert.equal(requests, steps.length + 1, api); assert.equal(actions, 1); assert.equal(questions, 1);
        const results = session.messages.filter(message => message.role === 'toolResult');
        assert.deepEqual(results.map(message => message.toolName), steps.map(step => step.name));
        assert.equal(results.at(-1).isError, true, 'read denial returns a tool failure and the loop can continue');
        assert.equal(results.slice(0, -1).some(message => message.isError), false);
      } finally { session.dispose(); }
    }
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});
