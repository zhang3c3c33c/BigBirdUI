import test from 'node:test';
import assert from 'node:assert/strict';
import { DesktopHost } from '../desktop/host.mjs';

test('takeover waits for old dispatch and a concurrent user STOP prevents granting manual input', async () => {
  const host = new DesktopHost({ data: '' });
  host.state = { paused: false, active: { sessionId: 's' } };
  host.control = { id: 'control', mode: 'running', sessionId: 's' };
  host.publish = () => {};
  let settle; const pending = new Promise(resolve => { settle = resolve; });
  const calls = [];
  host.deviceControl = async operation => { calls.push(operation); return { token: 'manual-token' }; };
  host.bridge = { settled: () => pending, setStopped: async () => {} };
  host.finished = Promise.resolve();
  host.agent = { request: async command => { calls.push(command.type); host.agent = null; host.state.active = null; } };
  const takeover = host.takeover('control');
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(calls, ['hold', 'abort']);
  assert.equal(host.control.mode, 'taking_over');
  await host.stop(); settle();
  await assert.rejects(takeover, /STOP/);
  assert.ok(!calls.includes('manual')); assert.equal(host.control.mode, 'stopped');
});

test('failed submission acknowledges rejection without erasing the draft or stopping another session', () => {
  const host = new DesktopHost({ data: '' });
  host.state = { paused: false, views: { s: { text: '保留草稿' } } }; host.publish = () => {};
  host.rejectCommand({ type: 'send', submissionId: 'q', sessionId: 's' }, new Error('模型未配置'));
  assert.equal(host.submissionResult.accepted, false); assert.equal(host.state.views.s.text, '保留草稿'); assert.equal(host.state.paused, false);
});

test('STOP during device startup leaves the queued task unstarted', async () => {
  const host = new DesktopHost({ data: '' });
  host.state = { paused: false, queue: [{ id: 'task' }] }; host.publish = () => {};
  host.connected = true; host.hasModel = () => true;
  let connected;
  host.ensureDevice = () => new Promise(resolve => { connected = resolve; });
  const starting = host.runNext();
  await host.stop(); connected(); await starting;
  assert.equal(host.agent, undefined); assert.equal(host.state.queue.length, 1);
  assert.equal(host.control.mode, 'stopped');
});

test('a newer STOP wins even when a previous resume request completes late', async () => {
  const host = new DesktopHost({ data: '' });
  host.state = { paused: true }; host.publish = () => {};
  const calls = []; let resumed;
  host.bridge = { setStopped: async value => { calls.push(value); } };
  host.deviceControl = operation => operation === 'resume' ? new Promise(resolve => { resumed = resolve; }) : Promise.resolve();
  const resume = host.resumeDevice();
  await host.stop(); resumed();
  await assert.rejects(resume, /STOP/);
  assert.equal(calls.at(-1), true); assert.equal(host.state.paused, true);
});

function manualHost(mode = 'idle') {
  const host = new DesktopHost({ data: '' });
  host.state = { selected: 's', paused: true, queue: [{ id: 'queued', sessionId: 'other' }], interrupted: [] };
  host.control = { id: 'control', mode, sessionId: 's', taskId: 'current', canResume: false, canSteer: false };
  host.publish = () => {}; host.runNext = async () => {};
  host.connected = true; host.settings = { defaultModel: { connectionId: 'c', modelId: 'm' },
    connections: [{ id: 'c', baseUrl: 'https://example.test/v1', apiKey: 'fixture', models: [{ id: 'm' }] }] };
  const calls = [];
  host.bridge = { settled: async () => { calls.push('settled'); }, setStopped: async value => { calls.push(['stopped', value]); } };
  host.deviceControl = async (operation, params) => { calls.push([operation, params]); return { token: 'manual-token' }; };
  return { host, calls };
}

test('idle, stopped and error states permit explicit manual control without resuming the queue', async () => {
  for (const mode of ['idle', 'stopped', 'error']) {
    const { host, calls } = manualHost(mode);
    await host.takeover('control');
    assert.equal(host.control.mode, 'manual'); assert.equal(host.control.canResume, false);
    assert.equal(host.state.paused, true); assert.equal(host.state.queue.length, 1);
    assert.deepEqual(calls, [['hold', undefined], 'settled', ['manual', { resumeStopped: true }]]);
    await host.command({ type: 'endManual', controlId: host.control.id });
    assert.equal(host.control.mode, 'stopped'); assert.equal(host.control.canResume, false);
    assert.equal(host.manualToken, null); assert.equal(host.state.paused, true);
    assert.equal(host.state.queue.length, 1);
  }
});

