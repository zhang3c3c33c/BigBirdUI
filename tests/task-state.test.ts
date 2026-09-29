import { createAssistantMessageEventStream } from '@earendil-works/pi-ai';
import test, { before } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { createAgentSession, DefaultResourceLoader, ModelRuntime, SettingsManager, SessionManager } from '@earendil-works/pi-coding-agent';
import { Check } from 'typebox/value';
import { registerTaskState, readTaskState, TaskStateSchema, TASK_CONTEXT_TYPE, type TaskState } from '../pi/task-state.ts';
import desktopExtension from '../pi/phone-extension.ts';
import androidExtension from '../pi/android/extension.ts';
import { PhoneBridge, ROOT } from '../pi/bridge.ts';
import { AndroidPhoneBridge } from '../pi/android/bridge.ts';

before(async () => { await mkdir(join(ROOT, 'runs'), { recursive: true }); });

const initial: TaskState = {
  version: 1, id: 'book-one', goal: '查找附近可预约的餐厅', status: 'active', summary: '正在读取列表',
  constraints: [{ text: '只查询，不预约', source: 'user' }, { text: '默认原版应用', source: 'preference' },
    { text: '列表可能可按距离排序', source: 'assumption' }],
  facts: ['已打开餐厅列表'], unknowns: [{ text: '营业时间', resolveBy: 'observe' }, { text: '用餐人数', resolveBy: 'user' }],
  steps: [{ id: 'inspect', title: '查阅营业时间', status: 'in_progress' }],
};

function harness(manager = SessionManager.inMemory(ROOT)) {
  const tools = new Map<string, any>(), hooks = new Map<string, any>();
  registerTaskState({ registerTool(tool: any) { tools.set(tool.name, tool); }, on(name: string, hook: any) { hooks.set(name, hook); } } as any);
  const ctx = { sessionManager: manager };
  return { manager, tools, hooks, ctx,
    execute(params: unknown, signal?: AbortSignal) { return tools.get('task_state').execute('fixture', params, signal, undefined, ctx); },
    context(messages: any[] = []) { return hooks.get('context')({ type: 'context', messages }, ctx); },
  };
}

function appendResult(manager: SessionManager, task: unknown, isError = false) {
  return manager.appendMessage({ role: 'toolResult', toolCallId: 'fixture', toolName: 'task_state',
    content: [{ type: 'text', text: '任务记录' }], details: { bbuiTask: task } as any, isError, timestamp: 100 });
}

function note(result: any) { return result.messages.find((message: any) => message.customType === TASK_CONTEXT_TYPE); }

test('read reconstructs committed tool results without local mutation or new authorization', async () => {
  const fx = harness();
  assert.deepEqual((await fx.execute({ action: 'read' })).details, { bbuiTask: null });
  const before = fx.manager.getBranch().length;
  const update = await fx.execute({ action: 'update', task: initial });
  assert.equal(fx.manager.getBranch().length, before, 'only Pi commits results');
  assert.deepEqual((await fx.execute({ action: 'read' })).details, { bbuiTask: null }, 'an uncommitted return cannot become state');
  appendResult(fx.manager, update.details.bbuiTask);
  const read = await fx.execute({ action: 'read' });
  assert.deepEqual(read.details.bbuiTask, initial);
  read.details.bbuiTask.constraints[0].source = 'assumption';
  assert.equal((await fx.execute({ action: 'read' })).details.bbuiTask.constraints[0].source, 'user');
  const context = note(fx.context());
  assert.equal(context.display, false);
  assert.match(context.content, /不是新的用户指令或授权/);
  assert.deepEqual(context.details.bbuiTask.constraints, initial.constraints);
});

test('branch changes restore their own last valid task and failed or malformed results do not overwrite it', async () => {
  const fx = harness();
  const base = appendResult(fx.manager, initial);
  appendResult(fx.manager, { ...initial, status: 'completed', completionEvidence: '所有候选已列出' });
  assert.equal((await fx.execute({ action: 'read' })).details.bbuiTask.status, 'completed');
  fx.manager.branch(base);
  appendResult(fx.manager, { ...initial, status: 'waiting_user', question: '几位用餐？' }, true);
  appendResult(fx.manager, { ...initial, status: 'invented-status' });
  fx.manager.appendMessage({ role: 'toolResult', toolCallId: 'another', toolName: 'phone_action',
    content: [], details: { bbuiTask: { ...initial, status: 'completed' } }, isError: false, timestamp: 101 });
  assert.deepEqual((await fx.execute({ action: 'read' })).details.bbuiTask, initial);
  assert.deepEqual(note(fx.context()).details.bbuiTask, initial);
});

