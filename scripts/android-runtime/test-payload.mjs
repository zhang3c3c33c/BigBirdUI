import { createServer } from 'node:http';
import { spawn, spawnSync } from 'node:child_process';
import { mkdtemp, writeFile, mkdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { tmpdir } from 'node:os';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const workspace = path.join(root, 'android/runtime/build');
await mkdir(workspace, { recursive: true });
// Resolve only dependencies shipped in the exact APK ZIP, outside the repository.
const home = await mkdtemp(path.join(tmpdir(), 'bbui-isolated-host-gate-'));
const stage = path.join(home, 'payload');
// Exercise the shipped runtime with internet access forbidden and a local model fixture.
const offlineGuard = path.join(home, 'loopback-only.cjs');
await writeFile(offlineGuard, `
const net = require('node:net');
const connect = net.Socket.prototype.connect;
net.Socket.prototype.connect = function (...args) {
  const options = net._normalizeArgs(args)[0];
  const host = options.host || 'localhost';
  if (!options.path && !['localhost', '127.0.0.1', '::1'].includes(host)) {
    process.stderr.write('Unexpected internet connection: ' + host + '\\n');
    process.exit(97);
  }
  return connect.apply(this, args);
};
require('node:module').syncBuiltinESMExports();
`);
const unpack = spawnSync(process.env.BBUI_PYTHON_HOST || 'python', ['-c',
  'import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])',
  path.join(root, 'android/runtime/build/generated/runtime-assets/pi-runtime.zip'), stage], { windowsHide: true });
if (unpack.status !== 0) throw new Error(`Cannot unpack exact APK payload: ${unpack.stderr}`);
const token = 'bbui-host-fixture-token';
const pixel = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=';
const constraint = '只查看当前画面，不发送消息，也不提交订单。';
const reasoning = '先观察手机，再确认当前画面。🧭';
const reply = '完成观察。你好，世界！👋\n\n- 第一项\n- 第二项\n\n```js\nconsole.log("完成");\n```';
const capturedEvents = [];
let modelCalls = 0, actionCalls = 0, sawImage = false, runNumber = 0;
let shouldRestore = false, currentMarker = '', plan = [], currentTask, expectedMemory = null;
let serverFailure = null;

function task(status, marker) {
  return { version: 1, id: marker === 'BBUI_GATE_ONLY' ? 'gate-task' : 'real-task',
    goal: '读取当前页面，保存观察结果', status, summary: `任务记忆-${marker}-${status}`,
    constraints: [{ text: constraint, source: 'user' }], facts: status === 'active' ? [] : ['本轮已取得模拟截图'],
    unknowns: status === 'waiting_user' ? [{ text: '用户下一步目标', resolveBy: 'user' }] : [],
    steps: [{ id: 'observe', title: '查看当前画面', status: status === 'active' ? 'in_progress' : 'completed' }],
    ...(status === 'waiting_user' ? { question: '下一步想查看什么？' } : {}),
    ...(status === 'completed' ? { completionEvidence: '已取得本轮模拟截图，未执行其它手机动作' } : {}) };
}

function toolResult(messages, id) {
  return messages.find(message => message.role === 'tool' && message.tool_call_id === id);
}

const server = createServer(async (req, res) => {
  try {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString() || '{}');
    assert.equal(req.headers.authorization, `Bearer ${token}`);
    if (req.url === '/action') {
      actionCalls++;
      assert.equal(actionCalls, 1, 'Only the corrected observation may reach the phone bridge in this run');
      assert.equal(plan[modelCalls - 1]?.label, 'observe');
      assert.equal(body.操作, '查看');
      assert.equal(body.参数.屏幕会话, 'virtual');
      assert.equal(Object.hasOwn(body, '意图'), false);
      assert.equal(Object.hasOwn(body.参数, '意图'), false);
      assert.match(body.参数.动作编号, /^[0-9a-f]{64}$/);
      const details = { 状态: '已观察', 执行: { 状态: '无需派发' },
        观察: { 状态: '已取得' }, 截图编号: `fixture-${runNumber}`, 屏幕会话: 'virtual' };
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ content: [
        { type: 'text', text: JSON.stringify(details) },
        { type: 'image', data: pixel, mimeType: 'image/png' },
      ], details }));
    } else if (req.url === '/stop') {
      res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{}');
    } else if (req.url === '/v1/chat/completions') {
      modelCalls++;
      const next = plan[modelCalls - 1];
      assert.ok(modelCalls <= plan.length + 1, 'Unexpected extra Agent iteration');
      assert.deepEqual(body.tools.map(tool => tool.function.name).sort(), ['read', 'phone_action', 'phone_history', 'task_state', 'system_apps', 'system_notifications', 'system_clipboard', 'system_files', 'system_calendar', 'system_contacts', 'system_sms', 'system_call_log', 'system_media', 'system_clock', 'ask_user_question', 'memory_write', 'memory_read', 'memory_forget', 'memory_restore', 'web_search', 'fetch_content', 'get_search_content'].sort());
      const phoneSchema = body.tools.find(tool => tool.function.name === 'phone_action').function.parameters;
      assert.equal(phoneSchema.properties.意图.maxLength, 80);
      assert.ok(phoneSchema.required.includes('意图'), 'Intent must be enforced by Pi schema validation');
      assert.equal(body.model, 'deepseek-flash');
      assert.equal(body.thinking.type, 'enabled');
      if (modelCalls > 1) assert.ok(body.messages.some(message => message.reasoning_content === reasoning),
        'Pi must retain DeepSeek reasoning_content through all task and phone tool calls');
      const messages = JSON.stringify(body.messages);
      if (modelCalls === 1) {
        assert.ok(messages.includes('每次新任务和会话恢复后'));
        const system = body.messages.filter(message => message.role === 'system' || message.role === 'developer').map(message => typeof message.content === 'string' ? message.content : JSON.stringify(message.content)).join('\n');
        assert.ok(system.includes('phone-operation'));
        assert.ok(system.includes('<skills>'));
        assert.ok(!system.includes('expert coding assistant')); 
        assert.ok(messages.includes('禁止重放历史动作'));
        if (shouldRestore) {
          assert.ok(messages.includes('BBUI_REAL_FIRST'));
          assert.ok(!messages.includes('BBUI_GATE_ONLY'));
        } else if (currentMarker === 'BBUI_GATE_ONLY') {
          assert.ok(!messages.includes('BBUI_REAL_FIRST'));
        }
        assert.equal(actionCalls, 0, 'Starting or restoring Pi must never replay phone actions');
      }
      const previous = plan[modelCalls - 2];
      if (previous) {
        const result = toolResult(body.messages, previous.id);
        assert.ok(result, `Missing tool response for ${previous.label}`);
        if (previous.name === 'read' && previous.label !== 'skill-denied') {
          assert.match(JSON.stringify(result), /phone-operation/);
          assert.equal(actionCalls, 0, 'Reading skill must not create or operate a display');
        }
        if (previous.label === 'skill-denied') {
          assert.match(JSON.stringify(result), /指南读取失败/);
          assert.ok(!JSON.stringify(result).includes('PRIVATE_FIXTURE'));
          assert.equal(actionCalls, 0, 'Denied reader must not reach the phone');
        }
        if (previous.label === 'invalid-intent') {
          assert.equal(actionCalls, 0, 'Missing intent must fail before bridge dispatch');
          assert.match(JSON.stringify(result), /意图/);
        }
        if (previous.name === 'task_state' && previous.args.action === 'update') expectedMemory = previous.args.task;
      }
      if (expectedMemory) {
        // Exclude raw tool results and assistant arguments to prove context injection.
        const injected = JSON.stringify(body.messages.filter(message => message.role !== 'tool' && !message.tool_calls));
        assert.ok(injected.includes(expectedMemory.summary), 'Latest task state must be injected into model context');
        assert.ok(injected.includes(constraint), 'User constraints must survive updates and restart');
      }
      if (messages.includes('data:image/png;base64,')) sawImage = true;
      if (next?.name === 'task_state') {
        const observedAlready = plan.slice(0, modelCalls - 1).some(step => step.label === 'observe');
        assert.equal(actionCalls, observedAlready ? 1 : 0, 'Task bookkeeping must not request the phone bridge');
      }
      res.writeHead(200, { 'Content-Type': 'text/event-stream' });
      const deltas = [{ role: 'assistant' }, ...Array.from(reasoning, c => ({ reasoning_content: c }))];
      if (next) {
        deltas.push({ content: `步骤：${next.label}。` }, { tool_calls: [{ index: 0, id: next.id,
          type: 'function', function: { name: next.name, arguments: '' } }] });
        for (const fragment of Array.from(JSON.stringify(next.args))) {
          deltas.push({ tool_calls: [{ index: 0, function: { arguments: fragment } }] });
        }
      } else deltas.push(...Array.from(reply, content => ({ content })));
      for (const chunk of [
        ...deltas.map(delta => ({ id: `fixture-${modelCalls}`, object: 'chat.completion.chunk', created: 1,
          model: 'deepseek-flash', choices: [{ index: 0, delta, finish_reason: null }] })),
        { id: `fixture-${modelCalls}`, object: 'chat.completion.chunk', created: 1, model: 'deepseek-flash',
          choices: [{ index: 0, delta: {}, finish_reason: next ? 'tool_calls' : 'stop' }],
          usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } },
      ]) res.write(`data: ${JSON.stringify(chunk)}\n\n`);
      res.end('data: [DONE]\n\n');
    } else { res.writeHead(404); res.end('{}'); }
  } catch (error) {
    serverFailure = error;
    res.writeHead(500, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: { message: `Local fixture assertion failed: ${error.message}` } }));
  }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;