test('continuation belongs to the interrupted submission, not another task in the same session', async () => {
  const { host } = manualHost();
  host.state.interrupted.push({ id: 'older', sessionId: 's', interruptedReason: 'running' },
    { id: 'current', deviceId: 'android:', sessionId: 's', interruptedReason: 'queued' });
  await host.stop(); assert.equal(host.control.canResume, false);
  host.state.interrupted[1].interruptedReason = 'running';
  await host.takeover(host.control.id); assert.equal(host.control.canResume, true);
});

test('resuming without an interrupted task rejects without releasing STOP or the queue', async () => {
  const { host, calls } = manualHost('stopped');
  await assert.rejects(host.command({ type: 'resumeTask', controlId: 'control' }), /没有可继续/);
  assert.deepEqual(calls, []); assert.equal(host.state.paused, true); assert.equal(host.state.queue.length, 1);
});

test('explicit continuation keeps the bound task configuration and requires reobservation', async () => {
  const { host } = manualHost('stopped');
  host.state.interrupted.push({ id: 'current', deviceId: 'android:', sessionId: 's', text: '完成目标', configRevision: 'config-v1', interruptedReason: 'running' });
  await host.command({ type: 'resumeTask', controlId: 'control' });
  assert.equal(host.state.queue[0].configRevision, 'config-v1');
  assert.match(host.state.queue[0].text, /先重新观察.*不要重放不确定输入/);
  assert.equal(host.state.interrupted.length, 0); assert.equal(host.state.paused, false);
  assert.equal(host.control.canResume, false);
});

test('STOP during manual grant revokes the late token and reasserts the device STOP', async () => {
  const { host, calls } = manualHost('stopped');
  let grant;
  host.deviceControl = async (operation, params) => {
    calls.push([operation, params]);
    return operation === 'manual' ? new Promise(resolve => { grant = resolve; }) : {};
  };
  const takeover = host.takeover('control');
  await new Promise(resolve => setImmediate(resolve));
  await host.stop(); grant({ token: 'late-token' });
  await assert.rejects(takeover, /STOP/);
  assert.equal(host.manualToken, null); assert.equal(host.control.mode, 'stopped');
  assert.deepEqual(calls.slice(-2), [['stopped', true], ['stop', undefined]]);
});

test('STOP during takeover device startup prevents requesting a manual token', async () => {
  const { host, calls } = manualHost();
  let held;
  host.deviceControl = async operation => {
    calls.push(operation);
    return operation === 'hold' ? new Promise(resolve => { held = resolve; }) : {};
  };
  const takeover = host.takeover('control');
  await host.stop(); held();
  await assert.rejects(takeover, /STOP/);
  assert.ok(!calls.includes('manual')); assert.equal(host.control.mode, 'stopped');
});

test('failed manual grants remain paused and can be retried with a current control ID', async () => {
  const { host } = manualHost('stopped');
  host.deviceControl = async operation => { if (operation === 'manual') throw new Error('input uncertain'); return {}; };
  await assert.rejects(host.takeover('control'), /input uncertain/);
  assert.equal(host.control.mode, 'error'); assert.equal(host.state.paused, true); assert.equal(host.manualToken, null);
  await assert.rejects(host.takeover('control'), /控制权已变化/);
  await assert.rejects(host.command({ type: 'endManual', controlId: host.control.id }), /控制权已过期/);
});

test('snapshot no longer offers continuation after the interrupted task is deleted', () => {
  const { host } = manualHost('stopped');
  Object.assign(host.state, { questions: [], views: {}, selections: {} });
  host.sessions = []; host.settings = { connections: [], tools: {} };
  host.state.interrupted.push({ id: 'current', deviceId: 'android:', sessionId: 's', interruptedReason: 'running' });
  host.control.canResume = true;
  assert.equal(host.snapshot().control.canResume, true);
  host.state.interrupted = [];
  assert.equal(host.snapshot().control.canResume, false);
});

test('deleting an interrupted task during resume never restores it to the queue', async () => {
  const { host } = manualHost('stopped');
  host.state.interrupted.push({ id: 'current', deviceId: 'android:', sessionId: 's', interruptedReason: 'running' });
  let resumed;
  host.resumeDevice = () => new Promise(resolve => { resumed = resolve; });
  const continuing = host.command({ type: 'resumeTask', controlId: 'control' });
  await new Promise(resolve => setImmediate(resolve));
  host.state.interrupted = []; resumed();
  await assert.rejects(continuing, /该中断任务已处理/);
  assert.deepEqual(host.state.queue.map(task => task.id), ['queued']);
  assert.equal(host.state.paused, true); assert.equal(host.control.mode, 'stopped');
});
