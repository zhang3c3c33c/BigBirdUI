import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { createAgentSession, DefaultResourceLoader, ModelRuntime, SessionManager, SettingsManager } from '@earendil-works/pi-coding-agent';
import { createAssistantMessageEventStream } from '@earendil-works/pi-ai';
import { createHash } from 'node:crypto';
import { PhoneBridge, ROOT, convertContent } from '../pi/bridge.ts';
import { Check } from 'typebox/value';
import extension, { PhoneSchema } from '../pi/phone-extension.ts';
import { desktopPhoneSchema } from '../pi/phone-contract.ts';

test('iPhone schema exposes only declared operations and main-screen semantics', () => {
  const schema = desktopPhoneSchema({ devicePlatform: 'ios', phoneOperations: ['查看', '按键', '输入内容'], phoneKeys: ['主页', '回车'] });
  const valid = (operation: string, params: object) => Check(schema, { 意图: '操作手机', 操作: operation, 参数: params });
  assert.equal(valid('查看', { 屏幕会话: 'main' }), true);
  assert.equal(valid('查看', {}), false);
  assert.equal(valid('按键', { 屏幕会话: 'main', 键名: '主页' }), true);
  assert.equal(valid('按键', { 屏幕会话: 'main', 键名: '返回' }), false);
  for (const operation of ['创建屏幕', '关闭屏幕', '系统面板']) assert.equal(valid(operation, { 屏幕会话: 'main' }), false);
  for (const params of [{ 屏幕会话: 'virtual' }, { 屏幕会话: 'main', 读取节点: true }, { 屏幕会话: 'main', 启动组件: 'Activity' }]) assert.equal(valid('查看', params), false);
  assert.equal(Check(PhoneSchema, { 意图: '创建屏幕', 操作: '创建屏幕', 参数: { 屏幕会话: 'work' } }), true);
});

test('iPhone STOP and uncertainty use the normalized global lease directory across data roots', async () => {
  const previous = { config: process.env.BBUI_CONFIG_FILE, state: process.env.BBUI_DEVICE_STATE_DIR };
  const dir = await mkdtemp(join(ROOT, 'runs', 'pi-ios-state-'));
  try {
    process.env.BBUI_CONFIG_FILE = join(dir, 'config.json');
    process.env.BBUI_DEVICE_STATE_DIR = join(dir, 'must-not-use');
    await writeFile(process.env.BBUI_CONFIG_FILE, JSON.stringify({ serial: 'ab-cd', devicePlatform: 'ios' }));
    const location = await (new PhoneBridge(dir) as any).runDirectory();
    assert.ok(location.endsWith(join('BBUI', 'devices', createHash('sha256').update('ios:ABCD').digest('hex').slice(0, 24))));
    assert.ok(!location.includes('must-not-use'));
    await writeFile(process.env.BBUI_CONFIG_FILE, JSON.stringify({ serial: 'ABCD', devicePlatform: 'ios' }));
    assert.equal(await (new PhoneBridge(ROOT) as any).runDirectory(), location);
  } finally {
    for (const [key, value] of [['BBUI_CONFIG_FILE', previous.config], ['BBUI_DEVICE_STATE_DIR', previous.state]]) {
      if (value === undefined) delete process.env[key!]; else process.env[key!] = value;
    }
  }
});

test('MCP image blocks stay images and queries do not require a wait', () => {
  assert.deepEqual(convertContent([{ type: 'image', data: 'YWJj', mimeType: 'image/png' }]),
    [{ type: 'image', data: 'YWJj', mimeType: 'image/png' }]);
  assert.equal(Check(PhoneSchema, { 意图: '查看当前状态', 操作: '查看', 参数: {} }), true);
});

test('package inventory is available to text-only models', async () => {
  let registered: any;
  extension({ on() {}, registerCommand() {}, registerTool(tool: any) { registered = tool; } } as any);
  const original = PhoneBridge.prototype.call;
  PhoneBridge.prototype.call = async (operation, params) => {
    assert.equal(operation, '列出应用');
    assert.equal(params.关键词, 'bili');
    return { content: [{ type: 'text', text: '{"状态":"应用已列出"}' }], details: { 状态: '应用已列出' } };
  };
  try {
    assert.ok(JSON.stringify(PhoneSchema.properties.操作).includes('列出应用'));
    const result = await registered.execute('inventory-test', { 意图: '查看当前状态', 操作: '列出应用',
      参数: { 执行后等待毫秒: 0, 关键词: 'bili' } }, undefined, undefined, { model: { input: ['text'] } });
    assert.equal(result.details.状态, '应用已列出');
  } finally { PhoneBridge.prototype.call = original; }
});

