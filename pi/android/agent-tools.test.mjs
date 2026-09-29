import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createServer } from 'node:http';
import { Check } from 'typebox/value';
import { searchWithBaidu } from './baidu-search.mjs';
const home = await mkdtemp(path.join(tmpdir(), 'bbui-tools-'));
process.env.PI_MEMORY_DIR = path.join(home, 'memory');
process.env.PI_CODING_AGENT_DIR = home;
process.env.BBUI_BRIDGE_URL = 'http://127.0.0.1:1'; process.env.BBUI_BRIDGE_TOKEN = 'fixture';
await writeFile(path.join(home, 'web-search.json'), JSON.stringify({ pdf: { enabled: false }, image: { enabled: false }, ssrf: { allowRanges: ['127.0.0.0/8'] } }));
const { default: register, NativeToolsBridge } = await import('../../runs/tool-build/agent-tools.js');
const { memoryCommand } = await import('../../runs/tool-build/memory-manager.js');
const tools = new Map(), hooks = new Map(), entries = [];
register({ registerTool(tool) { tools.set(tool.name, tool); }, on(name, handler) { hooks.set(name, [...(hooks.get(name) || []), handler]); }, appendEntry(customType, data) { entries.push({ type: 'custom', customType, data }); } });
after(async () => { for (const hook of hooks.get('session_shutdown') || []) await hook(); });
const context = { sessionManager: { getSessionId: () => 'fixture-session', getBranch: () => entries } };
const execute = (name, args, signal) => tools.get(name).execute('fixture-call', args, signal, undefined, context);
const text = value => value.content.map(item => item.text).join('');
async function serverFixture(handler) {
  const server = createServer((req, res) => { void (async () => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    await handler(req, res, JSON.parse(Buffer.concat(chunks).toString() || '{}'));
  })().catch(error => { res.statusCode = 500; res.end(JSON.stringify({ error: error.message })); }); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return { url: `http://127.0.0.1:${server.address().port}`, close: () => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }) };
}
test('manifest tools expose implemented capabilities and upstream question schema', () => {
  assert.deepEqual([...tools.keys()].sort(), ['system_apps', 'system_notifications', 'system_clipboard', 'system_files', 'system_calendar', 'system_contacts', 'system_sms', 'system_call_log', 'system_media', 'system_clock', 'ask_user_question', 'memory_write', 'memory_read', 'memory_forget', 'memory_restore', 'web_search', 'fetch_content', 'get_search_content'].sort());
  const schema = tools.get('ask_user_question').parameters;
  assert.equal(schema.properties.questions.maxItems, 4);
  assert.equal(Check(schema, { questions: [] }), false);
  assert.equal(Check(tools.get('memory_write').parameters, { target: 'daily', content: 'not supported' }), false);
  assert.equal(Check(tools.get('system_apps').parameters, { operation: 'shell', params: {}, intent: 'test' }), false);
});

test('desktop iPhone registers only backend system operations with phone-scoped descriptions', async () => {
  const registered = new Map();
  const bridge = new NativeToolsBridge('http://127.0.0.1:1', 'fixture'); bridge.desktop = true;
  bridge.deviceCapabilities = { devicePlatform: 'ios', systemOperations: {
    apps: ['list', 'details', 'launch', 'force_stop'], clipboard: ['read', 'write', 'clear'], files: ['list', 'read_text'],
  } };
  try {
    register({ registerTool(tool) { registered.set(tool.name, tool); }, on() {} }, bridge);
    assert.deepEqual([...registered.keys()].filter(name => name.startsWith('system_')).sort(), ['system_apps', 'system_clipboard', 'system_files']);
    const apps = registered.get('system_apps');
    assert.equal(Check(apps.parameters, { operation: 'launch', intent: '打开测试应用', params: { packageName: 'app.bundle', screen: 'main' } }), true);
    for (const operation of ['install', 'grant_permission', 'disable']) assert.equal(Check(apps.parameters, { operation, intent: '测试', params: {} }), false);
    assert.equal(Check(apps.parameters, { operation: 'list', intent: '查询应用', params: { userId: 0 } }), false);
    assert.match(apps.description, /Bundle ID/); assert.doesNotMatch(apps.description, /虚拟屏/);
    assert.match(apps.description, /先用 phone_action 查看 main/);
    assert.match(apps.description, /observationId=该截图编号/);
    assert.match(registered.get('system_files').description, /AFC/);
  } finally { await bridge.close(); }
});

