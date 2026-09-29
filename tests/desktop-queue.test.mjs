import test, { before } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { DesktopHost } from '../desktop/host.mjs';
import { restoreState } from '../desktop/state.mjs';
import { PiProcess } from '../desktop/rpc.mjs';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';

before(async () => { await mkdir(path.resolve('runs'), { recursive: true }); });

const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const turn = () => new Promise(resolve => setImmediate(resolve));
const send = (id = 'one', sessionId = 's') => ({ type: 'send', submissionId: id, sessionId, text: '任务 ' + id });
function fixture() {
  const host = new DesktopHost({ data: '' });
  host.state = restoreState({ selected: 's' }); host.sessions = [];
  host.settings = { revision: 'v1', defaultModel: { connectionId: 'c', modelId: 'm' },
    connections: [{ id: 'c', baseUrl: 'https://example.test', apiKey: 'fixture', models: [{ id: 'm' }] }] };
  host.connected = true; host.catalog = { resolve: async () => {} };
  host.publish = () => {}; host.runNext = async () => {};
  host.writes = []; host.writeState = async value => { host.writes.push(value); };
  host.resumeDevice = async () => {}; host.bridge = { setStopped: async () => {} };
  host.deviceControl = async () => {}; return host;
}

function titleFixture() {
  const host = fixture(); host.pause('user');
  host.state.titleGeneration.s = { status: 'eligible' };
  host.sessions = [{ id: 's', title: '新会话' }, { id: 't', title: '其他会话' }];
  host.catalog.execute = async command => {
    if (command.action === 'list') return { sessions: host.sessions };
    if (command.action === 'history') return { messages: [] };
    if (command.action === 'rename') {
      const session = host.sessions.find(item => item.id === command.sessionId);
      if (!session) throw new Error('会话已删除');
      session.title = command.title; return {};
    }
    if (command.action === 'delete') { host.sessions = host.sessions.filter(item => item.id !== command.sessionId); return {}; }
  };
  return host;
}

test('automatic title runs once after durable acceptance, binds original config and targets its session', async () => {
  const host = titleFixture(), response = deferred(); const requests = [];
  host.requestTitle = (text, config) => { requests.push({ text, config }); return response.promise; };
  await host.command(send());
  assert.equal(requests.length, 1); assert.equal(host.writes[0].titleGeneration.s.status, 'attempted');
  host.settings.connections[0].apiKey = 'changed'; host.state.selected = 't';
  await host.command(send()); await host.command(send('two'));
  assert.equal(requests.length, 1); assert.equal(requests[0].config.apiKey, 'fixture');
  response.resolve('任务简短标题'); await Promise.all(host.titleJobs);
  assert.equal(host.sessions[0].title, '任务简短标题'); assert.equal(host.sessions[1].title, '其他会话');
  assert.equal(host.state.queue.length, 2);
});

test('failed acceptance never generates a title and retains eligibility for the next successful submission', async () => {
  const host = titleFixture(); let calls = 0;
  host.requestTitle = async () => { calls++; return '标题'; };
  host.writeState = async () => { throw new Error('disk'); };
  await assert.rejects(host.command(send()), /disk/); assert.equal(calls, 0);
  assert.equal(host.state.titleGeneration.s.status, 'eligible');
  host.writeState = async () => {}; await host.command(send('two')); await Promise.all(host.titleJobs);
  assert.equal(calls, 1);
});

test('a concurrent accepted submission claims naming after an earlier acceptance fails to persist', async () => {
  const host = titleFixture(), blocked = deferred(); const named = []; let saves = 0;
  host.requestTitle = async text => { named.push(text); return '成功任务标题'; };
  host.writeState = async () => { if (++saves === 1) await blocked.promise; };
  const first = host.command(send()); const rejected = assert.rejects(first, /disk/); await turn();
  const second = host.command(send('two')); blocked.reject(new Error('disk'));
  await rejected; await second; await Promise.all(host.titleJobs);
  assert.deepEqual(named, ['任务 two']); assert.equal(host.state.titleGeneration.s.submissionId, 'two');
});