const config = path.join(home, 'runtime-config.json');

async function run(marker, gate, restore, targetSessionId) {
  runNumber++;
  modelCalls = 0; actionCalls = 0; sawImage = false; serverFailure = null;
  currentMarker = marker; shouldRestore = restore;
  expectedMemory = restore ? task('waiting_user', 'BBUI_REAL_FIRST') : null;
  currentTask = task(marker === 'BBUI_REAL_FIRST' ? 'waiting_user' : 'completed', marker);
  plan = [
    { name: 'read', label: 'skill-read', args: { path: path.join(stage, 'pi/skills/phone-operation/SKILL.md') } },
    { name: 'read', label: 'skill-denied', args: { path: path.join(stage, 'PRIVATE_FIXTURE') } },
    ...(restore ? [{ name: 'task_state', label: 'restore-read', args: { action: 'read' } }] : []),
    { name: 'task_state', label: 'active', args: { action: 'update', task: task('active', marker) } },
    { name: 'phone_action', label: 'invalid-intent', args: { 操作: '查看', 参数: {} } },
    { name: 'phone_action', label: 'observe', args: { 意图: '查看当前画面以确认任务状态', 操作: '查看', 参数: {} } },
    { name: 'task_state', label: 'terminal', args: { action: 'update', task: currentTask } },
    { name: 'task_state', label: 'read', args: { action: 'read' } },
  ].map((step, index) => ({ ...step, id: `fixture-call-${runNumber}-${index + 1}` }));
  await writeFile(config, JSON.stringify({ bridgeUrl: base, bridgeToken: token, gate, sessionId: targetSessionId,
    baseUrl: `${base}/v1`, apiKey: token, provider: 'deepseek', model: 'deepseek-flash',
    input: ['text', 'image'], reasoning: true, thinkingLevels: ['off', 'low', 'high'].map(id => ({ id, label: id })),
    api: 'openai-completions', ...(restore ? {thinkingLevel: 'low'} : {}) }));
  const child = spawn(process.execPath, ['--require', offlineGuard, path.join(stage, 'bootstrap.mjs'), config], {
    cwd: stage, env: { ...process.env, NODE_PATH: '', NODE_OPTIONS: '', PI_CODING_AGENT_DIR: home },
    stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true,
  });
  let stderr = '', buffer = '', boot = false, sessionId, historyRestored = false, activeCatalogRead = false, agentChecked = false;
  const updates = new Set();
  child.stdout.setEncoding('utf8');
  child.stderr.on('data', chunk => { stderr += chunk; });
  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(serverFailure || new Error(`Host gate timeout: boot=${boot} session=${sessionId} history=${historyRestored} modelCalls=${modelCalls} actions=${actionCalls} ${stderr.slice(-2000)}`)), 60000);
      const fail = error => { clearTimeout(timer); reject(serverFailure || error); };
      child.once('error', fail);
      child.once('exit', code => fail(new Error(`Pi exited ${code}: ${stderr.slice(-2000)}`)));
      child.stdout.on('data', chunk => {
        try {
          buffer += chunk.toString();
          let index;
          while ((index = buffer.indexOf('\n')) >= 0) {
            const line = buffer.slice(0, index); buffer = buffer.slice(index + 1);
            if (!line.trim()) continue;
            const event = JSON.parse(line);
            assert.ok(Number.isFinite(event.piEmittedAtMs) && event.piEmittedAtMs > 0,
              `Every Pi RPC record must carry emission time: ${event.type}`);
            assert.ok(Math.abs(event.piEmittedAtMs - Date.now()) < 60000);
            if (runNumber === 1 && ['message_start', 'message_update', 'message_end', 'tool_execution_start',
              'tool_execution_update', 'tool_execution_end', 'agent_start', 'agent_end', 'agent_settled'].includes(event.type)) capturedEvents.push(event);
            if (event.type === 'message_update') updates.add(event.assistantMessageEvent.type);
            if (event.type === 'runtime_boot') { boot = true; assert.equal(event.pi, '0.87.0'); }
            if (event.type === 'runtime_error') throw new Error(event.message);
            if (event.type === 'response' && event.id === 'ready') {
              assert.equal(event.success, true);
              sessionId = event.data.sessionId;
              assert.equal(event.data.model.reasoning, true);
              if (!gate) assert.equal(event.data.thinkingLevel, restore ? 'low' : 'high', 'Explicit composer setting overrides resumed session; inheritance preserves Pi defaults');
              assert.equal(event.data.model.reasoning, true);
              assert.deepEqual(event.data.model.input, ['text', 'image']);
              assert.equal(event.data.model.compat, undefined, 'capability catalog is not imported; wire compatibility is checked on requests');
              child.stdin.write('{"id":"history","type":"get_messages"}\n');
            }
            if (event.type === 'response' && event.id === 'history') {
              assert.equal(event.success, true);
              assert.ok(Array.isArray(event.data.messages));
              assert.equal(JSON.stringify(event.data.messages).includes('BBUI_REAL_FIRST'), restore);
              if (gate) assert.equal(event.data.messages.length, 0);
              if (restore) {
                const restored = event.data.messages.filter(message => message.role === 'toolResult' && message.toolName === 'task_state').at(-1);
                assert.equal(restored.details.bbuiTask.status, 'waiting_user');
                assert.equal(restored.details.bbuiTask.constraints[0].text, constraint);
                assert.equal(restored.details.bbuiTask.id, 'real-task');
              }
              assert.equal(actionCalls, 0, 'get_messages must not replay phone actions');
              historyRestored = true;
              child.stdin.write(JSON.stringify({ id: 'run', type: 'prompt', message:
                `${marker}: 只观察当前页面并保存任务进展。用户约束：${constraint} Unicode \u2028 remains in this message.` }) + '\n');
              child.stdin.write(JSON.stringify({ id: 'active-catalog', type: 'bbui_sessions', action: 'list' }) + '\n');
            }
            if (event.type === 'response' && event.id === 'active-catalog') {
              assert.equal(event.success, true);
              assert.ok(Array.isArray(event.data.sessions));
              activeCatalogRead = true;
              if (agentChecked) { clearTimeout(timer); resolve(); }
            }
            if (event.type === 'agent_settled') child.stdin.write('{"id":"after","type":"get_messages"}\n');
            if (event.type === 'response' && event.id === 'after') {
              assert.equal(event.success, true);
              if (serverFailure) throw serverFailure;
              const messages = event.data.messages;
              const assistant = messages.filter(message => message.role === 'assistant').at(-1);
              assert.equal(assistant.content.find(part => part.type === 'text').text, reply);
              assert.equal(assistant.content.find(part => part.type === 'thinking').thinking, reasoning);
              for (const step of plan) {
                const result = messages.find(message => message.role === 'toolResult' && message.toolCallId === step.id);
                assert.ok(result, `RPC history must retain ${step.label}`);
                if (step.label === 'invalid-intent') {
                  assert.equal(result.isError, true);
                  assert.match(String(result.details.错误), /意图/);
                  assert.deepEqual(result.content, [], 'UI history excludes raw tool content');
                } else if (step.label === 'skill-denied') {
                  assert.equal(result.isError, true, 'Pi must mark thrown skill-reader errors as failed');
                  assert.ok(!JSON.stringify(result).includes('PRIVATE_FIXTURE'));
                } else assert.equal(result.isError, false, `${step.label} must execute successfully`);
                if (step.name === 'read' && step.label !== 'skill-denied') {
                  assert.equal(result.details.bbuiTool.kind, 'skill');
                  assert.deepEqual(result.content, [], 'UI history excludes skill document content');
                  assert.ok(!JSON.stringify(result.details).includes(stage), 'UI metadata excludes packaged paths');
                }
                if (step.name === 'task_state') {
                  const expected = step.label === 'restore-read' ? task('waiting_user', 'BBUI_REAL_FIRST')
                    : step.label === 'active' ? task('active', marker) : currentTask;
                  assert.equal(result.details.bbuiTask.id, expected.id);
                  assert.equal(result.details.bbuiTask.status, expected.status);
                  assert.equal(result.details.bbuiTask.summary, expected.summary);
                  assert.equal(result.details.bbuiTask.constraints[0].text, constraint);
                }
              }
              agentChecked = true;
              if (activeCatalogRead) { clearTimeout(timer); resolve(); }
            }
          }
        } catch (error) { fail(error); }
      });
      child.stdin.write('{"id":"ready","type":"get_state"}\n');
    });
    assert.equal(boot, true);
    assert.equal(actionCalls, 1);
    assert.equal(modelCalls, plan.length + 1);
    assert.equal(sawImage, true);
    assert.ok(sessionId);
    assert.equal(historyRestored, true);
    assert.equal(activeCatalogRead, true, 'catalog responses must remain on stdout after Pi takes over RPC output');
    for (const type of ['text_delta', 'thinking_delta', 'toolcall_delta']) assert.ok(updates.has(type), type);
    return sessionId;
  } finally {
    child.stdin.end();
    await new Promise(resolve => {
      const timeout = setTimeout(() => { child.kill(); resolve(); }, 3000);
      child.once('exit', () => { clearTimeout(timeout); resolve(); });
    });
  }
}
async function catalogGate() {
  await writeFile(config, JSON.stringify({ catalogOnly: true }));
  const child = spawn(process.execPath, ['--require', offlineGuard, path.join(stage, 'bootstrap.mjs'), config], {
    cwd: stage, env: { ...process.env, NODE_PATH: '', NODE_OPTIONS: '', PI_CODING_AGENT_DIR: home },
    stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true,
  });
  const waiting = new Map();
  let buffer = '';
  child.stdout.setEncoding('utf8');
  child.stdout.on('data', chunk => {
    buffer += chunk;
    let index;
    while ((index = buffer.indexOf('\n')) >= 0) {
      const event = JSON.parse(buffer.slice(0, index)); buffer = buffer.slice(index + 1);
      waiting.get(event.id)?.(event);
    }
  });
  let sequence = 0;
  const command = body => new Promise((resolve, reject) => {
    const id = `catalog-${++sequence}`;
    const timer = setTimeout(() => { waiting.delete(id); reject(new Error(`catalog timeout: ${body.action ?? body.type}`)); }, 15000);
    waiting.set(id, result => { clearTimeout(timer); waiting.delete(id); result.success ? resolve(result.data) : reject(new Error(result.error)); });
    child.stdin.write(JSON.stringify({ id, type: 'bbui_sessions', ...body }) + '\n');
  });
  try {
    assert.equal((await command({ type: 'get_state' })).sessionId, '');
    const a = await command({ action: 'create' });
    const b = await command({ action: 'create' });
    await command({ action: 'rename', sessionId: a.sessionId, title: '中文🙂\u2028会话' });
    const rows = (await command({ action: 'list' })).sessions;
    assert.equal(rows.length, 2);
    assert.equal(rows.find(row => row.id === a.sessionId).title, '中文🙂\u2028会话');
    assert.deepEqual((await command({ action: 'history', sessionId: a.sessionId })).messages, []);
    await command({ action: 'delete', sessionId: b.sessionId });
    assert.equal((await command({ action: 'list' })).sessions.length, 1);
    assert.equal(modelCalls, 0); assert.equal(actionCalls, 0);
    return a.sessionId;
  } finally { child.kill(); }
}

try {
  const selectedSession = await catalogGate();
  await writeFile(path.join(home, 'settings.json'), JSON.stringify({ defaultThinkingLevel: 'high', testPreference: 'preserved' }));
  const first = await run('BBUI_REAL_FIRST', false, false, selectedSession);
  assert.equal(first, selectedSession, 'Execution must use the selected session');
  const gate = await run('BBUI_GATE_ONLY', true, false);
  const resumed = await run('BBUI_REAL_RESUMED', false, true, selectedSession);
  assert.equal(resumed, first, 'Real task must resume its prior session');
  assert.notEqual(gate, first, 'Gate must have an isolated session');
  const settings = JSON.parse(await readFile(path.join(home, 'settings.json'), 'utf8'));
  assert.equal(settings.defaultThinkingLevel, 'high');
  assert.equal(settings.testPreference, 'preserved');
  await writeFile(path.join(workspace, 'pi-chat-events.json'), JSON.stringify(capturedEvents, null, 2));
  console.log('PASS exact Pi payload (local model fixture only): required intent correction without dispatch, task_state lifecycle/context/restart, no action replay, DeepSeek reasoning/image roundtrip, Unicode streaming, gate isolation, preserved settings');
} finally {
  server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
}
