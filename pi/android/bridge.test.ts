import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { AndroidPhoneBridge } from './bridge.js';
import androidPhoneExtension, { ANDROID_PHONE_INSTRUCTIONS, phoneEnvironmentInstructions } from './extension.js';
import { PhoneSchema, AndroidPhoneSchema, PHONE_INSTRUCTIONS } from '../phone-contract.js';
import { Check } from 'typebox/value';
import { PHONE_SYSTEM_PROMPT } from '../phone-skill.js';

test('host environment facts distinguish new, reused and rebuilt displays without replay', () => {
  assert.equal(phoneEnvironmentInstructions({}), '');
  assert.equal(phoneEnvironmentInstructions({ BBUI_ENVIRONMENT_ID: 'invalid\nextra' }), '');
  const fresh = phoneEnvironmentInstructions({ BBUI_ENVIRONMENT_ID: 'device-new', BBUI_ENVIRONMENT_REBUILT: 'true' });
  assert.match(fresh, /本次新建/);
  const rebuilt = phoneEnvironmentInstructions({ BBUI_ENVIRONMENT_ID: 'device-new', BBUI_PREVIOUS_ENVIRONMENT_ID: 'device-old', BBUI_ENVIRONMENT_REBUILT: 'true' });
  assert.match(rebuilt, /已重建.*之前的应用页面不保证保留/);
  const reused = phoneEnvironmentInstructions({ BBUI_ENVIRONMENT_ID: 'device-new', BBUI_ENVIRONMENT_REBUILT: 'false' });
  assert.match(reused, /沿用现有虚拟屏/);
  for (const prompt of [fresh, rebuilt, reused]) {
    assert.match(prompt, /先调用查看/);
    assert.match(prompt, /禁止重放历史动作/);
  }
});

async function fixture(handler: (req: IncomingMessage, res: ServerResponse) => void | Promise<void>) {
  const server = createServer((req, res) => {
    assert.equal(req.headers.authorization, 'Bearer fixture');
    assert.equal(req.headers['content-type'], 'application/json; charset=utf-8');
    void handler(req, res);
  });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  assert.ok(address && typeof address === 'object');
  return {
    bridge: new AndroidPhoneBridge(`http://127.0.0.1:${address.port}`, 'fixture'),
    async close() {
      server.closeAllConnections();
      await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
    },
  };
}

test('abort sends STOP but preserves an already dispatched action outcome', async () => {
  let releaseAction: (() => void) | undefined;
  let actionStarted: (() => void) | undefined;
  let sawStop: (() => void) | undefined;
  const dispatched = new Promise<void>(resolve => { actionStarted = resolve; });
  const stopped = new Promise<void>(resolve => { sawStop = resolve; });
  const fx = await fixture(async (req, res) => {
    if (req.url === '/action') {
      actionStarted!();
      await new Promise<void>(resolve => { releaseAction = resolve; });
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ content: [{ type: 'text', text: 'applied exactly once' }], details: { applied: true } }));
    } else { sawStop!(); res.end('{}'); }
  });
  try {
    const controller = new AbortController();
    const pending = fx.bridge.call('点击', { 执行后等待毫秒: 0 }, controller.signal);
    await dispatched;
    controller.abort();
    await stopped;
    releaseAction!();
    const result = await pending;
    assert.equal(result.details.applied, true);
  } finally { await fx.close(); }
});

test('server failure stops and prevents automatic replay', async () => {
  let actions = 0, stops = 0;
  const fx = await fixture((req, res) => {
    if (req.url === '/action') { actions++; res.statusCode = 500; res.end('{"error":"input outcome unknown"}'); }
    else { stops++; res.end('{}'); }
  });
  try {
    assert.equal(((await fx.bridge.call('点击', {})).details.执行 as any).状态, '未知');
    assert.equal(((await fx.bridge.call('点击', {})).details.执行 as any).状态, '未派发');
    assert.equal(actions, 1);
    assert.equal(stops, 1);
  } finally { await fx.close(); }
});

test('known stale-observation rejection still allows fresh observation', async () => {
  let actions = 0;
  const fx = await fixture((_req, res) => {
    if (++actions === 1) { res.statusCode = 409; res.end('{"error":"stale observation"}'); }
    else res.end('{"content":[],"details":{"fresh":true}}');
  });
  try {
    assert.match(String((await fx.bridge.call('点击', {})).details.错误), /stale observation/);
    assert.equal((await fx.bridge.call('查看', {})).details.fresh, true);
  } finally { await fx.close(); }
});