test('desktop application launch preserves screen and observation identity through the HTTP bridge', async () => {
  const params = { packageName: 'com.example.test', screen: 'main', observationId: 'current-observation' };
  const received = [];
  const server = await serverFixture(async (_request, response, body) => {
    received.push(body); response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify({ content: [], details: { 执行: { 状态: '已派发' }, 观察: { 状态: '已取得' } } }));
  });
  const bridge = new NativeToolsBridge(server.url, 'fixture'); bridge.desktop = true;
  bridge.deviceCapabilities = { devicePlatform: 'ios', systemOperations: { apps: ['launch'] } };
  let appTool;
  try {
    register({ registerTool(tool) { if (tool.name === 'system_apps') appTool = tool; }, on() {} }, bridge);
    const result = await appTool.execute('launch-call', { operation: 'launch', params, intent: '打开测试应用' });
    assert.equal(received.length, 1); assert.deepEqual(received[0].params, params);
    assert.equal(received[0].operation, 'launch'); assert.equal(received[0].group, 'apps');
    assert.equal(result.details.执行.状态, '已派发'); assert.equal(result.details.观察.状态, '已取得');
  } finally { await bridge.close(); await server.close(); }
});

test('calendar schema supports explicit series edits and distinguishes null, empty and omitted values', () => {
  const schema = tools.get('system_calendar').parameters;
  const valid = params => Check(schema, { operation: 'update', params, intent: '调整会议' });
  assert.equal(valid({ eventId: 42, scope: 'series' }), true);
  assert.equal(valid({ eventId: 42, scope: 'series', rrule: null, reminderMinutes: [], description: '', location: '' }), true);
  assert.equal(valid({ eventId: 42, scope: 'series', rrule: 'FREQ=WEEKLY;COUNT=3', reminderMinutes: [0, 15] }), true);
  for (const params of [{ scope: 'instance' }, { scope: 'future' }, { reminderMinutes: [-1] }, { reminderMinutes: [1.5] }, { limit: 101 }, { sql: 'DELETE FROM Events' }, { command: 'sh' }]) assert.equal(valid(params), false);
  for (const [field, size] of [['title', 2000], ['location', 2000], ['description', 12000], ['rrule', 4000], ['query', 2000]]) {
    assert.equal(valid({ [field]: '字'.repeat(size) }), true);
    assert.equal(valid({ [field]: '字'.repeat(size + 1) }), false);
  }
  assert.equal(valid({ reminderMinutes: Array.from({ length: 101 }, (_, i) => i) }), false);
  assert.equal(Check(schema, { operation: 'list', params: { startMs: 0, endMs: 1, query: '会议' }, intent: '查日程' }), true);
  assert.equal(Check(schema, { operation: 'shell', params: {}, intent: 'test' }), false);
  assert.equal(Check(schema, { operation: 'create', params: {}, intent: ' ' }), false);
  const names = [];
  register({ registerTool(tool) { names.push(tool.name); }, on() {} }, { desktop: true, close() {} });
  assert.equal(names.includes('system_calendar'), false, 'desktop backend does not implement CalendarProvider');
});