test('manual rename including an unchanged title wins over pending generation', async () => {
  for (const name of ['用户标题', '新会话']) {
    const host = titleFixture(), response = deferred(); host.requestTitle = () => response.promise;
    await host.command(send()); await host.command({ type: 'renameSession', sessionId: 's', title: name });
    response.resolve('迟到标题'); await Promise.all(host.titleJobs);
    assert.equal(host.sessions[0].title, name); assert.equal(host.state.titleGeneration.s.status, 'manual');
  }
});

test('deleted or closing sessions ignore late title results without recreating records', async () => {
  for (const action of ['delete', 'close']) {
    const host = titleFixture(), response = deferred(); host.requestTitle = () => response.promise;
    await host.command(send());
    if (action === 'delete') await host.command({ type: 'deleteSession', sessionId: 's' });
    else host.closing = true;
    response.resolve('迟到标题'); await Promise.all(host.titleJobs);
    assert.equal(host.sessions.find(item => item.id === 's')?.title, action === 'close' ? '新会话' : undefined);
  }
});

test('automatic title failures are silent and never pause a running queue or retry on later submissions', async () => {
  const host = titleFixture(); host.unpause(); let calls = 0;
  host.requestTitle = async () => { calls++; throw new Error('API error'); };
  await host.command(send()); await Promise.all(host.titleJobs); await host.command(send('two'));
  assert.equal(calls, 1); assert.equal(host.state.paused, false); assert.equal(host.sessionError, undefined);
  assert.equal(host.sessions[0].title, '新会话');
});

test('legacy and restored attempted/manual sessions are never retrospectively named', async () => {
  for (const marker of [undefined, { status: 'attempted' }, { status: 'manual' }]) {
    const host = titleFixture(); host.state = restoreState({ selected: 's', titleGeneration: marker ? { s: marker } : {} });
    let calls = 0; host.requestTitle = async () => { calls++; return '标题'; };
    await host.command(send()); assert.equal(calls, 0);
  }
});

test('title writes wait for lifecycle transitions and use the live Pi manager while running', async () => {
  const host = titleFixture(), response = deferred(), writes = [];
  host.requestTitle = () => response.promise;
  await host.command(send()); host.starting = true; response.resolve('运行中标题'); await turn();
  assert.equal(host.sessions[0].title, '新会话');
  host.state.active = { sessionId: 's' }; host.agent = { request: async value => { writes.push(value); } };
  host.starting = false; host.emit('sessionWritable'); await Promise.all(host.titleJobs);
  assert.deepEqual(writes, [{ type: 'set_session_name', name: '运行中标题' }]);
  assert.equal(host.sessions[0].title, '新会话'); // Catalog never writes behind the live manager.
});

test('fresh durable submissions release automatic idle pauses; user/unknown and protected work stay paused', async () => {
  for (const reason of ['restart', 'stop', 'error', 'environment', 'user', 'unknown']) {
    const host = fixture(); host.state.pauseReasons = [reason];
    await host.command(send());
    assert.equal(host.state.paused, ['user', 'unknown'].includes(reason), reason);
    assert.equal(host.submissionResult.accepted, true);
  }
  for (const protect of [host => host.state.queue.push({ id: 'prior' }), host => host.state.interrupted.push({ id: 'prior' }),
    host => { host.state.active = { id: 'prior' }; }, host => { host.control.mode = 'manual'; }, host => { host.question = {}; }]) {
    const host = fixture(); protect(host); await host.command(send()); assert.equal(host.state.paused, true);
  }
  assert.ok(restoreState({ paused: true }).pauseReasons.includes('user'));
});

test('failed save removes only that submission before subsequent snapshots and does not poison the save chain', async () => {
  const host = fixture(), writing = deferred(); host.pause('user'); host.state.views.s = { text: '草稿' };
  let count = 0; host.writeState = async value => { if (++count === 1) await writing.promise; host.writes.push(value); };
  const first = host.command(send()); const rejected = assert.rejects(first, /disk/);
  await turn(); const later = host.save(); const second = host.command(send('two', 't'));
  writing.reject(new Error('disk')); await rejected; await later; await second;
  assert.deepEqual(host.state.queue.map(task => task.id), ['two']); assert.equal(host.state.views.s.text, '草稿');
  assert.equal(host.state.submissions.one, undefined);
  assert.ok(host.writes.every(value => !value.queue.some(task => task.id === 'one') && !value.submissions.one));
  assert.equal(host.submissionResult.id, 'two');
});