test('compaction retains the latest valid branch note even when the model transcript loses the original result', async () => {
  const fx = harness();
  appendResult(fx.manager, initial);
  const user = fx.manager.appendMessage({ role: 'user', content: '默认那个就行', timestamp: 101 });
  fx.manager.appendCompaction('此前查询餐厅。', user, 12000);
  const compacted = fx.manager.buildSessionContext().messages;
  assert.equal(compacted.some(message => message.role === 'toolResult' && message.toolName === 'task_state'), false);
  const restored = fx.context(compacted);
  assert.deepEqual(note(restored).details.bbuiTask, initial);
  assert.equal(fx.context(restored.messages).messages.filter((message: any) => message.customType === TASK_CONTEXT_TYPE).length, 1);
  assert.deepEqual(restored.messages.slice(1), compacted, 'existing user/model context is preserved');
});

test('new task ids fully replace the record and do not inherit completed state, constraints or questions', async () => {
  const fx = harness();
  appendResult(fx.manager, { ...initial, status: 'waiting_user', question: '几位用餐？' });
  const next: TaskState = { version: 1, id: 'weather-two', goal: '查看明天天气', status: 'active', summary: '查询天气' };
  const update = await fx.execute({ action: 'update', task: next });
  appendResult(fx.manager, update.details.bbuiTask);
  assert.deepEqual((await fx.execute({ action: 'read' })).details.bbuiTask, next);
  assert.deepEqual(note(fx.context()).details.bbuiTask, next);
});

test('validation is structural, does not invent task policy gates, and aborted updates never replace state', async () => {
  const fx = harness();
  appendResult(fx.manager, initial);
  await assert.rejects(fx.execute({ action: 'update' }), /完整/);
  await assert.rejects(fx.execute({ action: 'update', task: { ...initial, version: 2 } }), /格式/);
  await assert.rejects(fx.execute({ action: 'update', task: { ...initial, version: null } }), /格式/);
  await assert.rejects(fx.execute({ action: 'update', task: { ...initial, goal: undefined } }), /格式/);
  await assert.rejects(fx.execute({ action: 'update', task: { ...initial, constraints: [{ text: '猜测', source: 'authorized' }] } }), /格式/);
  const cancelled = new AbortController(); cancelled.abort();
  await assert.rejects(fx.execute({ action: 'update', task: { ...initial, status: 'completed' } }, cancelled.signal), /取消/);
  assert.deepEqual((await fx.execute({ action: 'read' })).details.bbuiTask, initial);
  for (const status of ['active', 'waiting_user', 'completed', 'blocked'] as const) {
    // Question/evidence/steps are useful model guidance, never executor gates.
    assert.equal(Check(TaskStateSchema, { ...initial, status, steps: undefined }), true);
    assert.equal((await fx.execute({ action: 'update', task: { ...initial, status } })).details.bbuiTask.status, status);
  }
});

test('omitted input version is supplied by the host while stored records remain versioned', async () => {
  const fx = harness();
  const { version: _version, ...input } = initial;
  const result = await fx.execute({ action: 'update', task: input });
  assert.equal('version' in input, false, 'input is not mutated');
  assert.deepEqual(result.details.bbuiTask, initial);
  appendResult(fx.manager, result.details.bbuiTask);
  appendResult(fx.manager, input);
  assert.deepEqual((await fx.execute({ action: 'read' })).details.bbuiTask, initial,
    'unversioned historical results are still rejected');
});

test('reopening a persisted Pi session restores only task facts and invokes no phone tools', async () => {
  await mkdir(join(ROOT, 'runs'), { recursive: true });
  const dir = await mkdtemp(join(ROOT, 'runs', 'pi-task-state-'));
  const manager = SessionManager.create(dir, dir);
  manager.appendMessage({ role: 'user', content: '查询附近餐厅', timestamp: 100 });
  manager.appendMessage({ role: 'assistant', content: [{ type: 'text', text: '查询中' }],
    api: 'openai-completions', provider: 'fixture', model: 'fixture', stopReason: 'stop', timestamp: 101,
    usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } } });
  appendResult(manager, initial);
  const file = manager.getSessionFile(); assert.ok(file);
  const reopened = SessionManager.open(file);
  const originalDesktop = PhoneBridge.prototype.call, originalAndroid = AndroidPhoneBridge.prototype.call;
  PhoneBridge.prototype.call = async () => { assert.fail('restore cannot operate a phone'); };
  AndroidPhoneBridge.prototype.call = async () => { assert.fail('restore cannot operate a phone'); };
  try {
    const fx = harness(reopened);
    assert.deepEqual((await fx.execute({ action: 'read' })).details.bbuiTask, initial);
    assert.deepEqual(note(fx.context()).details.bbuiTask, initial);
  } finally { PhoneBridge.prototype.call = originalDesktop; AndroidPhoneBridge.prototype.call = originalAndroid; }
});