test('calendar bridge preserves edit presence, intent, receipts and native failure without stopping healthy channel', async () => {
  const calls = [];
  let fail = false;
  const fx = await serverFixture((req, res, body) => {
    calls.push(body); assert.equal(req.url, '/system'); assert.equal(body.group, 'calendar');
    const details = fail ? { 错误: 'Provider拒绝', 执行: { 状态: '已派发' }, receipt: { eventId: 42 } }
      : { 成功: true, 执行: { 状态: '已派发' }, data: { eventId: 42, event: { id: 42, calendarId: 3 } } };
    res.end(JSON.stringify({ content: [{ type: 'text', text: JSON.stringify(details) }], details }));
  });
  const bridge = new NativeToolsBridge(fx.url, 'fixture');
  try {
    const params = { eventId: 42, scope: 'series', rrule: null, reminderMinutes: [] };
    const ok = await bridge.system('calendar', 'update', params, 'same-id', undefined, '取消会议重复与提醒');
    assert.deepEqual(calls[0].params, params); assert.equal(Object.hasOwn(calls[0].params, 'title'), false);
    assert.equal(ok.isError, false); assert.equal(ok.details.bbuiTool.kind, 'calendar'); assert.equal(ok.details.bbuiTool.status, 'complete');
    assert.equal(ok.details.bbuiTool.title, '取消会议重复与提醒');
    fail = true;
    const rejected = await bridge.system('calendar', 'update', params, 'same-id', undefined, '调整会议');
    assert.equal(rejected.isError, true); assert.equal(rejected.details.执行.状态, '已派发');
    assert.deepEqual(rejected.details.receipt, { eventId: 42 }); assert.equal(bridge.uncertain, false);
    assert.equal(calls[0].actionId, calls[1].actionId, 'same tool call preserves native deduplication identity');
    const c = new AbortController(); c.abort();
    const cancelled = await bridge.system('calendar', 'create', {}, 'cancelled', c.signal);
    assert.equal(cancelled.isError, true); assert.equal(cancelled.details.执行.状态, '未派发');
    assert.equal(calls.length, 2);
  } finally { await bridge.close(); await fx.close(); }
});

test('non-GUI schemas match bounded native capabilities, reject arbitrary commands and stay Android-only', () => {
  const valid = (group, operation, params) => Check(tools.get(`system_${group}`).parameters, { operation, params, intent: '测试明确操作' });
  assert.equal(valid('contacts', 'create', { name: '张三😀', phones: ['+8612345'], emails: ['test@example.invalid'] }), true);
  assert.equal(valid('contacts', 'update', { rawContactId: 7, contactId: 9, phones: [], emails: [] }), true);
  assert.equal(valid('contacts', 'details', { contactId: 9, rawOffset: 50, rawLimit: 100, offset: 100, limit: 50 }), true);
  assert.equal(valid('contacts', 'details', { rawOffset: -1 }), false);
  assert.equal(valid('contacts', 'details', { rawLimit: 101 }), false);
  for (const params of [{ phones: Array(21).fill('123') }, { emails: ['x'.repeat(321)] }, { name: '😀'.repeat(2001) }, { rawContactId: -1 }]) assert.equal(valid('contacts', 'update', params), false);
  assert.equal(valid('contacts', 'create', { name: '😀'.repeat(2000), phones: ['😀'.repeat(320)] }), true);
  assert.equal(valid('sms', 'details', { messageId: 1, textOffset: 123, textLimit: 16384 }), true);
  assert.equal(valid('sms', 'details', { textLimit: 16385 }), false);
  assert.equal(valid('call_log', 'list', { number: '+8612345', startMs: 1, endMs: 1000, type: 3 }), true);
  assert.equal(valid('call_log', 'list', { type: 8 }), false);
  assert.equal(valid('media', 'details', { kind: 'image', mediaId: 7 }), true);
  assert.equal(valid('media', 'list', { kind: 'private', query: 'x' }), false);
  assert.equal(valid('clock', 'create_alarm', { hour: 23, minute: 59, days: [1, 7], vibrate: false }), true);
  assert.equal(valid('clock', 'create_timer', { seconds: 86400, label: '煮茶' }), true);
  for (const params of [{ hour: 24 }, { minute: -1 }, { days: [0] }, { days: [2, 2] }, { seconds: 0 }, { seconds: 86401 }, { extras: {} }]) assert.equal(valid('clock', 'create_alarm', params), false);
  for (const group of ['contacts', 'sms', 'call_log', 'media', 'clock']) {
    assert.equal(valid(group, 'shell', {}), false);
    assert.equal(valid(group, group === 'clock' ? 'capabilities' : 'list', { sql: '1=1', uri: 'content://private', command: 'sh' }), false);
    if (group !== 'clock') assert.equal(valid(group, 'list', { limit: 101 }), false);
  }
  for (const group of ['sms', 'call_log', 'media']) assert.equal(valid(group, 'delete', {}), false);
  const names = [];
  register({ registerTool(tool) { names.push(tool.name); }, on() {} }, { desktop: true, close() {} });
  for (const group of ['contacts', 'sms', 'call_log', 'media', 'clock']) assert.equal(names.includes(`system_${group}`), false);
});