test('Pi SDK runs phone extension, parallel calls and consumes returned images', async () => {
  await mkdir(join(ROOT, 'runs'), { recursive: true });
  const dir = await mkdtemp(join(ROOT, 'runs', 'pi-offline-'));
  const settings = SettingsManager.inMemory({ compaction: { enabled: false }, retry: { enabled: false } });
  const runtime = await ModelRuntime.create({ authPath: join(dir, 'auth.json'), modelsPath: join(dir, 'models.json'), refreshOnCreate: false });
  runtime.registerProvider('bbui-test', {
    baseUrl: 'http://127.0.0.1:1', apiKey: 'offline-test-placeholder', api: 'openai-completions',
    models: [{ id: 'vision-test', name: 'Offline test', reasoning: false, input: ['text', 'image'],
      contextWindow: 100000, maxTokens: 4096, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } }],
  });
  const loader = new DefaultResourceLoader({ cwd: dir, agentDir: dir, settingsManager: settings,
    noExtensions: true, noSkills: true, noContextFiles: true, noThemes: true, noPromptTemplates: true,
    extensionFactories: [extension],
  });
  await loader.reload();
  const { session, extensionsResult } = await createAgentSession({ cwd: dir, agentDir: dir,
    modelRuntime: runtime, model: runtime.getModel('bbui-test', 'vision-test'), resourceLoader: loader,
    sessionManager: SessionManager.inMemory(dir), settingsManager: settings, tools: ['phone_action', 'task_state'],
  });
  assert.equal(extensionsResult.errors.length, 0);
  await session.bindExtensions({ mode: 'print' });
  assert.deepEqual(session.getActiveToolNames().sort(), ['phone_action', 'task_state']);
  let active = 0, peak = 0, requests = 0;
  const seen: any[] = [];
  const original = PhoneBridge.prototype.call;
  PhoneBridge.prototype.call = async (_operation, params) => {
    seen.push(params);
    peak = Math.max(peak, ++active);
    await new Promise(resolve => setTimeout(resolve, 30));
    active--;
    return { content: [{ type: 'text', text: JSON.stringify({ 屏幕会话: params.屏幕会话, 状态: '已观察', 截图编号: 'new' }) },
      { type: 'image', mimeType: 'image/png', data: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==' }], details: {} };
  };
  session.agent.streamFunction = (_model, context) => {
    requests++;
    const stream = createAssistantMessageEventStream();
    const content: any[] = requests === 1 ? ['a', 'b'].map(name => ({
      type: 'toolCall', id: `call-${name}`, name: 'phone_action',
      arguments: { 意图: '查看当前状态', 操作: '查看', 参数: { 屏幕会话: name, 执行后等待毫秒: 0 } },
    })) : [{ type: 'text', text: 'Screens observed.' }];
    if (requests === 2) {
      const results = context.messages.filter(m => m.role === 'toolResult');
      assert.equal(results.length, 2);
      assert.ok(results.every((m: any) => !m.isError && m.content.some((c: any) => c.type === 'image')));
    }
    const message: any = { role: 'assistant', content, api: 'openai-completions', provider: 'bbui-test', model: 'vision-test',
      stopReason: requests === 1 ? 'toolUse' : 'stop', timestamp: Date.now(),
      usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0, totalTokens: 2, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } } };
    stream.push({ type: 'done', reason: message.stopReason, message });
    return stream;
  };
  try {
    await session.prompt('Observe two phone screens in parallel.');
    assert.equal(requests, 2);
    assert.equal(peak, 2);
    assert.equal(seen.length, 2);
    assert.ok(seen.every(p => typeof p.动作编号 === 'string' && p.动作编号.length === 64));
  } finally {
    PhoneBridge.prototype.call = original;
    session.dispose();
  }
});

test('MCP tool errors preserve dispatch facts and error screenshots', async () => {
  const bridge = new PhoneBridge();
  const content = [{ type: 'text', text: JSON.stringify({ 执行: { 状态: '未派发' }, 观察: { 状态: '已取得' } }) },
    { type: 'image', data: 'evidence', mimeType: 'image/png' }];
  (bridge as any).client = { async callTool() { return { isError: true, content }; } };
  const result = await bridge.call('点击', {});
  assert.deepEqual(result.content, content);
  assert.equal(result.details.执行.状态, '未派发');
  assert.equal(result.details.工具错误, true);
  assert.equal((bridge as any).inputUncertain, false);
});