test('unrelated saves exclude provisional submissions and a pending head blocks committed followers', async () => {
  const host = fixture(), lookup = deferred(); host.unpause();
  host.catalog.resolve = id => id === 's' ? lookup.promise : Promise.resolve();
  const first = host.command(send()); await host.command(send('two', 't')); await host.save();
  assert.deepEqual(host.writes.at(-1).queue.map(task => task.id), ['two']);
  let starts = 0; host.ensureDevice = async () => { starts++; };
  await DesktopHost.prototype.runNext.call(host); assert.equal(starts, 0);
  lookup.resolve(); await first;
  assert.deepEqual(host.state.queue.map(task => task.id), ['one', 'two']);
});

test('new draft written during send persistence survives ACK; unchanged draft clears in its own session', async () => {
  const host = fixture(), writing = deferred(); host.pause('user');
  host.state.views.s = { text: 'old', scrollTop: 12 }; host.writeState = () => writing.promise;
  const pending = host.command(send()); await turn();
  await host.command({ type: 'viewState', sessionId: 's', text: 'new', scrollTop: 17 });
  host.state.selected = 't'; host.state.views.t = { text: 'other' };
  writing.resolve(); await pending;
  assert.equal(host.state.views.s.text, 'new'); assert.equal(host.state.views.t.text, 'other');
  await host.command(send('two')); assert.equal(host.state.views.s.text, ''); assert.equal(host.state.views.s.scrollTop, 17);
});

test('duplicate completed and pending submissions are acknowledged without enqueueing or releasing pause again', async () => {
  const host = fixture(), writing = deferred(); host.writeState = () => writing.promise;
  const first = host.command(send()); const duplicate = host.command(send());
  await assert.rejects(host.command(send('one', 'other')), /编号/);
  writing.resolve(); await Promise.all([first, duplicate]);
  assert.equal(host.state.queue.length, 1);
  host.state.queue = []; host.pause('stop'); await host.command(send());
  assert.equal(host.state.queue.length, 0); assert.equal(host.state.paused, true);
});

test('new STOP or user pause wins during both submission writes and automatic resume', async () => {
  for (const phase of ['save', 'resume', 'release']) for (const action of ['stop', 'pauseQueue']) {
    const host = fixture(), block = deferred(); let writes = 0;
    if (phase !== 'resume') host.writeState = async () => { if (++writes === (phase === 'save' ? 1 : 2)) await block.promise; };
    else host.resumeDevice = () => block.promise;
    const pending = host.command(send()); await turn(); await host.command({ type: action }); block.resolve(); await pending;
    assert.equal(host.state.paused, true, phase + action); assert.equal(host.submissionResult.accepted, true);
  }
});

test('pause-release persistence failure retains accepted durable task and pause, then future writes recover', async () => {
  const host = fixture(); let writes = 0;
  host.writeState = async value => { if (++writes === 2) throw new Error('release disk'); host.writes.push(value); };
  await host.command(send()); await host.save();
  assert.equal(host.submissionResult.accepted, true); assert.equal(host.state.paused, true);
  assert.equal(host.writes.at(-1).queue[0].id, 'one'); assert.equal(host.writes.at(-1).paused, true);
  assert.match(host.sessionError, /任务已排队/);
});

test('bound model/configuration survives settings and selected-session changes while submission is saving', async () => {
  const host = fixture(), writing = deferred(); host.pause('user'); host.writeState = () => writing.promise;
  const pending = host.command(send()); await turn(); host.settings.revision = 'v2'; host.settings.defaultModel.modelId = 'changed'; host.state.selected = 'other';
  writing.resolve(); await pending;
  assert.equal(host.state.queue[0].selection.modelId, 'm'); assert.equal(host.state.queue[0].configRevision, 'v1'); assert.equal(host.state.queue[0].sessionId, 's');
});