test('non-GUI read failures preserve a healthy channel; unknown writes stop once, keep reads available and block replay', async () => {
  let calls = 0, stops = 0, unknown = false;
  const fx = await serverFixture((req, res, body) => {
    if (req.url === '/stop') { stops++; res.end('{}'); return; }
    calls++;
    if (unknown) { res.statusCode = 500; res.end('{}'); return; }
    const details = { 错误: '系统拒绝访问', 执行: { 状态: '未派发' }, receipt: { rawContactId: 42 } };
    res.end(JSON.stringify({ content: [{ type: 'text', text: JSON.stringify(details) }], details }));
  });
  const bridge = new NativeToolsBridge(fx.url, 'fixture');
  try {
    for (const group of ['contacts', 'sms', 'call_log', 'media', 'clock']) {
      const operation = group === 'clock' ? 'capabilities' : 'list';
      const value = await bridge.system(group, operation, {}, group, undefined, '读取所需系统信息');
      assert.equal(value.isError, true); assert.equal(value.details.bbuiTool.kind, group);
      assert.equal(value.details.bbuiTool.status, 'error'); assert.equal(value.details.bbuiTool.title, '读取所需系统信息');
      assert.deepEqual(value.details.receipt, { rawContactId: 42 });
      assert.equal(bridge.uncertain, false);
    }
    unknown = true;
    assert.equal((await bridge.system('sms', 'details', { messageId: 42 }, 'read')).details.执行.状态, '无需派发');
    assert.equal(bridge.uncertain, false); assert.equal(stops, 0);
    assert.equal((await bridge.system('clock', 'create_timer', { seconds: 30 }, 'write')).details.执行.状态, '未知');
    assert.equal(bridge.uncertain, true); assert.equal(stops, 1);
    const before = calls;
    assert.equal((await bridge.system('contacts', 'create', { name: '测试' }, 'replay')).details.执行.状态, '未派发');
    assert.equal(calls, before);
    assert.equal((await bridge.system('media', 'list', {}, 'observe')).details.执行.状态, '无需派发');
    assert.equal(calls, before + 1);
    const cancelled = new AbortController(); cancelled.abort();
    assert.equal((await bridge.system('contacts', 'list', {}, 'cancelled', cancelled.signal)).details.执行.状态, '未派发');
    assert.equal(calls, before + 1);
  } finally { await bridge.close(); await fx.close(); }
});
test('upstream memory and settings share revisions; forget/restore and delete change injected context', async () => {
  const initial = await memoryCommand({ action: 'read' });
  const written = await execute('memory_write', { target: 'long_term', content: '偏好喝茶☕', mode: 'append' });
  assert.ok(!written.isError);
  await assert.rejects(memoryCommand({ action: 'write', revision: initial.revision, content: 'stale' }), /已更新/);
  assert.match((await memoryCommand({ action: 'read' })).content, /喝茶/);
  const hook = hooks.get('before_agent_start')[0];
  const event = { systemPrompt: 'base', systemPromptOptions: { sections: { phone_environment: 'fixture environment' } } };
  assert.equal(await hook(event), undefined);
  assert.match(event.systemPromptOptions.sections.user_memory, /喝茶/);
  assert.equal(event.systemPromptOptions.sections.phone_environment, 'fixture environment');
  assert.equal(event.systemPrompt, 'base', 'memory does not force a replacement system prompt');
  const forgotten = await execute('memory_forget', { target: 'long_term', match: '喝茶' });
  assert.ok(forgotten.details.recoveryId);
  assert.equal(await hook(event), undefined);
  assert.equal(event.systemPromptOptions.sections.user_memory, undefined, 'forget removes the previous memory section');
  await execute('memory_restore', { recoveryId: forgotten.details.recoveryId });
  const updated = await memoryCommand({ action: 'read' });
  assert.match(updated.content, /喝茶/);
  await memoryCommand({ action: 'delete', revision: updated.revision });
  assert.equal((await memoryCommand({ action: 'read' })).content, '');
  assert.equal(await hook(event), undefined);
  assert.equal(event.systemPromptOptions.sections.user_memory, undefined);
  assert.ok((await readdir(path.join(process.env.PI_MEMORY_DIR, 'recovery'))).length >= 2);
  const c = new AbortController(); c.abort();
  await assert.rejects(execute('memory_write', { target: 'long_term', content: 'should not write' }, c.signal));
  assert.equal((await memoryCommand({ action: 'read' })).content, '');
});
test('Baidu official payload, empty results, business errors and redaction', async () => {
  let captured;
  const fetchImpl = async (url, init) => { captured = { url, init }; return Response.json({ references: [{ title: '中文', url: 'https://example.org', content: '内容' }] }); };
  const answer = await searchWithBaidu('中文搜索', { apiKey: 'secret', fetchImpl });
  assert.equal(JSON.parse(captured.init.body).messages[0].content, '中文搜索');
  assert.equal(captured.init.headers.Authorization, 'Bearer secret');
  assert.equal(answer.results[0].snippet, '内容');
  assert.deepEqual((await searchWithBaidu('empty', { apiKey: 'secret', fetchImpl: async () => Response.json({ references: [] }) })).results, []);
  await assert.rejects(searchWithBaidu('bad', { apiKey: 'secret', fetchImpl: async () => Response.json({ error_code: 1, error_msg: 'bad secret' }) }), error => !error.message.includes('secret') && /redacted/.test(error.message));
  await assert.rejects(searchWithBaidu('rate', { apiKey: 'secret', fetchImpl: async () => new Response('slow', { status: 429 }) }), /429/);
});
test('actual upstream Bocha parser is used without provider fallback; full results are stored', async () => {
  const original = globalThis.fetch;
  process.env.BBUI_SEARCH_PROVIDER = 'bocha'; process.env.BBUI_SEARCH_KEY = 'secret'; process.env.BOCHA_API_KEY = 'secret';
  let requests = 0;
  globalThis.fetch = async (url, init) => { requests++; assert.equal(url, 'https://api.bochaai.com/v1/web-search'); assert.equal(JSON.parse(init.body).query, '中文'); return Response.json({ code: 200, data: { webPages: { value: [{ name: '来源', url: 'https://example.org', snippet: '中'.repeat(9000) }] } } }); };
  try {
    const output = await execute('web_search', { query: '中文' });
    assert.equal(output.isError, false); assert.equal(requests, 1);
    const data = JSON.parse(text(output));
    assert.equal(data.results[0].snippet.length, 1800);
    const full = JSON.parse(text(await execute('get_search_content', { responseId: data.responseId })));
    assert.ok(full.totalChars > 9000);
    globalThis.fetch = async () => { requests++; return new Response('denied', { status: 401 }); };
    assert.equal((await execute('web_search', { query: '中文' })).isError, true);
    assert.equal(requests, 2);
  } finally { globalThis.fetch = original; }
});
test('upstream direct HTTP extraction and cache pagination work without browser or external providers', async () => {
  const fx = await serverFixture((_req, res) => { res.setHeader('Content-Type', 'text/html'); res.end(`<html><head><title>测试文章</title></head><body><article><h1>测试文章</h1><p>${'这是一篇可提取的正文，验证网页内容。'.repeat(1000)}</p></article></body></html>`); });
  try {
    const output = await execute('fetch_content', { url: fx.url + '/article' });
    assert.equal(output.isError, false, text(output));
    const parsed = JSON.parse(text(output));
    assert.ok(parsed.totalChars > 12000); assert.equal(parsed.content.length, 12000);
    const page = JSON.parse(text(await execute('get_search_content', { responseId: parsed.responseId, offset: 12000, limit: 30 })));
    assert.equal(page.content.length, 30);
    for (const hook of hooks.get('session_start') || []) await hook({}, context);
    assert.ok(!((await execute('get_search_content', { responseId: parsed.responseId })).isError));
  } finally { await fx.close(); }
});
test('question bridge validates identities and preserves upstream answer formatting', async () => {
  const fx = await serverFixture((req, res, body) => { assert.equal(req.url, '/questions'); assert.equal(body.toolCallId, 'question-call'); assert.equal(body.questions[0].id, 'q1'); res.end(JSON.stringify({ requestId: 'native-id', cancelled: false, answers: [{ questionId: 'q1', selected: ['茶'], text: '不要糖' }] })); });
  const bridge = new NativeToolsBridge(fx.url, 'fixture');
  try {
    const output = await bridge.ask('question-call', { questions: [{ header: '饮品', question: '喝什么？', multiSelect: false, options: [{ label: '茶', description: '热饮' }, { label: '水', description: '常温' }] }] });
    assert.match(text(output), /饮品: 茶, "不要糖" \(other\)/);
    assert.deepEqual(output.details.answers[0].selectedLabels, ['茶']);
  } finally { await bridge.close(); await fx.close(); }
});