test('MCP uncertainty preserves observations but cannot be cleared by a fresh picture', async () => {
  const bridge = new PhoneBridge();
  const calls: string[] = [];
  let quarantines = 0;
  (bridge as any).quarantineInput = async () => { quarantines++; };
  (bridge as any).client = { async callTool(request: any) {
    const operation = request.arguments.操作; calls.push(operation);
    if (operation === '点击') throw new Error('response lost');
    return { content: [{ type: 'text', text: '{"观察":{"状态":"已取得"}}' }] };
  } };
  assert.equal((await bridge.call('点击', {})).details.执行.状态, '未知');
  assert.equal((await bridge.call('查看', {})).details.观察.状态, '已取得');
  assert.equal((await bridge.call('点击', {})).details.执行.状态, '未派发');
  assert.equal(quarantines, 1);
  assert.deepEqual(calls, ['点击', '查看']);
});

test('desktop extension does not terminate after 100 tool calls', async () => {
  let tool: any;
  extension({ on() {}, registerCommand() {}, registerTool(value: any) { tool = value; } } as any);
  const original = PhoneBridge.prototype.call;
  let calls = 0;
  PhoneBridge.prototype.call = async (_operation, params) => {
    calls++; assert.equal(params.执行后等待毫秒, 0); return { content: [], details: {} };
  };
  try {
    for (let i = 0; i < 105; i++) await tool.execute(`query-${i}`, { 意图: '查看当前状态', 操作: '列出屏幕', 参数: {} },
      undefined, undefined, { model: { input: ['text'] }, abort() { assert.fail('tool must not end the agent task'); } });
    assert.equal(calls, 105);
  } finally { PhoneBridge.prototype.call = original; }
});


async function recoveryFixture() {
  const dir = await mkdtemp(join(ROOT, 'runs', 'pi-recovery-'));
  await writeFile(join(dir, 'config.local.json'), JSON.stringify({ serial: 'fixture-device' }));
  const run = join(dir, 'runs', 'fixture-device');
  await mkdir(run, { recursive: true });
  const bridge = new PhoneBridge(dir);
  const internals = bridge as any;
  internals.inputUncertain = true;
  const marker = join(run, 'INPUT_UNCERTAIN');
  await writeFile(marker, JSON.stringify({ owner: internals.owner, reason: 'fixture' }));
  return { bridge, internals, marker, run };
}

test('host recovery waits for exit and lease release, clears only owned quarantine, and only observes', async () => {
  const fx = await recoveryFixture();
  const events: string[] = [];
  await writeFile(join(fx.run, 'STOP'), 'User explicitly stopped');
  fx.internals.userStopped = true;
  let confirm!: () => void;
  const exitState = { ended: false, promise: new Promise<void>(resolve => { confirm = resolve; }) };
  fx.internals.exitState = exitState;
  fx.internals.client = { async close() { events.push('closed'); exitState.ended = true; confirm(); } };
  fx.internals.verifyLeaseReleased = async () => { events.push('lease'); };
  fx.internals.connect = async () => ({ async callTool(request: any) {
    events.push(request.arguments.操作);
    await assert.rejects(readFile(fx.marker), { code: 'ENOENT' });
    return { content: [{ type: 'text', text: JSON.stringify({ 截图编号: 'fresh', 观察: { 状态: '已取得' } }) }] };
  } });
  const result = await fx.bridge.recover();
  assert.equal(result.用户STOP保持不变, true);
  assert.equal(await readFile(join(fx.run, 'STOP'), 'utf8'), 'User explicitly stopped');
  assert.deepEqual(events, ['closed', 'lease', '列出屏幕', '查看']);
  assert.match(result.说明, /未创建虚拟屏/);
  assert.equal(fx.internals.inputUncertain, false);
  assert.equal((await fx.bridge.call('点击', {})).details.执行.状态, '未派发');
});