test('invalid per-session model is rejected before queue/draft changes', async () => {
  const host = fixture(); host.state.selections.s = { connectionId: 'c', modelId: 'gone' };
  await assert.rejects(host.command(send()), /配置模型/); assert.equal(host.state.queue.length, 0);
});

function runningHost() {
  const host = fixture(); host.unpause(); host.state.active = { id: 'active', deviceId: 'android:', sessionId: 's', text: 'original' };
  host.control = { id: 'control', mode: 'running', sessionId: 's', taskId: 'active' };
  host.agent = { request: async () => {} }; return host;
}
const steer = () => ({ ...send(), type: 'steer' });
const steerQueued = () => ({ type: 'steerQueued', sessionId: 's', submissionId: 'queued', controlId: 'control', runId: 'run' });
function queuedSteeringHost() {
  const host = runningHost(); host.runId = 'run'; host.control.canSteer = true;
  host.state.queue = [{ id: 'before', sessionId: 'other', text: 'before' },
    { id: 'queued', deviceId: 'android:', sessionId: 's', text: '请额外核对日期', configRevision: 'v1', selection: { connectionId: 'c', modelId: 'm' } },
    { id: 'after', sessionId: 'other', text: 'after' }];
  host.state.submissions.queued = { sessionId: 's', type: 'send', status: 'accepted' };
  host.state.views.s = { text: '正在输入的新草稿', scrollTop: 12 };
  return host;
}

test('steerQueued durably claims the original FIFO item before dispatch and deduplicates clicks without touching drafts', async () => {
  const host = queuedSteeringHost(), reply = deferred(); let calls = 0;
  host.agent.request = async command => {
    calls++; assert.deepEqual(command, { type: 'steer', message: '请额外核对日期' });
    assert.ok(!host.writes.at(-1).queue.some(item => item.id === 'queued'));
    assert.equal(host.writes.at(-1).interrupted[0].steeringStatus, 'unconfirmed');
    await reply.promise;
  };
  const first = host.command(steerQueued()), duplicate = host.command(steerQueued()); await turn();
  assert.equal(calls, 1); reply.resolve(); await Promise.all([first, duplicate]); await host.command(steerQueued());
  assert.equal(calls, 1); assert.deepEqual(host.state.queue.map(item => item.id), ['before', 'after']);
  assert.deepEqual(host.state.active.steering, ['请额外核对日期']); assert.equal(host.state.interrupted.length, 0);
  assert.equal(host.state.views.s.text, '正在输入的新草稿');
  assert.equal(host.state.submissions.queued.queuedSteering.status, 'accepted');
  assert.equal(host.submissionResult, undefined);
});

test('steerQueued rejects stale runs, control changes, other sessions, questions and non-durable items without fallback sends', async () => {
  for (const change of [command => { command.runId = 'old'; }, command => { command.controlId = 'old'; },
    command => { command.sessionId = 'other'; }, (_, host) => { host.question = {}; },
    (_, host) => { host.control.canSteer = false; }, (_, host) => { host.pendingSubmissions.set('queued', {}); },
    command => { command.submissionId = 'missing'; }]) {
    const host = queuedSteeringHost(), command = steerQueued(); let calls = 0;
    host.agent.request = async () => { calls++; }; change(command, host);
    await assert.rejects(host.command(command)); assert.equal(calls, 0);
    assert.deepEqual(host.state.queue.map(item => item.id), ['before', 'queued', 'after']);
    assert.equal(host.state.interrupted.length, 0); assert.equal(host.state.views.s.text, '正在输入的新草稿');
  }
});