test('pure text questions have no invented choices and preserve answers, cancellation and validation', async () => {
  const question = { header: '收件人', question: '发给谁？', multiSelect: false, options: [] };
  const schema = tools.get('ask_user_question').parameters;
  assert.equal(Check(schema, { questions: [question] }), true);
  assert.equal(Check(schema, { questions: [{ ...question, multiSelect: true }] }), false);
  assert.equal(Check(schema, { questions: [{ ...question, options: [{ label: '联系人', description: '未指定' }] }] }), false);
  let answer = { cancelled: false, answers: [{ questionId: 'q1', selected: [], text: '张三 😀' }] };
  const fx = await serverFixture((_req, res, body) => {
    assert.deepEqual(body.questions[0].options, []);
    res.end(JSON.stringify(answer));
  });
  const bridge = new NativeToolsBridge(fx.url, 'fixture');
  try {
    const output = await bridge.ask('pure-text', { questions: [question] });
    assert.match(text(output), /张三 😀/);
    assert.deepEqual(output.details.answers[0].selectedLabels, []);
    answer.answers[0].text = '   ';
    await assert.rejects(bridge.ask('empty', { questions: [question] }), /格式/);
    answer.answers[0] = { questionId: 'q1', selected: ['伪造选项'], text: '张三' };
    await assert.rejects(bridge.ask('invented', { questions: [question] }), /格式/);
    answer = { cancelled: true, answers: [] };
    assert.equal((await bridge.ask('cancelled', { questions: [question] })).details.cancelled, true);
  } finally { await bridge.close(); await fx.close(); }
});
test('system transport keeps dispatch receipt after cancellation and quarantines unknown mutation', async () => {
  let release, started; const dispatched = new Promise(resolve => { started = resolve; }); let mode = 'receipt', stops = 0, calls = 0;
  const fx = await serverFixture(async (req, res) => {
    if (req.url === '/stop') { stops++; res.end('{}'); return; }
    calls++;
    if (mode === 'receipt') { started(); await new Promise(resolve => { release = resolve; }); res.end(JSON.stringify({ content: [], details: { 执行: { 状态: '已派发' } } })); }
    else { res.statusCode = 500; res.end('{}'); }
  });
  const bridge = new NativeToolsBridge(fx.url, 'fixture');
  try {
    const c = new AbortController(); const pending = bridge.system('clipboard', 'write', { text: 'x' }, 'one', c.signal);
    await dispatched; c.abort(); release();
    assert.equal((await pending).details.执行.状态, '已派发');
    mode = 'unknown'; assert.equal((await bridge.system('clipboard', 'write', {}, 'two')).details.执行.状态, '未知');
    assert.equal((await bridge.system('clipboard', 'clear', {}, 'three')).details.执行.状态, '未派发');
    assert.equal(calls, 2); assert.ok(stops > 0);
  } finally { await bridge.close(); await fx.close(); }
});