test('an already cancelled action never reaches the device', async () => {
  let requests = 0;
  const fx = await fixture((_req, res) => { requests++; res.end('{}'); });
  try {
    const controller = new AbortController(); controller.abort();
    assert.match(String((await fx.bridge.call('点击', {}, controller.signal)).details.错误), /派发前/);
    assert.equal(requests, 0);
  } finally { await fx.close(); }
});

test('device bridge rejects non-local endpoints', () => {
  assert.throws(() => new AndroidPhoneBridge('https://example.com', 'token'), /loopback/);
  assert.throws(() => new AndroidPhoneBridge('http://192.168.1.1', 'token'), /loopback/);
});

test('UTF-8 charset preserves public Chinese keys and input text', async () => {
  let received: Record<string, any> | undefined;
  const fx = await fixture(async (req, res) => {
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(Buffer.from(chunk));
    received = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    res.end('{"content":[],"details":{}}');
  });
  try {
    await fx.bridge.call('输入内容', { 内容: '你好，世界 👋', 执行后等待毫秒: 0 });
    assert.deepEqual(received, { 操作: '输入内容', 参数: { 内容: '你好，世界 👋', 执行后等待毫秒: 0 } });
  } finally { await fx.close(); }
});

test('tool-level isError retains structured facts and screenshot without closing the channel', async () => {
  let requests = 0;
  const fx = await fixture((_req, res) => {
    if (++requests === 1) res.end(JSON.stringify({ isError: true,
      content: [{ type: 'text', text: '截图编号已失效' }, { type: 'image', data: 'fixture', mimeType: 'image/png' }], details: { 执行: { 状态: '未派发' }, 观察: { 状态: '已取得' } } }));
    else res.end('{"content":[],"details":{"fresh":true}}');
  });
  try {
    const result = await fx.bridge.call('点击', {});
    assert.equal(result.details.工具错误, true);
    assert.equal((result.details.执行 as any).状态, '未派发');
    assert.ok(result.content.some(item => item.type === 'image' && item.data === 'fixture'));
    assert.equal((await fx.bridge.call('查看', {})).details.fresh, true);
    assert.equal(requests, 2);
  } finally { await fx.close(); }
});

test('intent is a required bounded display field for new tool calls', () => {
  const action = { 意图: '查看当前状态', 操作: '查看', 参数: { 执行后等待毫秒: 0 } };
  assert.equal(Check(PhoneSchema, action), true);
  const { 意图: _intent, ...missingIntent } = action;
  assert.equal(Check(PhoneSchema, missingIntent), false);
  for (const 意图 of ['', '   ', '\t\n']) assert.equal(Check(PhoneSchema, { ...action, 意图 }), false);
  assert.equal(Check(PhoneSchema, { ...action, 意图: '搜索天气' }), true);
  assert.equal(Check(PhoneSchema, { ...action, 意图: '字'.repeat(80) }), true);
  assert.equal(Check(PhoneSchema, { ...action, 意图: '字'.repeat(81) }), false);
  assert.equal(Check(PhoneSchema, { ...action, 意图: { secret: 'not text' } }), false);
  for (const prompt of [PHONE_INSTRUCTIONS, ANDROID_PHONE_INSTRUCTIONS]) {
    assert.ok(prompt.includes('顶层意图'));
    assert.ok(prompt.includes('不能宣称操作成功'));
    assert.ok(prompt.includes('密码、验证码、API Key或令牌'));
  }
});