test('steerQueued claim persistence failure restores the original FIFO position and never dispatches', async () => {
  const host = queuedSteeringHost(); let calls = 0;
  host.agent.request = async () => { calls++; }; host.writeState = async () => { throw new Error('claim disk'); };
  await assert.rejects(host.command(steerQueued()), /claim disk/);
  assert.equal(calls, 0); assert.deepEqual(host.state.queue.map(item => item.id), ['before', 'queued', 'after']);
  assert.equal(host.state.interrupted.length, 0); assert.equal(host.state.submissions.queued.queuedSteering, undefined);
  host.writeState = async () => {}; await host.command(steerQueued()); assert.equal(calls, 1);
});

test('claim rollback keeps FIFO ordering when an earlier waiting item was already consumed', async () => {
  const host = queuedSteeringHost(), writing = deferred(); host.writeState = () => writing.promise;
  const pending = host.command(steerQueued()), rejected = assert.rejects(pending, /disk/); await turn();
  host.state.queue.shift(); host.state.queue.push({ id: 'new', text: 'new' }); writing.reject(new Error('disk'));
  await rejected; assert.deepEqual(host.state.queue.map(item => item.id), ['queued', 'after', 'new']);
});

test('STOP or a new run after durable steerQueued claim leaves an explicitly not-sent record, never a FIFO replay', async () => {
  for (const change of [host => { host.control = { ...host.control, id: 'stop', mode: 'stopped' }; },
    host => { host.runId = 'new'; host.state.active = { id: 'new', sessionId: 's' }; }, host => { host.question = {}; }]) {
    const host = queuedSteeringHost(), writing = deferred(); let calls = 0;
    host.agent.request = async () => { calls++; }; host.writeState = async () => writing.promise;
    const pending = host.command(steerQueued()), rejected = assert.rejects(pending, /未发送/); await turn();
    change(host); writing.resolve(); await rejected;
    assert.equal(calls, 0); assert.equal(host.state.interrupted[0].steeringStatus, 'not_sent');
    assert.ok(!host.state.queue.some(item => item.id === 'queued'));
  }
});

test('uncertain queued steering preserves original text and receipt across restart without replay', async () => {
  const host = queuedSteeringHost(); let calls = 0;
  host.agent.request = async () => { calls++; throw new Error('Pi 请求超时'); };
  await assert.rejects(host.command(steerQueued()), /超时/); await assert.rejects(host.command(steerQueued()), /不会自动重发/);
  assert.equal(calls, 1); assert.equal(host.state.interrupted[0].steeringStatus, 'unconfirmed');
  assert.equal(host.state.interrupted[0].text, '请额外核对日期');
  const restored = restoreState(host.state);
  assert.equal(restored.queue.length, 0);
  assert.equal(restored.interrupted.find(item => item.id === 'queued').steeringStatus, 'unconfirmed');
});

test('an explicit Pi rejection is retained as not sent rather than a transport uncertainty', async () => {
  const host = queuedSteeringHost(); let calls = 0;
  host.agent.request = async () => { calls++; throw Object.assign(new Error('Pi rejected this request'), { responseReceived: true }); };
  await assert.rejects(host.command(steerQueued()), /rejected/);
  assert.equal(host.state.interrupted[0].steeringStatus, 'not_sent');
  assert.ok(!host.state.queue.some(item => item.id === 'queued'));
  await assert.rejects(host.command(steerQueued()), /不会自动重发/); assert.equal(calls, 1);
});

test('in-flight queued claims explicitly reject cancellation/deletion, and rollback never undoes STOP', async () => {
  const host = queuedSteeringHost(), writing = deferred(); let calls = 0;
  host.agent.request = async () => { calls++; }; host.writeState = () => writing.promise;
  const pending = host.command(steerQueued()), rejected = assert.rejects(pending, /disk/); await turn();
  await assert.rejects(host.command({ type: 'cancelQueued', submissionId: 'queued' }), /正在立即补充/);
  host.state.active = null; host.agent = null; await host.command({ type: 'stop' });
  await assert.rejects(host.command({ type: 'deleteSession', sessionId: 's' }), /确认接收/);
  writing.reject(new Error('disk')); await rejected;
  assert.equal(calls, 0); assert.deepEqual(host.state.queue.map(item => item.id), ['before', 'queued', 'after']);
  assert.equal(host.state.paused, true); assert.equal(host.control.mode, 'stopped');
  assert.ok(host.state.pauseReasons.includes('stop'));
});