test('search response cap rejects chunked oversize before JSON parsing; abort makes no extra request', async () => {
  const { boundedSearchFetch } = await import('./bounded-http.mjs');
  let cancelled = false;
  const stream = new ReadableStream({ pull(controller) { controller.enqueue(new Uint8Array(1024 * 1024)); }, cancel() { cancelled = true; } });
  await assert.rejects(boundedSearchFetch('https://unused.example', {}, async () => new Response(stream)), /2 MiB/);
  assert.equal(cancelled, true);
  const original = fetch;
  process.env.BBUI_SEARCH_PROVIDER = 'bocha'; process.env.BBUI_SEARCH_KEY = 'secret'; process.env.BOCHA_API_KEY = 'secret';
  let requests = 0;
  globalThis.fetch = async () => { requests++; return Response.json({ code: 500, msg: 'failed secret' }); };
  try {
    const failed = await execute('web_search', { query: 'error' });
    assert.equal(failed.isError, true); assert.ok(!text(failed).includes('secret'));
    globalThis.fetch = async () => { requests++; return new Response('limit', { status: 429 }); };
    assert.match(text(await execute('web_search', { query: 'limit' })), /429/);
    const c = new AbortController(); c.abort();
    await assert.rejects(execute('web_search', { query: 'cancelled' }, c.signal));
    assert.equal(requests, 2);
  } finally { globalThis.fetch = original; }
});
test('Baidu cancellation interrupts a pending local request; auth failure is reported', async () => {
  let started;
  const pending = new Promise(resolve => { started = resolve; });
  const fx = await serverFixture(() => { started(); });
  try {
    const c = new AbortController();
    const work = searchWithBaidu('wait', { apiKey: 'secret', signal: c.signal, fetchImpl: (_url, init) => fetch(fx.url, init) });
    await pending; c.abort(); await assert.rejects(work, /abort/i);
    await assert.rejects(searchWithBaidu('auth', { apiKey: 'secret', fetchImpl: async () => new Response('invalid', { status: 401 }) }), /401/);
  } finally { await fx.close(); }
});
test('direct HTTP rejects oversize chunked bodies and unsupported image content', async () => {
  const fx = await serverFixture((req, res) => {
    if (req.url === '/image') { res.setHeader('Content-Type', 'image/png'); res.end('fixture'); }
    else { res.setHeader('Content-Type', 'text/plain'); res.write('x'.repeat(3 * 1024 * 1024)); res.end('x'.repeat(3 * 1024 * 1024)); }
  });
  try {
    const huge = await execute('fetch_content', { url: fx.url + '/huge' });
    assert.equal(huge.isError, true); assert.match(text(huge), /Response too large/);
    const image = await execute('fetch_content', { url: fx.url + '/image' });
    assert.equal(image.isError, true); assert.match(text(image), /disabled/);
    assert.ok(JSON.stringify(huge).length < 4000);
  } finally { await fx.close(); }
});
test('question cancellation is factual and duplicate or missing answer IDs are rejected', async () => {
  let answer = { cancelled: true, answers: [] };
  const fx = await serverFixture((_req, res) => res.end(JSON.stringify(answer)));
  const bridge = new NativeToolsBridge(fx.url, 'fixture');
  const ask = { questions: [{ header: '选择', question: '选哪个？', multiSelect: false, options: [{ label: 'A', description: 'A' }, { label: 'B', description: 'B' }] }] };
  try {
    assert.equal((await bridge.ask('ask', ask)).details.cancelled, true);
    answer = { cancelled: false, answers: [{ questionId: 'old-request', selected: ['A'], text: '' }] };
    await assert.rejects(bridge.ask('ask', ask), /编号/);
    answer = { cancelled: false, answers: [{ questionId: 'q1', selected: ['A', 'A'], text: '' }] };
    await assert.rejects(bridge.ask('ask', ask), /格式/);
  } finally { await bridge.close(); await fx.close(); }
});