test('changing display intent never changes device parameters or action identity', async () => {
  let tool: any;
  const oldUrl = process.env.BBUI_BRIDGE_URL;
  const oldToken = process.env.BBUI_BRIDGE_TOKEN;
  const original = AndroidPhoneBridge.prototype.call;
  const calls: Array<{ operation: string; params: Record<string, unknown> }> = [];
  process.env.BBUI_BRIDGE_URL = 'http://127.0.0.1:1';
  process.env.BBUI_BRIDGE_TOKEN = 'fixture';
  AndroidPhoneBridge.prototype.call = async (operation, params) => {
    calls.push({ operation, params });
    return { content: [], details: {} };
  };
  try {
    androidPhoneExtension({ on() {}, registerTool(value: any) { tool = value; } } as any);
    const params = { 意图: '查看当前状态', 操作: '查看', 参数: { 执行后等待毫秒: 0 } };
    await tool.execute('stable-call-id', { ...params, 意图: '查看搜索结果' }, undefined, undefined,
      { model: { input: ['image'] } });
    await tool.execute('stable-call-id', { ...params, 意图: '确认当前画面' }, undefined, undefined,
      { model: { input: ['image'] } });
    assert.deepEqual(calls[0], calls[1]);
    assert.equal(calls[0].operation, '查看');
    assert.equal(calls[0].params.屏幕会话, 'virtual');
    assert.equal('意图' in calls[0].params, false);
    assert.match(String(calls[0].params.动作编号), /^[0-9a-f]{64}$/);
  } finally {
    AndroidPhoneBridge.prototype.call = original;
    if (oldUrl === undefined) delete process.env.BBUI_BRIDGE_URL; else process.env.BBUI_BRIDGE_URL = oldUrl;
    if (oldToken === undefined) delete process.env.BBUI_BRIDGE_TOKEN; else process.env.BBUI_BRIDGE_TOKEN = oldToken;
  }
});

test('Android schema only declares native virtual-screen capabilities', () => {
  for (const operation of ['创建屏幕', '关闭屏幕', '系统面板']) {
    assert.equal(Check(AndroidPhoneSchema, { 意图: '查看当前状态', 操作: operation, 参数: {} }), false);
    assert.equal(Check(PhoneSchema, { 意图: '查看当前状态', 操作: operation, 参数: {} }), true);
  }
  assert.equal(Check(AndroidPhoneSchema, { 意图: '查看当前状态', 操作: '查看', 参数: {} }), true);
  assert.equal(Check(AndroidPhoneSchema, { 意图: '查看当前状态', 操作: '按键', 参数: { 键名: '音量加' } }), false);
  assert.equal(Check(AndroidPhoneSchema, { 意图: '查看当前状态', 操作: '按键', 参数: { 键名: '回车', 屏幕会话: 'virtual' } }), true);
  assert.equal(Check(AndroidPhoneSchema, { 意图: '查看当前状态', 操作: '查看', 参数: { 屏幕会话: 'main' } }), false);
  assert.equal(Check(AndroidPhoneSchema, { 意图: '查看当前状态', 操作: '查看', 参数: { 读取节点: true } }), false);
  assert.equal(Check(AndroidPhoneSchema, { 意图: '查看当前状态', 操作: '打开应用', 参数: { 包名: 'app.fixture', 启动组件: 'app.fixture/.Main' } }), true);
});

test('uncertain input quarantines only mutations and STOP source is distinct', async () => {
  const operations: string[] = [], stops: any[] = [];
  const fx = await fixture(async (req, res) => {
    const chunks: Buffer[] = [];
    for await (const chunk of req) chunks.push(Buffer.from(chunk));
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (req.url === '/stop') { stops.push(body); res.end('{}'); return; }
    operations.push(body.操作);
    if (body.操作 === '点击') { res.destroy(); return; }
    res.end('{"content":[],"details":{"观察":{"状态":"已取得"}}}');
  });
  try {
    assert.equal(((await fx.bridge.call('点击', {})).details.执行 as any).状态, '未知');
    assert.deepEqual(stops, [{ stopped: true, source: 'transport' }]);
    for (const operation of ['查看', '等待', '列出应用', '列出屏幕']) {
      assert.equal(((await fx.bridge.call(operation, {})).details.观察 as any).状态, '已取得');
    }
    // A STOP toggle/observation does not prove an old request is no longer running.
    await fx.bridge.setStopped(false);
    assert.equal(((await fx.bridge.call('点击', {})).details.执行 as any).状态, '未派发');
    assert.deepEqual(operations, ['点击', '查看', '等待', '列出应用', '列出屏幕']);
    assert.deepEqual(stops[1], { stopped: false, source: 'user' });
  } finally { await fx.close(); }
});