test('late queued steering ACK records known acceptance without supplementing a replacement run', async () => {
  const host = queuedSteeringHost(), reply = deferred(); host.agent.request = () => reply.promise;
  const pending = host.command(steerQueued()); await turn();
  host.pendingSteering.get('queued').settled = true;
  host.state.active = { id: 'replacement', sessionId: 's' }; host.runId = 'replacement';
  reply.resolve(); await pending;
  assert.equal(host.state.active.steering, undefined); assert.equal(host.state.interrupted[0].steeringStatus, 'accepted');
  assert.equal(host.state.submissions.queued.queuedSteering.status, 'accepted');
  assert.equal(host.state.views.s.text, '正在输入的新草稿');
});

test('a question appearing after dispatch does not turn a same-run steering ACK into an interrupted task', async () => {
  const host = queuedSteeringHost(), reply = deferred(); host.agent.request = () => reply.promise;
  const pending = host.command(steerQueued()); await turn(); host.question = {}; host.control.canSteer = false;
  reply.resolve(); await pending;
  assert.deepEqual(host.state.active.steering, ['请额外核对日期']); assert.equal(host.state.interrupted.length, 0);
});

test('timeout followed by a late ACK updates receipt and original continuation without injecting into a replacement run', async () => {
  for (const received of [true, false]) {
    const host = queuedSteeringHost(); let late, calls = 0;
    host.agent.request = async (_command, _timeout, options) => { calls++; late = options.onLateResponse; throw new Error('Pi 请求超时'); };
    const original = host.state.active;
    await assert.rejects(host.command(steerQueued()), /超时/);
    host.state.interrupted.push({ ...original, interruptedReason: 'running' });
    host.state.active = { id: 'replacement', sessionId: 's' }; host.runId = 'replacement';
    await late(received);
    assert.equal(host.state.interrupted.find(item => item.id === 'queued').steeringStatus, received ? 'accepted' : 'not_sent');
    assert.equal(host.state.active.steering, undefined); assert.equal(calls, 1);
    assert.deepEqual(host.state.interrupted.find(item => item.id === 'active').steering, received ? ['请额外核对日期'] : undefined);
    assert.equal(host.state.views.s.text, '正在输入的新草稿');
  }
});

test('late ACK cannot recreate a supplement already explicitly continued, even when saving the receipt fails', async () => {
  const host = queuedSteeringHost(); let late;
  host.agent.request = async (_command, _timeout, options) => { late = options.onLateResponse; throw new Error('timeout'); };
  await assert.rejects(host.command(steerQueued()), /timeout/);
  host.state.active = null; host.agent = null; host.control.mode = 'stopped';
  await host.command({ type: 'resumeInterrupted', submissionId: 'queued' });
  host.writeState = async () => { throw new Error('receipt disk'); };
  await assert.rejects(late(true), /receipt disk/);
  assert.equal(host.state.interrupted.length, 0);
  assert.equal(host.state.queue.filter(item => item.text.includes('请额外核对日期')).length, 1);
});

test('deleting a session after an uncertain supplement invalidates its late acknowledgement', async () => {
  const host = queuedSteeringHost(); let late;
  host.agent.request = async (_command, _timeout, options) => { late = options.onLateResponse; throw new Error('timeout'); };
  await assert.rejects(host.command(steerQueued()), /timeout/);
  host.state.active = null; host.agent = null; host.state.selected = 'other';
  host.catalog.execute = async command => command.action === 'list' ? { sessions: [{ id: 'other', title: '其他' }] } : {};
  await host.command({ type: 'deleteSession', sessionId: 's' }); await late(true);
  assert.equal(host.state.submissions.queued, undefined); assert.equal(host.state.interrupted.length, 0);
});