test('recovery refuses foreign markers and cannot clear a quarantine without confirmed process exit', async () => {
  const fx = await recoveryFixture();
  await writeFile(fx.marker, JSON.stringify({ owner: 'another-host' }));
  await assert.rejects(fx.bridge.recover(), /不属于当前桥接实例/);
  assert.equal(JSON.parse(await readFile(fx.marker, 'utf8')).owner, 'another-host');
  await writeFile(fx.marker, JSON.stringify({ owner: fx.internals.owner }));
  fx.internals.exitState = { ended: false, promise: Promise.resolve() };
  fx.internals.client = { async close() {} };
  fx.internals.verifyLeaseReleased = async () => { assert.fail('unconfirmed exit must not reach lease probe'); };
  await assert.rejects(fx.bridge.recover(), /旧进程退出确认/);
  assert.equal(JSON.parse(await readFile(fx.marker, 'utf8')).owner, fx.internals.owner);
  assert.equal(fx.internals.inputUncertain, true);
});

test('lease conflict leaves the fault marker intact', async () => {
  const fx = await recoveryFixture();
  fx.internals.exitState = { ended: true, promise: Promise.resolve() };
  fx.internals.verifyLeaseReleased = async () => { throw new Error('device lease is still occupied'); };
  await assert.rejects(fx.bridge.recover(), /lease is still occupied/);
  assert.equal(JSON.parse(await readFile(fx.marker, 'utf8')).owner, fx.internals.owner);
});

test('recovery command is a user command and never exposed as a model tool', () => {
  const commands: string[] = [], tools: string[] = [];
  extension({ on() {}, registerCommand(name: string) { commands.push(name); },
    registerTool(tool: any) { tools.push(tool.name); } } as any);
  assert.ok(commands.includes('phone-recover'));
  assert.deepEqual(tools.sort(), ['phone_action', 'read', 'task_state']);
  assert.equal(Check(PhoneSchema, { 意图: '查看当前状态', 操作: '恢复连接', 参数: {} }), false);
});


test('recovery waits for a late old-call quarantine write before clearing the owned marker', async () => {
  const fx = await recoveryFixture();
  fx.internals.inputUncertain = false;
  let rejectRequest!: (error: Error) => void, started!: () => void, confirm!: () => void;
  const start = new Promise<void>(resolve => { started = resolve; });
  const exitState = { ended: false, promise: new Promise<void>(resolve => { confirm = resolve; }) };
  fx.internals.exitState = exitState;
  let lateWriteDone = false;
  fx.internals.quarantineInput = async () => {
    await new Promise(resolve => setTimeout(resolve, 10));
    await writeFile(fx.marker, JSON.stringify({ owner: fx.internals.owner, reason: 'late fault' }));
    lateWriteDone = true;
  };
  fx.internals.client = {
    async callTool() { started(); return new Promise((_resolve, reject) => { rejectRequest = reject; }); },
    async close() { rejectRequest(new Error('old transport closed')); exitState.ended = true; confirm(); },
  };
  const oldCall = fx.bridge.call('点击', {});
  await start;
  fx.internals.verifyLeaseReleased = async () => { assert.equal(lateWriteDone, true); };
  const originalConnect = fx.internals.connect.bind(fx.bridge);
  fx.internals.connect = async () => fx.internals.client ? originalConnect() : ({
    async callTool() { return { content: [{ type: 'text', text: '{"截图编号":"fresh","观察":{"状态":"已取得"}}' }] }; },
  });
  await fx.bridge.recover();
  assert.equal((await oldCall).details.执行.状态, '未知');
  await assert.rejects(readFile(fx.marker), { code: 'ENOENT' });
  assert.equal(fx.internals.inputUncertain, false);
});

test('recovery requires an actual new successful observation, not merely an error-free tool response', async () => {
  for (const details of [{}, { 观察: { 状态: '已取得' } }, { 截图编号: 'fresh', 观察: { 状态: '失败' } }]) {
    const fx = await recoveryFixture();
    fx.internals.exitState = { ended: true, promise: Promise.resolve() };
    fx.internals.verifyLeaseReleased = async () => {};
    fx.internals.connect = async () => ({ async callTool() {
      return { content: [{ type: 'text', text: JSON.stringify(details) }] };
    } });
    await assert.rejects(fx.bridge.recover(), /未取得新画面/);
  }
});

test('MCP transport loss declares control disconnection and preserves uncertain dispatch', async () => {
  const fx = await recoveryFixture(); fx.internals.inputUncertain = false;
  fx.internals.connect = async () => ({ async callTool() { throw new Error('transport closed'); } });
  const result = await fx.bridge.call('点击', {});
  assert.equal(result.details.执行.状态, '未知');
  assert.equal(result.details.通道.控制, '断开');
});