test('failed read does not quarantine later inputs or request STOP', async () => {
  let requests = 0, stops = 0;
  const fx = await fixture((req, res) => {
    if (req.url === '/stop') { stops++; res.end('{}'); return; }
    if (++requests === 1) { res.destroy(); return; }
    res.end('{"content":[],"details":{"执行":{"状态":"已派发"},"观察":{"状态":"失败"}}}');
  });
  try {
    const read = await fx.bridge.call('查看', {});
    assert.equal((read.details.执行 as any).状态, '无需派发');
    assert.equal((read.details.观察 as any).状态, '失败');
    assert.equal(((await fx.bridge.call('点击', {})).details.执行 as any).状态, '已派发');
    assert.equal(stops, 0);
  } finally { await fx.close(); }
});

test('a structured server error retains known dispatch and image evidence', async () => {
  const fx = await fixture((_req, res) => {
    res.statusCode = 500;
    res.end(JSON.stringify({ isError: true, content: [{ type: 'image', mimeType: 'image/png', data: 'evidence' }],
      details: { 执行: { 状态: '已派发' }, 观察: { 状态: '已取得' }, 错误: 'launch request rejected' } }));
  });
  try {
    const result = await fx.bridge.call('打开应用', {});
    assert.equal((result.details.执行 as any).状态, '已派发');
    assert.equal(result.content[0].type, 'image');
  } finally { await fx.close(); }
});

test('Android extension does not impose a fixed tool-call budget', async () => {
  let tool: any;
  const oldUrl = process.env.BBUI_BRIDGE_URL, oldToken = process.env.BBUI_BRIDGE_TOKEN;
  const original = AndroidPhoneBridge.prototype.call;
  let calls = 0;
  process.env.BBUI_BRIDGE_URL = 'http://127.0.0.1:1'; process.env.BBUI_BRIDGE_TOKEN = 'fixture';
  AndroidPhoneBridge.prototype.call = async (_op, params) => {
    calls++; assert.equal(params.执行后等待毫秒, 0); return { content: [], details: {} };
  };
  try {
    androidPhoneExtension({ on() {}, registerTool(value: any) { tool = value; } } as any);
    for (let i = 0; i < 105; i++) await tool.execute(`query-${i}`, { 意图: '查看当前状态', 操作: '列出屏幕', 参数: {} },
      undefined, undefined, { model: { input: ['text'] }, abort() { assert.fail('tool must not end the agent task'); } });
    assert.equal(calls, 105);
  } finally {
    AndroidPhoneBridge.prototype.call = original;
    if (oldUrl === undefined) delete process.env.BBUI_BRIDGE_URL; else process.env.BBUI_BRIDGE_URL = oldUrl;
    if (oldToken === undefined) delete process.env.BBUI_BRIDGE_TOKEN; else process.env.BBUI_BRIDGE_TOKEN = oldToken;
  }
});

test('failed STOP acknowledgement retains the input outcome and blocks later input', async () => {
  let dispatched!: () => void, release!: () => void;
  const started = new Promise<void>(resolve => { dispatched = resolve; });
  let actions = 0;
  const fx = await fixture(async (req, res) => {
    if (req.url === '/stop') { res.statusCode = 503; res.end('{}'); return; }
    actions++;
    if (actions === 1) { dispatched(); await new Promise<void>(resolve => { release = resolve; }); }
    res.end('{"content":[],"details":{"执行":{"状态":"已派发"},"观察":{"状态":"已取得"}}}');
  });
  try {
    const controller = new AbortController();
    const pending = fx.bridge.call('点击', {}, controller.signal);
    await started; controller.abort(); release();
    assert.equal(((await pending).details.执行 as any).状态, '已派发');
    const blocked = await fx.bridge.call('点击', {});
    assert.equal((blocked.details.执行 as any).状态, '未派发');
    assert.match(String(blocked.details.错误), /用户已停止/);
    await fx.bridge.call('查看', {});
    assert.equal(actions, 2);
  } finally { await fx.close(); }
});

test('screen observation is scoped to GUI work and does not force displays for other tools', () => {
  assert.match(PHONE_SYSTEM_PROMPT, /系统查询、搜索、记忆和提问不要求手机截图或创建虚拟屏/);
  assert.match(PHONE_SYSTEM_PROMPT, /GUI输入前先取得最新观察/);
  assert.match(ANDROID_PHONE_INSTRUCTIONS, /仅支持单个virtual/);
});