test('Pi RPC retains only opted-in timed-out requests until a late response or process exit', async () => {
  for (const finish of ['accepted', 'rejected', 'closed', 'ordinary']) {
    const child = Object.assign(new EventEmitter(), { stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough() });
    child.kill = () => child.emit('close'); child.stdin.on('finish', () => child.emit('close'));
    let command, receipt;
    child.stdin.on('data', data => { command = JSON.parse(data.toString()); });
    const worker = new PiProcess({ spawn: () => child, resource: () => 'fixture' }, {}, '.');
    const options = finish === 'ordinary' ? {} : { onLateResponse: accepted => { receipt = accepted; } };
    await assert.rejects(worker.request({ type: 'steer', message: '补充' }, 10, options), /超时/);
    assert.equal(worker.pending.size, finish === 'ordinary' ? 0 : 1);
    if (finish === 'accepted' || finish === 'rejected') {
      child.stdout.write(JSON.stringify({ type: 'response', id: command.id, success: finish === 'accepted', error: 'rejected' }) + '\n');
      await turn(); assert.equal(receipt, finish === 'accepted'); assert.equal(worker.pending.size, 0);
    }
    await worker.close(); assert.equal(worker.pending.size, 0);
    if (finish === 'closed' || finish === 'ordinary') assert.equal(receipt, undefined);
  }
});

test('post-ACK disk failure keeps the known acceptance receipt and cannot resend the original queued item', async () => {
  const host = queuedSteeringHost(); let saves = 0, calls = 0;
  host.agent.request = async () => { calls++; }; host.writeState = async () => { if (++saves === 2) throw new Error('result disk'); };
  await assert.rejects(host.command(steerQueued()), /result disk/); await host.command(steerQueued());
  assert.equal(calls, 1); assert.equal(host.state.interrupted[0].steeringStatus, 'accepted');
  assert.deepEqual(host.state.active.steering, ['请额外核对日期']);
});

test('explicitly continuing an uncertain supplement carries its receipt and observation requirement', async () => {
  const host = fixture(); host.state.interrupted = [{ id: 'queued', deviceId: 'android:', sessionId: 's', text: '原始补充', interruptedReason: 'steering', steeringStatus: 'unconfirmed' }];
  await host.command({ type: 'resumeInterrupted', submissionId: 'queued' });
  assert.match(host.state.queue[0].text, /接收结果未确认/); assert.match(host.state.queue[0].text, /先检查会话中之前的补充及已派发操作/);
  assert.match(host.state.queue[0].text, /不要重放不确定输入/); assert.equal(host.state.interrupted.length, 0);
});

test('steering persists submission ID before Pi ACK, deduplicates, and clears only matching draft revision', async () => {
  const host = runningHost(), response = deferred(); let calls = 0;
  host.state.views.s = { text: 'old' }; host.agent.request = async () => { calls++; await response.promise; };
  const first = host.command(steer()), duplicate = host.command(steer()); await turn();
  await assert.rejects(host.command({ ...steer(), sessionId: 'other' }), /编号/);
  assert.equal(host.writes[0].submissions.one.status, 'pending'); assert.equal(calls, 1);
  await host.command({ type: 'viewState', sessionId: 's', text: 'new', scrollTop: 0 });
  response.resolve(); await Promise.all([first, duplicate]); await host.command(steer());
  assert.equal(calls, 1); assert.deepEqual(host.state.active.steering, ['任务 one']); assert.equal(host.state.views.s.text, 'new');
});

test('question-time steering becomes one FIFO submission and repeat remains acknowledged', async () => {
  const host = runningHost(); host.question = {};
  await host.command(steer()); await host.command(steer());
  assert.equal(host.state.queue.length, 1); assert.equal(host.state.submissions.one.type, 'send'); assert.equal(host.submissionResult.accepted, true);
  assert.equal(host.snapshot().control.canSteer, false);
});

test('steering failures, STOP and settled runs never accept late ACK or resend same ID', async () => {
  for (const end of ['timeout', 'stop', 'settled']) {
    const host = runningHost(), response = deferred(); let calls = 0;
    host.agent.request = async () => { calls++; await response.promise; };
    const pending = host.command(steer()); const rejected = assert.rejects(pending); await turn();
    if (end === 'timeout') response.reject(new Error('Pi 请求超时'));
    else {
      if (end === 'stop') host.control = { ...host.control, id: 'new', mode: 'stopped' };
      else host.pendingSteering.get('one').settled = true;
      response.resolve();
    }
    await rejected; await assert.rejects(host.command(steer()), /未确认/);
    assert.equal(calls, 1); assert.equal(host.state.active.steering, undefined);
  }
});