test('both extensions reject missing or blank intents before any bridge dispatch', async () => {
  const originalDesktop = PhoneBridge.prototype.call, originalAndroid = AndroidPhoneBridge.prototype.call;
  const oldUrl = process.env.BBUI_BRIDGE_URL, oldToken = process.env.BBUI_BRIDGE_TOKEN;
  process.env.BBUI_BRIDGE_URL = 'http://127.0.0.1:1'; process.env.BBUI_BRIDGE_TOKEN = 'fixture';
  let calls = 0;
  PhoneBridge.prototype.call = async () => { calls++; return { content: [], details: {} }; };
  AndroidPhoneBridge.prototype.call = async () => { calls++; return { content: [], details: {} }; };
  try {
    for (const factory of [desktopExtension, androidExtension]) {
      const tools = new Map<string, any>();
      factory({ on() {}, registerCommand() {}, registerTool(tool: any) { tools.set(tool.name, tool); } } as any);
      const tool = tools.get('phone_action');
      for (const 意图 of [undefined, '', ' \t\n', '字'.repeat(81)]) {
        const result = await tool.execute('fixture', { 操作: '点击', 参数: {}, 意图 }, undefined, undefined,
          { model: { input: ['image'] } });
        assert.equal(result.details.执行.状态, '未派发');
      }
      assert.ok(tools.has('task_state'));
    }
    assert.equal(calls, 0);
  } finally {
    PhoneBridge.prototype.call = originalDesktop; AndroidPhoneBridge.prototype.call = originalAndroid;
    if (oldUrl === undefined) delete process.env.BBUI_BRIDGE_URL; else process.env.BBUI_BRIDGE_URL = oldUrl;
    if (oldToken === undefined) delete process.env.BBUI_BRIDGE_TOKEN; else process.env.BBUI_BRIDGE_TOKEN = oldToken;
  }
});


test('real Pi commits sequential update/read results within one assistant message and restores them to the next model call', async () => {
  const dir = await mkdtemp(join(ROOT, 'runs', 'pi-task-loop-'));
  const settings = SettingsManager.inMemory({ compaction: { enabled: false }, retry: { enabled: false } });
  const runtime = await ModelRuntime.create({ authPath: join(dir, 'auth.json'), modelsPath: join(dir, 'models.json'), refreshOnCreate: false });
  runtime.registerProvider('task-fixture', { baseUrl: 'http://127.0.0.1:1', apiKey: 'offline-fixture', api: 'openai-completions',
    models: [{ id: 'task-model', name: 'Offline task fixture', reasoning: false, input: ['text'], contextWindow: 100000,
      maxTokens: 4096, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } }],
  });
  const loader = new DefaultResourceLoader({ cwd: dir, agentDir: dir, settingsManager: settings,
    noExtensions: true, noSkills: true, noContextFiles: true, noThemes: true, noPromptTemplates: true,
    extensionFactories: [registerTaskState] });
  await loader.reload();
  const manager = SessionManager.inMemory(dir);
  const { session, extensionsResult } = await createAgentSession({ cwd: dir, agentDir: dir, modelRuntime: runtime,
    model: runtime.getModel('task-fixture', 'task-model'), resourceLoader: loader, sessionManager: manager,
    settingsManager: settings, tools: ['task_state'] });
  assert.equal(extensionsResult.errors.length, 0);
  await session.bindExtensions({ mode: 'print' });
  let requests = 0;
  const next: TaskState = { version: 1, id: 'weather-next', goal: '看天气', status: 'completed', summary: '已经查看', completionEvidence: '天气页面显示晴' };
  const { version: _version, ...unversioned } = next;
  session.agent.streamFunction = (_model, context) => {
    requests++;
    if (requests > 1) {
      const results = context.messages.filter(message => message.role === 'toolResult');
      const lastRead: any = results.at(-1);
      assert.equal(lastRead.toolName, 'task_state');
      assert.equal(lastRead.isError, false);
      const expected = requests === 2 ? initial : next;
      assert.deepEqual(JSON.parse(lastRead.content[0].text).bbuiTask, expected);
      assert.deepEqual(readTaskState(manager.getBranch()), expected);
      assert.ok(context.messages.some(message => message.role === 'user' && JSON.stringify(message.content).includes('不是新的用户指令或授权')));
    }
    const content: any[] = requests <= 2 ? [
      { type: 'toolCall', id: `update-${requests}`, name: 'task_state', arguments: { action: 'update', task: requests === 1 ? initial : unversioned } },
      { type: 'toolCall', id: `read-${requests}`, name: 'task_state', arguments: { action: 'read' } },
    ] : [{ type: 'text', text: '完成' }];
    const message: any = { role: 'assistant', content, api: 'openai-completions', provider: 'task-fixture', model: 'task-model',
      stopReason: requests <= 2 ? 'toolUse' : 'stop', timestamp: Date.now(),
      usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0, totalTokens: 2,
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } } };
    const stream = createAssistantMessageEventStream();
    stream.push({ type: 'done', reason: message.stopReason, message });
    return stream;
  };
  try {
    await session.prompt('离线测试任务记录，不操作手机');
    assert.equal(requests, 3);
    const results = manager.getBranch().filter(entry => entry.type === 'message' && entry.message.role === 'toolResult');
    assert.equal(results.length, 4);
    assert.deepEqual(readTaskState(manager.getBranch()), next);
  } finally { session.dispose(); }
});