test('all three Pi protocols serialize expanded schemas and continue after actual upstream memory tool', async () => {
  const { ModelRuntime } = await import('@earendil-works/pi-coding-agent');
  const { modelConfiguration, SUPPORTED_APIS } = await import('./runtime-config.mjs');
  const { events } = await import('./protocol-fixtures.mjs');
  let api, calls = 0;
  const fx = await serverFixture((_req, res, body) => {
    calls++;
    assert.equal(body.tools.length, tools.size);
    const names = body.tools.map(tool => tool.function?.name || tool.name);
    assert.ok(names.includes('ask_user_question')); assert.ok(names.includes('system_apps'));
    if (calls === 2) assert.match(JSON.stringify(body), /call_fixture/);
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    for (const event of events(api, calls === 1, 'memory_read', '{"target":"long_term"}')) res.write(`${event.type ? `event: ${event.type}\n` : ''}data: ${JSON.stringify(event)}\n\n`);
    if (api === 'openai-completions') res.write('data: [DONE]\n\n');
    res.end();
  });
  try {
    for (api of SUPPORTED_APIS) {
      calls = 0;
      const modelsPath = path.join(home, `models-${api}.json`);
      await writeFile(modelsPath, JSON.stringify(modelConfiguration({ provider: 'fixture', model: 'fixture', input: ['text'], api, baseUrl: api === 'anthropic-messages' ? fx.url : fx.url + '/v1' })));
      const runtime = await ModelRuntime.create({ modelsPath, authPath: path.join(home, 'auth.json'), refreshOnCreate: false });
      const model = runtime.getModel('fixture', 'fixture');
      const ctx = { messages: [{ role: 'user', content: '读取记忆', timestamp: 1 }], tools: [...tools.values()].map(({ name, description, parameters }) => ({ name, description, parameters })) };
      const first = await runtime.completeSimple(model, ctx, { apiKey: 'fixture', maxRetries: 0 });
      assert.equal(first.stopReason, 'toolUse', `${api}: ${first.errorMessage}`);
      const call = first.content.find(part => part.type === 'toolCall');
      assert.equal(call.name, 'memory_read'); assert.deepEqual(call.arguments, { target: 'long_term' });
      const response = await execute(call.name, call.arguments);
      ctx.messages.push(first, { role: 'toolResult', toolCallId: call.id, toolName: call.name, isError: Boolean(response.isError), content: response.content, timestamp: 2 });
      const last = await runtime.completeSimple(model, ctx, { apiKey: 'fixture', maxRetries: 0 });
      assert.equal(last.stopReason, 'stop', `${api}: ${last.errorMessage}`); assert.equal(calls, 2);
    }
  } finally { await fx.close(); }
});

