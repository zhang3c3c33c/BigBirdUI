import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { DesktopHost } from '../desktop/host.mjs';

async function until(check, ms = 30000) {
  const end = Date.now() + ms;
  while (!check()) { if (Date.now() > end) throw new Error('Condition timed out'); await new Promise(resolve => setTimeout(resolve, 30)); }
}
test('real Pi RPC with local mock API: stream, question, FIFO, queued steering, revision binding and background titles', { timeout: 90000 }, async () => {
  const requests = [], titleRequests = [], titleResponses = []; let asked = false, serverError, finishHeldResponse;
  const api = createServer(async (req, res) => {
    try {
      let body = ''; for await (const chunk of req) body += chunk;
      const input = JSON.parse(body);
      const titleOnly = !input.tools?.length;
      (titleOnly ? titleRequests : requests).push({ model: input.model, authorization: req.headers.authorization, input });
      assert.equal(input.stream, true);
      if (!titleOnly) {
        assert.ok(input.tools.some(t => t.function.name === 'phone_action'));
        assert.ok(input.tools.some(t => t.function.name === 'ask_user_question'));
        assert.ok(!input.tools.some(t => /resume|host_control|shell/.test(t.function.name)));
      }
      res.writeHead(200, { 'Content-Type': 'text/event-stream' });
      const chunk = (delta, finish_reason = null) => res.write('data: ' + JSON.stringify({ id: 'mock', object: 'chat.completion.chunk', created: 1, model: input.model, choices: [{ index: 0, delta, finish_reason }] }) + '\n\n');
      chunk({ role: 'assistant' });
      if (titleOnly) {
        assert.ok(!JSON.stringify(input.messages).includes('image_url'));
        titleResponses.push(() => { chunk({ content: '测试提问与方案选择' }); chunk({}, 'stop'); res.end('data: [DONE]\n\n'); });
        return;
      }
      if (!asked) {
        asked = true;
        chunk({ tool_calls: [{ index: 0, id: 'question_one', type: 'function', function: { name: 'ask_user_question', arguments: JSON.stringify({ questions: [{ header: '选择', question: '测试使用哪个选项？', options: [{ label: '甲', description: '第一项' }, { label: '乙', description: '第二项' }], multiSelect: false }] }) } }] });
        chunk({}, 'tool_calls');
      } else {
        for (const text of ['本地', '模拟完成', '，中文 👋']) chunk({ content: text });
        if (requests.length === 2) {
          finishHeldResponse = () => { chunk({}, 'stop'); res.end('data: [DONE]\n\n'); };
          return;
        }
        chunk({}, 'stop');
      }
      res.end('data: [DONE]\n\n');
    } catch (error) { serverError = error; res.writeHead(500); res.end('{}'); }
  });
  await new Promise(resolve => api.listen(0, '127.0.0.1', resolve));
  const directory = await mkdtemp(path.resolve('runs/desktop-integration-'));
  const root = process.env.BBUI_TEST_APP || path.resolve('.desktop/app');
  const manifest = JSON.parse(await readFile(path.join(root, 'runtime-manifest.json')));
  const platform = { data: directory, root, resource: name => path.join(root, manifest.paths[name]),
    spawn: (file, args, options) => spawn(file, args, { ...options, windowsHide: true, shell: false }),
    seal: value => Buffer.from(JSON.stringify(value)).toString('base64'), unseal: value => JSON.parse(Buffer.from(value, 'base64').toString()) };
  const host = new DesktopHost(platform);
  const controls = [];
  host.ensureDevice = async () => { host.connected = true; host.bridge ||= { settled: async () => {}, close: async () => {}, setStopped: async () => {}, call: async () => ({ content: [], details: {} }) }; };
  host.deviceControl = async operation => { await host.ensureDevice(); controls.push(operation); return {}; };
  try {
    await host.initialize();
    const firstSession = host.state.selected;
    const config = { ...host.publicSettings(), serial: 'mock', connections: [{ id: 'mock', name: 'mock', provider: 'fixture', api: 'openai-completions', baseUrl: `http://127.0.0.1:${api.address().port}/v1`, apiKey: 'SECRET_ONE', models: [{ id: 'one', input: ['text', 'image'] }] }], defaultModel: { connectionId: 'mock', modelId: 'one', thinkingLevel: '' } };
    await host.saveSettings(config);
    await host.ensureDevice();
    await host.command({ type: 'send', sessionId: firstSession, submissionId: 'first', text: '提出测试问题' });
    await host.command({ type: 'newSession' }); const secondSession = host.state.selected;
    await host.command({ type: 'send', sessionId: secondSession, submissionId: 'second', text: '第二个测试任务' });
    await host.saveSettings({ ...host.publicSettings(), connections: [{ ...config.connections[0], apiKey: 'SECRET_TWO', models: [{ id: 'two', input: ['text', 'image'] }] }], defaultModel: { connectionId: 'mock', modelId: 'two', thinkingLevel: '' } });
    await host.command({ type: 'resumeQueue' });
    await until(() => host.question || host.status.phase === 'error');
    assert.equal(host.status.phase, 'running', host.sessionError);
    assert.equal(host.state.selected, secondSession); assert.equal(host.state.active.sessionId, firstSession);
    await until(() => titleResponses.length === 2);
    await host.command({ type: 'renameSession', sessionId: secondSession, title: '用户保留的名字' });
    titleResponses.forEach(respond => respond());
    await until(() => host.titleJobs.size === 0);
    assert.equal(host.sessions.find(session => session.id === firstSession).title, '测试提问与方案选择');
    assert.equal(host.sessions.find(session => session.id === secondSession).title, '用户保留的名字');
    await host.command({ type: 'selectSession', sessionId: firstSession });
    const q = host.question.record;
    await assert.rejects(host.command({ type: 'answerQuestion', sessionId: firstSession, runId: 'old', requestId: q.requestId, answers: [] }), /过期/);
    await host.command({ type: 'answerQuestion', sessionId: firstSession, runId: q.runId, requestId: q.requestId,
      answers: [{ questionId: 'q1', selected: ['甲'], text: '' }] });
    await until(() => finishHeldResponse);
    const supplement = '立即补充并核对测试日期';
    await host.command({ type: 'send', sessionId: firstSession, submissionId: 'queued-supplement', text: supplement });
    await host.command({ type: 'viewState', sessionId: firstSession, text: '下一条独立草稿', scrollTop: 0 });
    const queuedCommand = { type: 'steerQueued', sessionId: firstSession, submissionId: 'queued-supplement', controlId: host.control.id, runId: host.runId };
    await host.command(queuedCommand); await host.command(queuedCommand);
    assert.ok(!host.state.queue.some(item => item.id === 'queued-supplement'));
    assert.deepEqual(host.state.active.steering, [supplement]);
    assert.equal(host.state.views[firstSession].text, '下一条独立草稿');
    finishHeldResponse();
    await until(() => !host.state.active && !host.state.queue.length && !host.finishing);
    if (serverError) throw serverError;
    assert.equal(requests.length, 4);
    assert.equal(requests[2].input.messages.filter(message => message.role === 'user' && JSON.stringify(message.content).includes(supplement)).length, 1);
    assert.ok(!JSON.stringify(requests[3].input.messages).includes(supplement));
    assert.equal(titleRequests.length, 2);
    assert.ok(titleRequests.every(r => r.model === 'one' && r.authorization === 'Bearer SECRET_ONE'));
    assert.ok(requests.every(r => r.model === 'one' && r.authorization === 'Bearer SECRET_ONE'));
    assert.ok(JSON.stringify(host.snapshot()).includes('本地模拟完成'));
    assert.ok(!JSON.stringify(host.snapshot()).includes('SECRET'));
    for (const sessionId of [firstSession, secondSession]) {
      const history = await host.catalog.execute({ action: 'history', sessionId });
      assert.ok(!JSON.stringify(history).includes('测试提问与方案选择'));
      if (sessionId === firstSession) assert.equal(history.messages.filter(message => message.role === 'user' && JSON.stringify(message).includes(supplement)).length, 1);
    }
    const memory = await host.memory({ action: 'read' });
    await host.memory({ action: 'write', content: '测试记忆', revision: memory.revision });
    await assert.rejects(host.memory({ action: 'write', content: '过期覆盖', revision: memory.revision }), /已更新/);
    const source = path.join(directory, '旧会话 import.jsonl');
    const original = JSON.stringify({ type: 'session', version: 3, id: 'import-fixture', timestamp: new Date().toISOString(), cwd: directory }) + '\n';
    await writeFile(source, original);
    await host.importFiles([source]); await host.importFiles([source]);
    assert.equal((await host.catalog.list()).filter(s => s.id === 'import-fixture').length, 1);
    assert.equal(await readFile(source, 'utf8'), original);
    await host.command({ type: 'viewState', sessionId: secondSession, text: '独立草稿', scrollTop: 128 });
    await host.command({ type: 'pauseQueue' });
    await host.command({ type: 'send', sessionId: firstSession, submissionId: 'not-replayed', text: '重启不得自动执行' });
    await host.close();
    const restarted = new DesktopHost(platform); await restarted.initialize();
    assert.equal(restarted.state.active, undefined); assert.equal(restarted.state.queue.length, 0); assert.equal(restarted.state.paused, true);
    assert.equal(restarted.state.interrupted[0].id, 'not-replayed');
    assert.equal(restarted.state.views[secondSession].text, '独立草稿');
    assert.equal(restarted.state.views[secondSession].scrollTop, 128);
    assert.equal(restarted.settings.connections[0].apiKey, 'SECRET_TWO');
    assert.equal(requests.length, 4);
    assert.equal(titleRequests.length, 2);
    await restarted.close();
  } finally { if (!host.closing) await host.close(); api.closeAllConnections(); await new Promise(resolve => api.close(resolve)); }
});