test('pause while resumeQueue awaits device release remains authoritative', async () => {
  const host = fixture(), response = deferred(); host.resumeDevice = () => response.promise;
  const pending = host.command({ type: 'resumeQueue' }); await host.command({ type: 'pauseQueue' }); response.resolve(); await pending;
  assert.equal(host.state.paused, true); assert.ok(host.state.pauseReasons.includes('user'));
});

test('concurrent explicit continuation resumes an interrupted task at most once', async () => {
  const host = fixture(), response = deferred(); host.resumeDevice = () => response.promise;
  host.state.interrupted = [{ id: 'old', deviceId: 'android:', sessionId: 's', text: 'target', steering: ['addition'], interruptedReason: 'running' }];
  const first = host.command({ type: 'resumeInterrupted', submissionId: 'old' });
  const second = host.command({ type: 'resumeInterrupted', submissionId: 'old' }); const rejected = assert.rejects(second, /变化/);
  response.resolve(); await first; await rejected;
  assert.equal(host.state.queue.length, 1); assert.match(host.state.queue[0].text, /addition/);
});

test('repeated continuation incorporates each acknowledged steering instruction only once', async () => {
  for (const type of ['resumeTask', 'resumeInterrupted']) {
    const host = fixture();
    let task = { id: 'old', deviceId: 'android:', sessionId: 's', text: 'target', steering: ['unique-addition'], interruptedReason: 'running' };
    for (let attempt = 0; attempt < 2; attempt++) {
      host.state.interrupted = [task];
      host.control = { id: 'control', mode: 'stopped', taskId: task.id, sessionId: 's' };
      await host.command({ type, controlId: 'control', submissionId: task.id });
      task = host.state.queue.shift();
      assert.equal(task.text.split('unique-addition').length - 1, 1);
      assert.deepEqual(task.steering, []);
    }
  }
});

test('active-claim persistence failure returns task to queue without starting Pi', async () => {
  const host = fixture(); host.unpause(); host.data = await mkdtemp(path.resolve('runs/desktop-queue-'));
  await mkdir(path.join(host.data, 'configurations')); await writeFile(path.join(host.data, 'configurations/v1.json'), JSON.stringify({ value: host.settings }));
  host.platform.unseal = value => value; host.ensureDevice = async () => {};
  host.state.queue = [{ id: 'one', sessionId: 's', text: 'task', configRevision: 'v1', selection: { connectionId: 'c', modelId: 'm' } }];
  host.writeState = async () => { throw new Error('claim disk'); };
  await DesktopHost.prototype.runNext.call(host);
  assert.equal(host.agent, undefined); assert.equal(host.state.active, null); assert.equal(host.state.queue[0].id, 'one'); assert.equal(host.state.paused, true);
  assert.equal(host.control.mode, 'error'); assert.equal(host.control.canResume, false);
  await host.command({ type: 'cancelQueued', submissionId: 'one' });
  host.writeState = async () => {}; await host.command(send('new'));
  assert.equal(host.state.paused, false); assert.equal(host.state.queue[0].id, 'new');
});

test('unexpected Pi termination pauses remaining FIFO work and keeps the interrupted task', async () => {
  const host = runningHost(); host.state.queue = [{ id: 'waiting', sessionId: 'other' }];
  host.agent.close = async () => {}; host.bridge.settled = async () => {};
  host.loadHistory = async () => {}; host.refreshSessions = async () => {};
  let advanced = false; host.runNext = async () => { advanced = true; };
  await host.finishRun(true);
  assert.equal(host.state.paused, true); assert.equal(advanced, false);
  assert.equal(host.state.interrupted[0].id, 'active'); assert.equal(host.state.queue[0].id, 'waiting');
});