test('model cannot overwrite a user edit it has not read yet', async () => {
  await execute('memory_read', { target: 'long_term' });
  const old = await memoryCommand({ action: 'read' });
  await memoryCommand({ action: 'write', revision: old.revision, content: '用户刚刚修改的偏好' });
  const stale = await execute('memory_write', { target: 'long_term', mode: 'overwrite', content: 'old model draft' });
  assert.equal(stale.isError, true);
  assert.equal((await memoryCommand({ action: 'read' })).content, '用户刚刚修改的偏好');
  await execute('memory_read', { target: 'long_term' });
  assert.ok(!(await execute('memory_write', { target: 'long_term', content: '新补充' })).isError);
  assert.match((await memoryCommand({ action: 'read' })).content, /用户刚刚修改的偏好/);
});

test('native HTTP 200 question error preserves its bounded redacted reason instead of answer-shape failure', async () => {
  const fx = await serverFixture((_req, res) => res.end(JSON.stringify({ isError: true,
    content: [{ type: 'text', text: 'raw ignored' }], details: { 错误: '已有问题等待回答 fixture secret ' + '错'.repeat(3000) } })));
  const bridge = new NativeToolsBridge(fx.url, 'fixture');
  try {
    await assert.rejects(bridge.ask('ask', { questions: [{ header: '问题', question: '选一个', multiSelect: false,
      options: [{ label: '甲', description: '甲' }, { label: '乙', description: '乙' }] }] }), error => {
      assert.match(error.message, /已有问题等待回答/);
      assert.ok(!error.message.includes('fixture')); assert.ok(!error.message.includes('secret'));
      assert.ok(error.message.length <= 800); assert.ok(!error.message.includes('回答数量不匹配'));
      return true;
    });
  } finally { await bridge.close(); await fx.close(); }
});

test('clipboard and file write schemas bound Binder payloads including emoji; clipboard reads paginate', () => {
  for (const name of ['system_clipboard', 'system_files']) {
    const schema = tools.get(name).parameters;
    const operation = name === 'system_files' ? 'write_text' : 'write';
    assert.equal(schema.properties.params.properties.text.maxLength, 16384);
    assert.equal(Check(schema, { operation, params: { text: '😀'.repeat(16384) }, intent: '写入测试文本' }), true);
    assert.equal(Check(schema, { operation, params: { text: 'a'.repeat(16385) }, intent: '写入测试文本' }), false);
    assert.equal('😀'.repeat(16384).length, 32768);
  }
  const schema = tools.get('system_clipboard').parameters;
  assert.equal(Check(schema, { operation: 'read', params: { offset: 16384, limit: 16384 }, intent: '继续读取剪贴板' }), true);
  assert.equal(Check(schema, { operation: 'read', params: { offset: 0, limit: 16385 }, intent: '读取剪贴板' }), false);
});
