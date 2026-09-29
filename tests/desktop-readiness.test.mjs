import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { DesktopHost } from '../desktop/host.mjs';
import { restoreState, bindSubmission, deviceIdentity, atomicJson } from '../desktop/state.mjs';
import { discoverDevices } from '../desktop/devices.mjs';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { createServer } from 'node:http';

test('iPhone cleanup receives authenticated release before closing MCP stdin and never acknowledges a missing receipt', async t => {
  const host = fixture(t); host.settings.devicePlatform = 'ios'; host.hostToken = 'fixture-cleanup-token';
  host.requestDevice = DesktopHost.prototype.requestDevice.bind(host);
  const events = []; let confirm, reply = { closed: true };
  const server = createServer(async (request, response) => {
    assert.equal(request.headers.authorization, 'Bearer fixture-cleanup-token');
    let text = ''; for await (const chunk of request) text += chunk;
    assert.deepEqual(JSON.parse(text), { operation: 'shutdown', params: {} }); events.push('shutdown');
    await new Promise(resolve => { confirm = resolve; });
    response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(reply));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  host.hostPort = server.address().port; host.bridge.close = async () => { events.push('mcp-close'); };
  const closing = host.closeExecutor();
  while (!confirm) await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(events, ['shutdown']); confirm(); await closing;
  assert.deepEqual(events, ['shutdown', 'mcp-close']); assert.equal(host.executorReleased, true);
  host.executorReleased = false; reply = {}; confirm = null;
  const failed = host.closeExecutor(); const rejected = assert.rejects(failed, /退出清理未确认/);
  while (!confirm) await new Promise(resolve => setImmediate(resolve));
  confirm(); await rejected;
  assert.equal(host.executorReleased, false); assert.equal(events.at(-1), 'mcp-close');
});

test('locked device status retains its reason after three paused reconnect attempts', async t => {
  const host = fixture(t); host.settings.devicePlatform = 'ios';
  host.waitForReconnect = async () => {};
  let attempts = 0;
  host.reconnectDevice = async () => { attempts++; throw new Error('请解锁 iPhone 后重新连接'); };
  host.requestDevice = async operation => operation === 'status'
    ? { connected: false, connectionStatus: { code: 'device_locked', message: '请解锁 iPhone 后重新连接' } }
    : operation === 'shutdown' ? { closed: true } : {};
  await host.checkDeviceConnection();
  assert.equal(host.connected, false); assert.equal(host.connectionStatus.code, 'connection_failed');
  assert.match(host.connectionStatus.message, /解锁/); assert.equal(attempts, 3);
  assert.equal(host.state.paused, true); assert.equal(host.control.mode, 'stopped');
});

test('device identity defaults legacy configs to Android and normalizes only iPhone serials', () => {
  assert.equal(deviceIdentity({ serial: 'Ab-c' }), 'android:Ab-c');
  assert.equal(deviceIdentity({ serial: 'Ab-c', devicePlatform: 'ios' }), 'ios:ABC');
});

test('new submissions bind device identity and mismatched continuations never release STOP', async t => {
  const host = fixture(t);
  host.settings.devicePlatform = 'ios'; host.settings.serial = 'AB-CD';
  const task = bindSubmission(host.state, host.settings, { submissionId: 'task', sessionId: 's', text: '目标' });
  assert.equal(task.deviceId, 'ios:ABCD');
  host.settings.serial = 'different'; host.state.interrupted = [task];
  host.resumeDevice = () => { throw new Error('must not resume'); };
  await assert.rejects(host.command({ type: 'resumeInterrupted', submissionId: 'task' }), /另一台手机/);
  assert.equal(host.state.interrupted[0], task);
});

test('legacy interrupted tasks resolve device identity from their original configuration', async t => {
  const host = fixture(t); host.data = await mkdtemp(path.resolve('runs/desktop-ios-'));
  host.platform.unseal = value => value;
  await atomicJson(path.join(host.data, 'configurations', 'legacy.json'), { value: { serial: 'fixture' } });
  await host.requireTaskDevice({ configRevision: 'legacy' });
  host.settings.devicePlatform = 'ios';
  await assert.rejects(host.requireTaskDevice({ configRevision: 'legacy' }), /另一台手机/);
});

test('same serial on another platform cannot replace active or queued device', async t => {
  const host = fixture(t); host.bridge = null;
  for (const active of [true, false]) {
    host.state.active = active ? { id: 'a' } : null; host.state.queue = active ? [] : [{ id: 'q' }];
    await assert.rejects(host.saveSettings({ ...host.settings, devicePlatform: 'ios' }), /修改设备/);
  }
});

test('iPhone connection publishes preparation status and adopts backend capabilities without resuming', async t => {
  const host = fixture(t); host.settings.devicePlatform = 'ios'; host.connected = false;
  const codes = []; host.publish = () => codes.push(host.connectionStatus.code);
  const capabilities = { devicePlatform: 'ios', phoneOperations: ['查看'], phoneKeys: ['主页'], systemOperations: { clipboard: ['read'] }, previewMode: 'screenshots', previewFps: 15 };
  const operations = [];
  host.bridge.call = async operation => { operations.push(operation); return { details: {} }; };
  let calls = 0;
  host.requestDevice = async operation => operation === 'status' ? (++calls === 1
    ? { connected: false, connectionStatus: { code: 'preparing_support', message: '准备支持文件' } }
    : { connected: true, stopped: true, capabilities }) : [];
  await host.finishDeviceConnection();
  assert.ok(codes.includes('preparing_support')); assert.equal(host.snapshot().capabilities.previewFps, 15);
  assert.equal(host.control.mode, 'stopped'); assert.equal(host.state.paused, true);
  assert.equal(host.snapshot().desktop.connectionStatus.code, 'ready');
  assert.deepEqual(operations, ['查看', '列出屏幕']);
});

test('USB discovery preserves Android devices and returns Apple component diagnostics independently', async () => {
  const platform = { resource: key => key, spawn(file, args, options) {
    assert.equal(options.stdio[0], 'ignore'); assert.ok(!options.shell);
    const child = new EventEmitter(); child.stdout = new PassThrough(); child.stderr = new PassThrough(); child.kill = () => {};
    queueMicrotask(() => {
      if (file === 'adb') { child.stdout.write(args[0] === 'devices' ? 'List of devices attached\nphone1 device model:Android\n' : 'Android'); child.emit('close', 0); }
      else { assert.deepEqual(args, ['-X', 'utf8', '-m', 'bbui.ios_discovery']); child.stderr.write(JSON.stringify({ error: '需要 Apple 支持组件', code: 'driver_missing' })); child.emit('close', 1); }
    }); return child;
  } };
  const result = await discoverDevices(platform);
  assert.equal(result.devices[0].deviceId, 'android:phone1');
  assert.equal(result.errors[0].devicePlatform, 'ios'); assert.match(result.errors[0].message, /Apple/);
  assert.equal(result.errors[0].code, 'driver_missing');
});

test('filtered discovery only runs selected backend and Android friendly names fall back without losing rows', async () => {
  const commands = [];
  const platform = { resource: key => key, spawn(file, args) {
    commands.push([file, args]);
    const child = new EventEmitter(); child.stdout = new PassThrough(); child.stderr = new PassThrough(); child.kill = () => {};
    queueMicrotask(() => {
      if (file === 'python') child.stdout.write(JSON.stringify([{ serial: 'iphone-id', devicePlatform: 'ios', label: '我的 iPhone' }]));
      else if (args[0] === 'devices') child.stdout.write('named device model:Fallback\nmodel device model:Old_Model\nblocked unauthorized model:Pixel_9\nunknown unauthorized\n');
      else if (args[1] === 'named') child.stdout.write('我的 vivo\n');
      else child.stdout.write(args.includes('getprop') ? 'Pixel 8\n' : 'null\n');
      child.emit('close', 0);
    }); return child;
  } };
  const android = await discoverDevices(platform, { devicePlatform: 'android' });
  assert.deepEqual(android.devices.map(row => row.label), ['我的 vivo', 'Pixel 8', 'Pixel 9', 'Android · unknown']);
  assert.ok(commands.every(([file]) => file === 'adb'));
  assert.ok(commands.filter(([, args]) => args[0] === '-s').every(([, args]) => ['named', 'model'].includes(args[1])));
  commands.length = 0;
  const ios = await discoverDevices(platform, { devicePlatform: 'ios' });
  assert.equal(ios.devices[0].label, '我的 iPhone'); assert.equal(commands.length, 1); assert.equal(commands[0][0], 'python');
  await assert.rejects(discoverDevices(platform, { devicePlatform: 'other' }), /不支持/);
});

test('connect persists only its target merged with latest settings and exposes the friendly name', async t => {
  const host = fixture(t); host.data = await mkdtemp(path.resolve('runs/desktop-connect-'));
  host.connected = false; host.bridge = null; host.settings.floatingStop = true;
  host.settings.blocked_packages = ['com.example.blocked']; host.settings.tools.search = { provider: 'bocha', apiKey: 'search-secret' };
  const updated = structuredClone(host.settings); updated.connections[0].apiKey = 'latest-secret';
  updated.connections[0].name = 'latest model'; updated.floatingStop = false;
  const saved = host.saveSettings(updated);
  let started = false;
  host.ensureDevice = async () => { started = true; assert.equal(host.settings.deviceName, '我的 iPhone'); };
  const connecting = host.connect({ devicePlatform: 'ios', serial: 'AB-CD', label: '我的 iPhone', connections: [], floatingStop: true });
  await Promise.all([saved, connecting]);
  assert.equal(started, true); assert.equal(host.settings.connections[0].apiKey, 'latest-secret');
  assert.equal(host.settings.connections[0].name, 'latest model'); assert.equal(host.settings.floatingStop, false);
  assert.equal(host.settings.tools.search.apiKey, 'search-secret'); assert.deepEqual(host.settings.blocked_packages, ['com.example.blocked']);
  assert.equal(host.snapshot().desktop.deviceName, '我的 iPhone'); assert.equal(host.snapshot().desktop.deviceId, 'ios:ABCD');
});

test('duplicate friendly labels use serial suffixes without changing device identity', async () => {
  const platform = { resource: key => key, spawn(file) {
    assert.equal(file, 'python');
    const child = new EventEmitter(); child.stdout = new PassThrough(); child.stderr = new PassThrough(); child.kill = () => {};
    queueMicrotask(() => {
      child.stdout.write(JSON.stringify(['first-11111111', 'second-22222222'].map(serial => ({ devicePlatform: 'ios', serial, label: '我的 iPhone' }))));
      child.emit('close', 0);
    }); return child;
  } };
  const { devices } = await discoverDevices(platform, { devicePlatform: 'ios' });
  assert.deepEqual(devices.map(device => device.label), ['我的 iPhone · 11111111', '我的 iPhone · 22222222']);
  assert.deepEqual(devices.map(device => device.deviceId), ['ios:FIRST11111111', 'ios:SECOND22222222']);
});

test('connect rejects selection during connection or bound work; empty params retain the legacy path', async t => {
  const host = fixture(t), target = { devicePlatform: 'ios', serial: 'new', label: 'iPhone' };
  await assert.rejects(host.connect(target), /先断开/);
  host.connected = false; host.state.queue = [{ id: 'pending' }];
  await assert.rejects(host.connect(target), /清空等待队列/);
  host.state.queue = []; host.state.active = { id: 'active' };
  await assert.rejects(host.connect(target), /停止任务/);
  host.state.active = null; let calls = 0; host.ensureDevice = async () => { calls++; };
  await host.connect(); await host.connect({}); assert.equal(calls, 2);
});

test('switching after failed executor cleanup waits for confirmed exit and preserves settings on failure', async t => {
  const host = fixture(t); host.connected = false; host.executorCleanupFailed = true;
  const original = host.settings.serial; let closed = false;
  host.closeExecutor = async () => { throw new Error('old process still running'); };
  await assert.rejects(host.connect({ devicePlatform: 'ios', serial: 'new' }), /old process/);
  assert.equal(host.settings.serial, original); assert.equal(host.deviceSelecting, false);
  host.data = await mkdtemp(path.resolve('runs/desktop-connect-'));
  host.closeExecutor = async () => { closed = true; };
  host.ensureDevice = async () => { assert.equal(closed, true); assert.equal(host.bridge, null); };
  await host.connect({ devicePlatform: 'ios', serial: 'new', label: '测试手机' });
  assert.equal(host.settings.serial, 'new'); assert.equal(host.executorCleanupFailed, false);
});

function fixture(t) {
  const host = new DesktopHost({ data: '', seal: async value => JSON.stringify(value) });
  host.state = restoreState({ selected: 's' }); host.sessions = [];
  host.settings = { serial: 'fixture', blocked_packages: [], tools: { search: {} },
    defaultModel: { connectionId: 'c', modelId: 'm' },
    connections: [{ id: 'c', baseUrl: 'https://example.test/v1', apiKey: 'fixture', models: [{ id: 'm' }] }] };
  host.modelCatalog = []; host.publish = () => {}; host.save = async () => {};
  host.catalog = { resolve: async () => {} }; host.connected = true; host.hostPort = 1;
  host.bridge = { close: async () => {}, settled: async () => {}, setStopped: async () => {},
    call: async () => ({ details: {} }) };
  host.requestDevice = async operation => operation === 'status' ? { connected: true, stopped: true } : {};
  host.waitForReconnect = async () => {};
  t.after(() => clearTimeout(host.deviceCheckTimer));
  return host;
}

test('configured requires an existing default connection and model with nonblank endpoint and key', t => {
  const host = fixture(t), settings = structuredClone(host.settings);
  assert.equal(host.snapshot().desktop.configured, true);
  const edits = [value => { value.defaultModel = null; }, value => { value.defaultModel.connectionId = 'deleted'; },
    value => { value.defaultModel.modelId = 'deleted'; }, value => { value.connections[0].baseUrl = ' '; },
    value => { value.connections[0].apiKey = '\t'; }, value => { value.connections = []; }];
  for (const edit of edits) {
    host.settings = structuredClone(settings); edit(host.settings);
    assert.equal(host.snapshot().desktop.configured, false);
  }
});

test('host rejects submissions while setup is missing and preserves drafts and bound queue entries', async t => {
  const host = fixture(t);
  host.state.views.s = { text: '保留草稿', scrollTop: 12 };
  host.state.queue = [{ id: 'waiting', configRevision: 'original' }];
  host.connected = false;
  await assert.rejects(host.command({ type: 'send', sessionId: 's', submissionId: 'new', text: '任务' }), /连接手机/);
  host.connected = true; host.settings.connections[0].apiKey = '';
  await assert.rejects(host.command({ type: 'send', sessionId: 's', submissionId: 'new', text: '任务' }), /配置默认模型/);
  assert.deepEqual(host.state.queue, [{ id: 'waiting', configRevision: 'original' }]);
  assert.equal(host.state.views.s.text, '保留草稿');
});

test('removing the default configuration pauses queue advancement without replacing an active task', async t => {
  const host = fixture(t);
  host.data = await mkdtemp(path.resolve('runs/desktop-readiness-'));
  const settings = structuredClone(host.settings), active = { id: 'active', configRevision: 'bound' };
  host.state.active = active; host.state.paused = false;
  host.state.queue = [{ id: 'queued', configRevision: 'bound' }];
  await host.saveSettings({ ...settings, defaultModel: null });
  assert.equal(host.state.paused, true); assert.equal(host.state.active, active);
  assert.equal(host.state.queue[0].configRevision, 'bound');
  await host.saveSettings(settings);
  assert.equal(host.hasModel(), true); assert.equal(host.state.paused, true);
});

test('physical disconnect publishes offline and STOP before old executor cleanup; reconnect waits and never resumes work', async t => {
  const host = fixture(t), events = [];
  const directory = await mkdtemp(path.resolve('runs/desktop-readiness-'));
  host.endpointFile = path.join(directory, 'endpoint.txt'); await writeFile(host.endpointFile, '2');
  host.state.paused = false; host.state.queue = [{ id: 'queued', configRevision: 'bound' }];
  let closed, online = false;
  host.bridge.close = () => new Promise(resolve => { closed = resolve; });
  host.bridge.recover = async () => { events.push('recover'); };
  host.bridge.call = async operation => {
    assert.notEqual(operation, '连接手机', 'Recovery preserves STOP and must use the main screen it already observed');
    return { details: { 屏幕会话列表: [{ 屏幕会话: 'main', 显示屏编号: 0 }] } };
  };
  host.bridge.setStopped = async stopped => events.push(stopped ? 'stop' : 'resume');
  host.publish = () => events.push(host.snapshot().desktop.connected ? 'online' : 'offline');
  host.requestDevice = async operation => operation === 'status' ? { connected: online, stopped: true } : {};
  const checking = host.checkDeviceConnection();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(host.connected, false); assert.equal(host.state.paused, true);
  assert.ok(events.includes('offline')); assert.ok(events.includes('stop')); assert.ok(!events.includes('recover'));
  online = true;
  const reconnecting = host.ensureDevice();
  await new Promise(resolve => setImmediate(resolve));
  assert.ok(!events.includes('recover'));
  closed(); await checking; await reconnecting;
  assert.equal(host.connected, true); assert.equal(host.state.paused, true);
  assert.deepEqual(host.state.queue, [{ id: 'queued', configRevision: 'bound' }]);
  assert.equal(events.filter(event => event === 'recover').length, 1); assert.ok(!events.includes('resume'));
});

test('executor transport loss returns offline, while a failed video observation alone does not', async t => {
  const host = fixture(t);
  host.requestDevice = async operation => {
    if (operation === 'frame') throw new Error('video decoding failed');
    return { connected: true, stopped: false };
  };
  await assert.rejects(host.deviceControl('frame', { screen: 'main' }), /video decoding/);
  await host.checkDeviceConnection(); assert.equal(host.connected, true);
  host.requestDevice = async () => { throw new Error('executor unavailable'); };
  await host.checkDeviceConnection();
  assert.equal(host.connected, false); assert.equal(host.state.paused, true);
  await assert.rejects(host.deviceControl('frame', { screen: 'main' }), /连接手机/);
});

test('shutdown retries executor cleanup after a failed disconnect', async t => {
  const host = fixture(t), closed = [];
  host.deviceDisconnecting = Promise.reject(new Error('previous cleanup failed'));
  host.bridge.close = async () => { closed.push('device'); };
  host.memoryWorker = { close: async () => { closed.push('memory'); } };
  host.server = { closeAllConnections: () => {}, close: callback => { closed.push('server'); callback(); } };
  await host.close();
  assert.deepEqual(closed, ['memory', 'device', 'server']);
});

test('external preview sources supplement owned screens without becoming model-owned sessions', async t => {
  const host = fixture(t);
  host.screens = [{ 屏幕会话: 'main', 显示屏编号: 0 }];
  const external = [{ 屏幕会话: 'external:152:identity', 显示屏编号: 152, external: true, readOnly: true, label: '外部屏幕 152' }];
  host.requestDevice = async operation => operation === 'previewSources' ? external : { connected: true };
  await host.checkDeviceConnection();
  assert.deepEqual(host.snapshot().desktop.screens, [...host.screens, ...external]);
  assert.deepEqual(host.screens.map(screen => screen.屏幕会话), ['main']);
  host.requestDevice = async operation => {
    if (operation === 'previewSources') throw new Error('display enumeration timeout');
    return { connected: true };
  };
  host.state.paused = false;
  await host.checkDeviceConnection();
  assert.equal(host.connected, true); assert.equal(host.state.paused, false);
  assert.deepEqual(host.externalScreens, external);
  assert.equal(host.externalDiscoveryError, 'display enumeration timeout');
  host.requestDevice = async operation => operation === 'previewSources' ? [] : { connected: true };
  await host.checkDeviceConnection();
  assert.deepEqual(host.externalScreens, []); assert.equal(host.externalDiscoveryError, null);
});

test('an external discovery response arriving after disconnect cannot restore stale sources', async t => {
  const host = fixture(t);
  let complete;
  host.requestDevice = async () => new Promise(resolve => { complete = resolve; });
  const discovering = host.refreshExternalScreens();
  host.connected = false;
  complete([{ 屏幕会话: 'external:152:old', external: true }]);
  await discovering;
  assert.deepEqual(host.externalScreens, []);
});

test('automatic reconnect is single flight, retries the same device, and never replays queued work', async t => {
  for (const devicePlatform of ['android', 'ios']) {
    const host = fixture(t), events = [], snapshots = [];
    host.settings.devicePlatform = devicePlatform;
    host.state.queue = [{ id: 'queued', deviceId: deviceIdentity(host.settings), configRevision: 'saved' }];
    host.state.views.s = { text: '未发送草稿' }; host.state.paused = false;
    host.publish = () => snapshots.push(structuredClone(host.snapshot().desktop));
    host.bridge.setStopped = async value => { events.push(['stop', value]); };
    host.closeExecutor = async () => { events.push(['close']); };
    let attempts = 0;
    host.reconnectDevice = async () => {
      attempts++; events.push(['recover', deviceIdentity(host.settings)]);
      assert.equal(host.state.paused, true);
      if (attempts < 3) throw new Error('USB 尚未返回');
      host.connected = true; host.connectionLost = false;
    };
    const first = host.reconnectAfterFailure(new Error('USB disconnected'));
    assert.equal(host.reconnectAfterFailure(new Error('duplicate')), first);
    await first;
    assert.equal(attempts, 3); assert.equal(host.connectionStatus.code, 'ready');
    assert.equal(host.connected, true); assert.equal(host.state.paused, true);
    assert.equal(host.state.queue[0].configRevision, 'saved'); assert.equal(host.state.views.s.text, '未发送草稿');
    assert.equal(host.control.mode, 'stopped'); assert.equal(host.agent, undefined);
    assert.deepEqual(events.filter(row => row[0] === 'stop'), [['stop', true]]);
    assert.ok(snapshots.filter(row => row.connectionStatus.code !== 'ready').every(row => row.connectionStatus.code === 'reconnecting' && !row.connected));
  }
});

test('automatic reconnect stops after three failures with the original queue retained', async t => {
  const host = fixture(t), codes = [];
  host.state.queue = [{ id: 'queued' }];
  host.publish = () => codes.push(host.connectionStatus.code);
  let attempts = 0;
  host.reconnectDevice = async () => { attempts++; throw new Error('USB disconnected'); };
  await host.reconnectAfterFailure(new Error('USB disconnected'));
  assert.equal(attempts, 3); assert.equal(host.connected, false);
  assert.equal(host.connectionStatus.code, 'connection_failed'); assert.equal(host.state.queue.length, 1);
  assert.equal(host.state.paused, true); assert.equal(host.agent, undefined);
  assert.ok(codes.slice(0, -1).every(code => code === 'reconnecting'));
});

test('user STOP cancels reconnect and releases a late successful connection without another attempt', async t => {
  const host = fixture(t); let complete, attempts = 0, closes = 0;
  host.closeExecutor = async () => { closes++; };
  host.reconnectDevice = async () => { attempts++; await new Promise(resolve => { complete = resolve; }); host.connected = true; };
  const reconnecting = host.reconnectAfterFailure(new Error('USB disconnected'));
  while (!complete) await new Promise(resolve => setImmediate(resolve));
  await host.stop(); complete(); await reconnecting;
  assert.equal(attempts, 1); assert.equal(closes, 2);
  assert.equal(host.connected, false); assert.equal(host.connectionStatus.code, 'disconnected');
  assert.equal(host.state.paused, true);
});

test('connection cleanup failure blocks all respawns and preserves the old executor handle', async t => {
  const host = fixture(t), bridge = host.bridge;
  host.closeExecutor = async () => { throw new Error('lease still owned'); };
  host.reconnectDevice = async () => { assert.fail('must not bypass cleanup'); };
  await host.reconnectAfterFailure(new Error('USB disconnected'));
  assert.equal(host.bridge, bridge); assert.equal(host.executorCleanupFailed, true);
  assert.equal(host.connectionStatus.code, 'connection_failed'); assert.match(host.connectionStatus.message, /lease/);
});

test('dead iPhone executor cleanup accepts only confirmed process exit plus released lease', async t => {
  const host = fixture(t); host.settings.devicePlatform = 'ios';
  host.requestDevice = async () => { throw new Error('connection refused'); };
  const events = [];
  host.bridge.close = async () => { events.push('exit'); };
  host.bridge.confirmReleased = async () => { events.push('lease'); };
  await host.closeExecutor();
  assert.deepEqual(events, ['exit', 'lease']); assert.equal(host.executorReleased, true);
  host.executorReleased = false;
  host.bridge.confirmReleased = async () => { throw new Error('lease owned'); };
  await assert.rejects(host.closeExecutor(), /lease owned/);
  assert.equal(host.executorReleased, false);
});

test('control channel failures recover; business rejection and frame failure never reconnect', async t => {
  const host = fixture(t), failures = [];
  host.reconnectAfterFailure = async error => { failures.push(error.message); };
  for (const [operation, error] of [
    ['manual', new Error('invalid token')],
    ['frame', Object.assign(new Error('screen timeout'), { channel: 'observation' })],
    ['manual', Object.assign(new Error('control service closed'), { channel: 'control' })],
  ]) {
    host.requestDevice = async () => { throw error; };
    await assert.rejects(host.deviceControl(operation), error);
  }
  const receipt = { details: { 执行: { 状态: '已派发' }, 观察: { 状态: '失败' }, 通道: { 观察: '失败' } } };
  assert.equal(host.checkToolConnection(receipt), receipt);
  const disconnected = { details: { 执行: { 状态: '未知' }, 通道: { 控制: '断开' }, 错误: 'MCP closed' } };
  assert.equal(host.checkToolConnection(disconnected), disconnected);
  host.requestDevice = async () => disconnected.details;
  assert.equal(await host.deviceControl('input'), disconnected.details);
  assert.deepEqual(failures, ['control service closed', 'MCP closed', 'MCP closed']);
});

test('queued work allows explicit reconnect of its device but rejects a different device', async t => {
  const host = fixture(t); host.connected = false; host.state.queue = [{ id: 'q', deviceId: 'android:fixture' }];
  host.updateSettings = async create => { host.settings = create(); };
  let connected = 0; host.ensureDevice = async () => { connected++; };
  await host.connect({ devicePlatform: 'android', serial: 'fixture', label: '原手机' });
  assert.equal(connected, 1);
  await assert.rejects(host.connect({ devicePlatform: 'ios', serial: 'other' }), /清空等待队列/);
  assert.equal(connected, 1); assert.equal(host.settings.serial, 'fixture');
});

test('control loss aborts the active model immediately without waiting for dead device cleanup', async t => {
  const host = fixture(t); let release, aborted = false;
  host.state.active = { id: 'active', sessionId: 's' }; host.control = { mode: 'running', sessionId: 's' };
  host.finished = Promise.resolve();
  host.agent = { request: async ({ type }) => { assert.equal(type, 'abort'); aborted = true; host.agent = null; } };
  host.closeExecutor = () => new Promise(resolve => { release = resolve; });
  host.deviceControl = async () => { assert.fail('must not wait for unavailable control RPC'); };
  const reconnecting = host.reconnectAfterFailure(new Error('control disconnected'));
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(aborted, true); assert.equal(host.state.paused, true);
  host.cancelRecovery(); release(); await reconnecting;
});

test('closing cancels a reconnect delay and does not launch another executor', async t => {
  const host = fixture(t); let waiting, attempts = 0;
  host.waitForReconnect = signal => new Promise(resolve => {
    waiting = true; signal.addEventListener('abort', resolve, { once: true });
  });
  host.reconnectDevice = async () => { attempts++; throw new Error('offline'); };
  host.server = { closeAllConnections() {}, close: callback => callback() };
  const reconnecting = host.reconnectAfterFailure(new Error('offline'));
  while (!waiting) await new Promise(resolve => setImmediate(resolve));
  await host.close(); await reconnecting;
  assert.equal(attempts, 1); assert.equal(host.connected, false); assert.equal(host.recovery, null);
});

test('Pi tool cancellation caused by recovery does not cancel reconnect, but explicit STOP does', async t => {
  const host = fixture(t);
  host.state.active = { id: 'task', sessionId: 's' };
  host.agentToken = 'recovery-test-token';
  host.control.mode = 'stopped';
  host.recovery = { controller: new AbortController() };
  const server = createServer((request, response) => { void host.handleAgent(request, response); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const request = suffix => fetch(`http://127.0.0.1:${server.address().port}${suffix}`, {
    method: 'POST', headers: { Authorization: 'Bearer recovery-test-token', 'Content-Type': 'application/json' }, body: '{}',
  });
  const cancelled = await request('/cancel');
  assert.equal(cancelled.status, 200); assert.deepEqual(await cancelled.json(), { stopped: true });
  assert.equal(host.recovery.controller.signal.aborted, false);
  const stopped = await request('/stop');
  assert.equal(stopped.status, 200); await stopped.json();
  assert.equal(host.recovery.controller.signal.aborted, true);
});
