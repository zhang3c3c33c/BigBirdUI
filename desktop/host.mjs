import { EventEmitter } from 'node:events';
import { createServer } from 'node:http';
import { randomUUID, randomBytes, timingSafeEqual, createHash } from 'node:crypto';
import { mkdir, readFile, copyFile, unlink } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { PhoneBridge } from '../pi/bridge.ts';
import { PiProcess } from './rpc.mjs';
import { atomicJson, readJson, restoreState, bindSubmission, validateAnswer, deviceIdentity } from './state.mjs';
import { projectMessages } from './projection.mjs';

const reads = { apps: ['list', 'details', 'launch_entries', 'permissions'], notifications: ['list', 'details'], clipboard: ['read'], files: ['list', 'stat', 'search', 'read_text'] };
const defaults = { version: 1, revision: '', serial: '', devicePlatform: 'android', blocked_packages: [], connections: [], tools: { search: { provider: '' } }, defaultModel: null, floatingStop: false };

export class DesktopHost extends EventEmitter {
  constructor(platform) {
    super(); this.platform = platform; this.data = platform.data; this.revision = 0; this.stateId = randomUUID();
    this.messages = new Map(); this.pages = new Map(); this.screens = []; this.externalScreens = []; this.control = { id: randomUUID(), mode: 'idle', sessionId: '', canResume: false, canSteer: false };
    this.status = { phase: 'idle', message: '准备开始' }; this.saveChain = Promise.resolve(); this.stopEpoch = 0; this.hostCalls = new Set(); this.connected = false;
    this.pendingSubmissions = new Map(); this.pendingSteering = new Map(); this.draftRevisions = new Map(); this.pauseRevision = 0;
    this.sessionWrites = Promise.resolve(); this.settingsWrites = Promise.resolve(); this.titleJobs = new Set();
    this.connectionStatus = { code: 'disconnected', message: '请连接手机' };
  }
  async initialize() {
    await mkdir(this.data, { recursive: true });
    this.state = restoreState(await readJson(path.join(this.data, 'state.json'), {}));
    const sealed = await readJson(path.join(this.data, 'settings.json'), null);
    this.settings = sealed ? this.platform.unseal(sealed.value) : structuredClone(defaults);
    this.settings.devicePlatform ||= 'android';
    this.modelCatalog = await readJson(path.join(this.platform.resource('pi'), 'model-catalog.json'), []);
    this.home = path.join(this.data, 'agent'); await mkdir(this.home, { recursive: true });
    const { SessionCatalog } = await import(pathToFileURL(path.join(this.platform.resource('pi'), 'sessions.mjs')).href);
    this.catalog = new SessionCatalog(path.join(this.home, 'sessions'), path.join(this.home, 'workspace'));
    await this.refreshSessions();
    if (!this.sessions.some(item => item.id === this.state.selected)) this.state.selected = this.sessions[0]?.id || await this.createSession();
    await this.loadHistory(this.state.selected); await this.refreshSessions();
    this.server = createServer((request, response) => { void this.handleAgent(request, response); });
    await new Promise(resolve => this.server.listen(0, '127.0.0.1', resolve));
    this.bridgeUrl = `http://127.0.0.1:${this.server.address().port}`;
    this.publish();
  }
  async refreshSessions() { this.sessions = (await this.catalog.execute({ action: 'list' })).sessions; }
  async loadHistory(id) { this.messages.set(id, (await this.catalog.execute({ action: 'history', sessionId: id })).messages); }
  async createSession() {
    const { sessionId } = await this.catalog.execute({ action: 'create' });
    this.state.titleGeneration[sessionId] = { status: 'eligible' };
    return sessionId;
  }
  async mutateSession(operation) {
    // Title writes use the live Pi manager, or the catalog only between runs.
    // Lifecycle transitions wait for existing writes and exclude new ones.
    for (;;) {
      while (this.starting || this.finishing) await new Promise(resolve => this.once('sessionWritable', resolve));
      const write = this.sessionWrites.then(async () => {
        if (this.starting || this.finishing) return false;
        if (!this.closing) await operation();
        return true;
      });
      this.sessionWrites = write.catch(() => {});
      if (await write) return;
    }
  }
  async writeSessionName(id, name) {
    if (id === this.state.active?.sessionId && this.agent) await this.agent.request({ type: 'set_session_name', name });
    else await this.catalog.execute({ action: 'rename', sessionId: id, title: name });
    await this.refreshSessions();
  }
  async requestTitle(text, config) {
    if (this.closing) return null;
    if (!this.titleWorker || this.titleWorker.closed) this.titleWorker = new PiProcess(this.platform, { catalogOnly: true }, this.home);
    return (await this.titleWorker.request({ type: 'bbui_title', text, config }, 30000)).title;
  }
  generateTitle(task, marker, config) {
    const current = () => !this.closing && this.state.titleGeneration[task.sessionId] === marker;
    const job = (async () => {
      if (!current()) return;
      const title = await this.requestTitle(task.text, config);
      if (!title || !current()) return;
      await this.mutateSession(async () => {
        if (!current()) return;
        await this.writeSessionName(task.sessionId, title);
        this.publish();
      });
    })().catch(() => {}); // Optional naming must never pause device work.
    this.titleJobs.add(job); void job.finally(() => this.titleJobs.delete(job));
  }
  save({ includingSubmission, prepare = () => {}, success = () => {}, failure = () => {} } = {}) {
    const write = this.saveChain.then(async () => {
      // Build the next snapshot after the preceding write's commit/rollback.
      prepare();
      const value = structuredClone(this.state);
      const excluded = new Set([...this.pendingSubmissions].filter(([id, pending]) => id !== includingSubmission && !pending.durable).map(([id]) => id));
      value.queue = value.queue.filter(task => !excluded.has(task.id));
      for (const id of excluded) delete value.submissions?.[id];
      for (const id of excluded) {
        const pending = this.pendingSubmissions.get(id);
        if (pending.titleMarker && this.state.titleGeneration[pending.task.sessionId] === pending.titleMarker) value.titleGeneration[pending.task.sessionId] = pending.previousTitle;
      }
      try { await this.writeState(value); success(); }
      catch (error) { failure(error); throw error; }
    });
    this.saveChain = write.catch(() => {});
    return write;
  }
  writeState(value) { return atomicJson(path.join(this.data, 'state.json'), value); }
  pause(reason) {
    this.state.pauseReasons ||= this.state.paused ? ['user'] : [];
    if (!this.state.pauseReasons.includes(reason)) this.state.pauseReasons.push(reason);
    this.state.paused = true; this.pauseRevision++;
  }
  unpause() { this.state.paused = false; this.state.pauseReasons = []; this.pauseRevision++; }
  freshSubmissionPause() {
    const reasons = this.state.pauseReasons || [];
    if (!this.state.paused || !reasons.length || reasons.some(reason => !['stop', 'environment', 'error', 'restart'].includes(reason)) ||
        this.state.queue.length || this.state.active || this.state.interrupted.length || this.question ||
        ['running', 'manual', 'taking_over'].includes(this.control.mode)) return null;
    return { revision: this.pauseRevision, controlId: this.control.id, reasons: [...reasons] };
  }
  validFreshPause(ticket, task) {
    return ticket && !this.closing && this.connected && this.hasModel() && ticket.revision === this.pauseRevision && ticket.controlId === this.control.id &&
      this.state.queue[0] === task && !this.state.active && !this.state.interrupted.length && !this.question;
  }
  publish() {
    this.emit('snapshot', this.snapshot());
    void this.save().catch(error => this.emit('failure', error));
  }
  snapshot() {
    const id = this.state.selected, page = this.pages.get(id) || 40;
    const messages = projectMessages(this.messages.get(id) || [], this.state.active?.sessionId === id);
    const task = [...(this.messages.get(id) || [])].reverse().find(m => m.details?.bbuiTask)?.details.bbuiTask;
    const questions = this.state.questions.filter(q => q.sessionId === id);
    for (const message of messages) for (const part of message.parts) {
      const q = questions.find(q => q.toolCallId === part.toolCallId && q.status === 'pending');
      if (q) part.questionRequestId = q.requestId;
    }
    return { type: 'snapshot', revision: ++this.revision, stateId: this.stateId,
      runId: this.runId || '', sessionId: id, status: this.status, isRunning: Boolean(this.state.active),
      runningSessionId: this.state.active?.sessionId || '', control: { ...this.control,
        canResume: ['manual', 'stopped', 'error'].includes(this.control.mode) && Boolean(this.continuation()),
        canSteer: this.control.mode === 'running' && Boolean(this.state.active) && !this.question },
      messages: messages.slice(-page), hasOlder: messages.length > page,
      timing: { nativeReceivedAtMs: Date.now(), projectionAtMs: Date.now() }, sessionsReady: true,
      sessions: this.sessions.map(s => ({ ...s, running: s.id === this.state.active?.sessionId,
        queued: this.state.queue.filter(q => q.sessionId === s.id).length, state: '' })),
      queue: this.state.queue.map(({ id, sessionId, text }) => ({ id, sessionId, text })), queuePaused: this.state.paused,
      queuePauseReasons: this.state.paused ? ['队列已暂停'] : [], interruptedTasks: this.state.interrupted.filter(t => t.sessionId === id),
      viewState: this.state.views[id] || { text: '', scrollTop: 0 }, modelSelection: this.state.selections[id] || this.settings.defaultModel,
      modelOptions: this.settings.connections.flatMap(c => (c.models || []).map(m => ({
        connectionId: c.id, connectionName: c.name, modelId: m.id, modelName: m.name || m.id,
        thinkingLevels: m.thinkingLevels || [], defaultThinkingLevel: m.defaultThinkingLevel || '',
      }))),
      pendingQuestion: this.question?.record || null, questionHistory: questions,
      submissionResult: this.submissionResult, modelSelectionResult: this.modelSelectionResult, questionResult: this.questionResult,
      sessionError: this.sessionError, task,
      capabilities: { platform: 'windows', primaryModifier: 'Control', embeddedPreview: true, systemTools: true,
        multipleScreens: this.settings.devicePlatform !== 'ios', appRestrictions: this.settings.devicePlatform !== 'ios',
        previewMode: this.settings.devicePlatform === 'ios' ? 'screenshots' : 'scrcpy', previewFps: this.settings.devicePlatform === 'ios' ? 15 : 60,
        ...this.deviceCapabilities, devicePlatform: this.settings.devicePlatform || 'android' },
      desktop: { connected: !this.closing && !this.recovery && this.connected, screens: [...this.screens, ...this.externalScreens], configured: this.hasModel(), serial: this.settings.serial,
        devicePlatform: this.settings.devicePlatform || 'android', deviceId: deviceIdentity(this.settings), deviceName: this.settings.deviceName || '', connectionStatus: this.connectionStatus },
    };
  }
  hasModel() {
    const selected = this.settings.defaultModel;
    const connection = this.settings.connections.find(item => item.id === selected?.connectionId);
    return Boolean(selected?.modelId?.trim() && connection?.models?.some(model => model.id === selected.modelId)
      && connection.baseUrl?.trim() && connection.apiKey?.trim());
  }
  requireReady() {
    if (!this.hasModel()) throw new Error('请先配置默认模型与 API');
    if (!this.connected || this.closing || this.recovery) throw new Error('请先连接手机');
  }
  async requireTaskDevice(task, boundSettings) {
    let expected = task.deviceId;
    if (!expected) {
      const saved = boundSettings || this.platform.unseal((await readJson(path.join(this.data, 'configurations', task.configRevision + '.json'))).value);
      expected = deviceIdentity(saved);
    }
    if (expected !== deviceIdentity(this.settings)) throw new Error('该任务绑定的是另一台手机，请连接原设备后再继续');
  }
  async connect(params = {}) {
    if (this.recovery) throw new Error('正在重连原手机，请稍候');
    if (this.deviceSelecting) throw new Error('正在选择并连接手机，请稍候');
    if (!Object.keys(params).length) return this.ensureDevice();
    if (!['android', 'ios'].includes(params.devicePlatform) || typeof params.serial !== 'string' || !params.serial.trim() || params.serial.length > 200 || /[\r\n\0]/.test(params.serial)) throw new Error('请选择有效手机');
    if (params.label !== undefined && (typeof params.label !== 'string' || params.label.length > 200)) throw new Error('手机名称格式错误');
    if (this.connected) throw new Error('请先断开当前手机，再选择连接');
    if (this.deviceStarting || this.closing) throw new Error('连接状态正在变化，请稍候');
    const target = { devicePlatform: params.devicePlatform, serial: params.serial.trim(), deviceName: params.label?.trim() || '' };
    const changing = deviceIdentity(target) !== deviceIdentity(this.settings);
    if (changing && (this.state.active || this.state.queue.length)) throw new Error('切换手机前，请先停止任务并清空等待队列');
    this.deviceSelecting = true;
    try {
      await this.deviceDisconnecting;
      if (this.bridge && (changing || this.executorCleanupFailed)) {
        await this.closeExecutor(); await this.bridge.settled();
        await Promise.allSettled([...this.hostCalls]);
        if (changing) this.bridge = null;
        this.hostPort = null; this.connectionLost = !changing; this.executorCleanupFailed = false;
      }
      // Merge after prior settings writes, never persist a stale renderer copy
      // of API keys, model choices, restrictions or unrelated preferences.
      if (this.closing) throw new Error('应用正在关闭');
      await this.updateSettings(() => ({ ...this.settings, ...target }));
      await this.ensureDevice();
    } finally { this.deviceSelecting = false; }
  }
  async ensureDevice(recovering = false) {
    if (this.recovery && !recovering) return this.recovery.promise;
    if (this.deviceStarting) return this.deviceStarting;
    if (this.connected) return;
    if (this.closing) throw new Error('应用正在关闭');
    if (!recovering) this.connectionStatus = { code: 'connecting', message: '正在连接手机' };
    this.publish();
    this.deviceStarting = (async () => {
      if (this.executorCleanupFailed) {
        await this.closeExecutor(); await this.bridge?.settled();
        this.executorCleanupFailed = false; this.connectionLost = true;
      }
      return this.connectionLost ? this.reconnectDevice() : this.connectDevice();
    })();
    try { await this.deviceStarting; }
    catch (error) {
      if (!recovering) this.connectionStatus = { code: error.code || 'connection_failed', message: error.message };
      this.connected = false;
      try { await this.closeExecutor(); this.executorCleanupFailed = false; }
      catch (cleanup) { this.executorCleanupFailed = true; error.message += `；${cleanup.message}`; if (!recovering) this.connectionStatus.message = error.message; }
      if (!this.executorCleanupFailed) {
        this.hostPort = null;
        if (!this.connectionLost) this.bridge = null;
      }
      this.publish(); throw error;
    }
    finally { this.deviceStarting = null; }
  }
  async reconnectDevice() {
    await this.deviceDisconnecting;
    if (this.settings.devicePlatform === 'ios') await unlink(this.endpointFile).catch(error => { if (error.code !== 'ENOENT') throw error; });
    this.executorReleased = false;
    try { await this.bridge.recover(); }
    finally {
      // Recovery can spawn a new executor and then fail while observing. Keep
      // its authenticated channel available for graceful failure cleanup.
      this.hostPort = await readFile(this.endpointFile, 'ascii').then(Number).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
    }
    await this.finishDeviceConnection(true);
  }
  async connectDevice() {
    if (!this.settings.serial) throw new Error('请先选择手机');
    const configFile = path.join(this.data, 'device.json');
    await atomicJson(configFile, { serial: this.settings.serial, devicePlatform: this.settings.devicePlatform || 'android', blocked_packages: this.settings.blocked_packages, embedded_preview: true });
    this.hostToken = randomBytes(32).toString('hex');
    this.endpointFile = path.join(this.data, `endpoint-${randomUUID()}.txt`);
    Object.assign(process.env, { BBUI_CONFIG_FILE: configFile, BBUI_RUNS_DIR: path.join(this.data, 'runs'),
      BBUI_DEVICE_STATE_DIR: this.platform.resource('deviceState'), BBUI_ASSET_ROOT: this.platform.resource('assets'),
      BBUI_PYTHON: this.platform.resource('python'), BBUI_MCP_MODULE: this.settings.devicePlatform === 'ios' ? 'bbui.ios_mcp' : 'bbui.desktop_mcp',
      BBUI_HOST_TOKEN: this.hostToken, BBUI_HOST_ENDPOINT: this.endpointFile,
      PYTHONPATH: this.platform.resource('pythonSource'), ADBUTILS_ADB_PATH: this.platform.resource('adb') });
    this.bridge = new PhoneBridge(this.platform.resource('pythonSource'));
    this.executorReleased = false;
    await this.bridge.listTools();
    this.hostPort = Number(await readFile(this.endpointFile, 'ascii'));
    await this.finishDeviceConnection();
  }
  async finishDeviceConnection(recovered = false) {
    let status = await this.requestDevice('status', {}, 5000);
    const deadline = Date.now() + 210000;
    while (!status.connected && this.settings.devicePlatform === 'ios' && ['connecting', 'preparing_support'].includes(status.connectionStatus?.code) && Date.now() < deadline && !this.closing) {
      if (!this.recovery) this.connectionStatus = status.connectionStatus;
      this.publish();
      await new Promise(resolve => setTimeout(resolve, 300));
      status = await this.requestDevice('status', {}, 5000);
    }
    if (!status.connected || this.closing) throw Object.assign(new Error(status.connectionStatus?.message || '手机未连接，请检查 USB 连接和调试授权'), { code: status.connectionStatus?.code });
    this.deviceCapabilities = status.capabilities;
    if (status.stopped && this.control.mode === 'idle') {
      this.control = { ...this.control, id: randomUUID(), mode: 'stopped', canResume: Boolean(this.continuation()) };
    }
    // Recovery already observed the main screen while preserving STOP. Do not
    // invoke a connection operation that the bridge classifies as an input.
    if (!recovered) {
      const result = await this.bridge.call(this.settings.devicePlatform === 'ios' ? '查看' : '连接手机', { 屏幕会话: 'main' });
      if (result.details.错误) throw new Error(result.details.错误);
    }
    if (this.recovery?.controller.signal.aborted) throw new Error('重连已取消');
    this.connected = true; this.connectionLost = false;
    await this.refreshScreens();
    await this.refreshExternalScreens();
    if (!this.recovery) { this.connectionStatus = { code: 'ready', message: '手机已连接' }; this.publish(); }
    this.scheduleDeviceCheck();
  }
  scheduleDeviceCheck() {
    clearTimeout(this.deviceCheckTimer);
    if (!this.connected || this.closing) return;
    this.deviceCheckTimer = setTimeout(() => { void this.checkDeviceConnection(); }, 2000);
    this.deviceCheckTimer.unref();
  }
  async checkDeviceConnection() {
    if (!this.connected || this.closing) return;
    const bridge = this.bridge, port = this.hostPort;
    try {
      const status = await this.requestDevice('status', {}, 5000);
      if (!status.connected) throw Object.assign(new Error(status.connectionStatus?.message || '手机连接已断开，请重新连接'), { code: status.connectionStatus?.code });
    } catch (error) {
      if (this.bridge === bridge && this.hostPort === port && this.connected && !this.closing) {
        await this.reconnectAfterFailure(error).catch(failure => this.fail(failure));
      }
      return;
    }
    await this.refreshExternalScreens();
    this.scheduleDeviceCheck();
  }
  async closeExecutor(bridge = this.bridge) {
    if (!bridge) return;
    let failure;
    if (bridge === this.bridge && this.settings.devicePlatform === 'ios' && this.hostPort && !this.executorReleased) {
      try {
        const result = await this.requestDevice('shutdown', {}, 45000);
        if (result.closed !== true) throw new Error('iPhone 执行器未确认释放设备');
        this.executorReleased = true;
      } catch (error) { failure = new Error(`iPhone 退出清理未确认：${error.message}`); }
    }
    // Close stdin only after graceful release; the MCP SDK otherwise force-kills
    // after 2 seconds, too early for a developer-image unmount.
    await bridge.close();
    if (failure) {
      // A dead executor cannot acknowledge HTTP shutdown. Process exit plus a
      // separately acquired device lease is the required alternative evidence.
      if (!bridge.confirmReleased) throw failure;
      await bridge.confirmReleased();
      this.executorReleased = true;
    }
  }
  async disconnectDevice(reason, code = 'disconnected', recovering = false) {
    if (this.deviceDisconnecting) return this.deviceDisconnecting;
    clearTimeout(this.deviceCheckTimer);
    this.connected = false; this.connectionLost = true; this.screens = []; this.externalScreens = []; this.sessionError = recovering ? null : reason;
    if (!recovering) this.connectionStatus = { code, message: reason };
    const bridge = this.bridge;
    const stopping = this.stop({ cancelRecovery: !recovering, skipDevice: true }); // STOP before waiting for old work.
    this.deviceDisconnecting = (async () => {
      const cleanup = await Promise.allSettled([stopping, this.closeExecutor(bridge)]);
      await bridge?.settled(); await Promise.allSettled([...this.hostCalls]);
      const failed = cleanup.find(result => result.status === 'rejected');
      this.executorCleanupFailed = Boolean(failed);
      if (failed) throw failed.reason;
      this.hostPort = null;
    })();
    try { await this.deviceDisconnecting; }
    finally { this.deviceDisconnecting = null; this.publish(); }
  }
  cancelRecovery() { this.recovery?.controller.abort(); }
  async waitForReconnect(signal) {
    if (signal.aborted) return;
    await new Promise(resolve => {
      const done = () => { clearTimeout(timer); signal.removeEventListener('abort', done); resolve(); };
      const timer = setTimeout(done, 750);
      signal.addEventListener('abort', done, { once: true });
    });
  }
  reconnectAfterFailure(error) {
    if (this.recovery) return this.recovery.promise;
    if (!this.connected || this.closing) return Promise.resolve();
    const recovery = { controller: new AbortController(), promise: null };
    this.recovery = recovery;
    this.connectionStatus = { code: 'reconnecting', message: '正在重连 1/3', attempt: 1 };
    recovery.promise = this.runDeviceRecovery(error, recovery).finally(() => {
      if (this.recovery === recovery) this.recovery = null;
      this.publish();
    });
    return recovery.promise;
  }
  async runDeviceRecovery(error, recovery) {
    const signal = recovery.controller.signal;
    let failure = error;
    try {
      await this.disconnectDevice(error.message, 'reconnecting', true);
      for (let attempt = 1; attempt <= 3 && !signal.aborted && !this.closing; attempt++) {
        this.connectionStatus = { code: 'reconnecting', message: `正在重连 ${attempt}/3`, attempt }; this.publish();
        if (attempt > 1) await this.waitForReconnect(signal);
        if (signal.aborted || this.closing) break;
        try {
          await this.ensureDevice(true);
          if (signal.aborted || this.closing) break;
          this.sessionError = null;
          this.connectionStatus = { code: 'ready', message: '手机已连接' };
          return;
        } catch (next) {
          failure = next;
          // Never spawn another executor while release of the last one is unconfirmed.
          if (this.executorCleanupFailed) break;
        }
      }
    } catch (cleanup) { failure = cleanup; }
    if (this.connected) await this.disconnectDevice('重连已取消', 'reconnecting', true);
    this.connected = false;
    this.connectionStatus = { code: signal.aborted || this.closing ? 'disconnected' : 'connection_failed',
      message: signal.aborted || this.closing ? '手机未连接' : failure.message };
  }
  checkToolConnection(result) {
    if (result?.details?.通道?.控制 === '断开') {
      void this.reconnectAfterFailure(new Error(result.details.错误 || '手机控制连接已断开')).catch(error => this.fail(error));
    }
    return result;
  }
  async disconnect() {
    if (this.state.active || this.state.queue.length) throw new Error('请先停止任务并清空等待队列');
    await this.disconnectDevice('手机已断开');
    this.bridge = null; this.connectionLost = false; this.publish();
  }
  async recoverDevice() {
    if (this.recovery) return this.recovery.promise;
    if (!this.bridge) { await this.ensureDevice(); return { 状态: '手机已连接，任务保持暂停' }; }
    await this.disconnectDevice('正在恢复手机连接');
    await this.ensureDevice();
    return { 状态: '连接已恢复，任务保持暂停', 用户STOP保持不变: true };
  }
  async deviceControl(operation, params = {}) {
    const pending = this.performDeviceControl(operation, params);
    this.hostCalls.add(pending);
    try {
      const result = await pending;
      if (!['frame', 'previewSources', 'stop'].includes(operation)) this.checkToolConnection({ details: result });
      return result;
    }
    catch (error) {
      if (!['frame', 'previewSources', 'stop'].includes(operation) && error.channel === 'control') {
        void this.reconnectAfterFailure(error).catch(failure => this.fail(failure));
      }
      throw error;
    } finally { this.hostCalls.delete(pending); }
  }
  async performDeviceControl(operation, params) {
    if ((!this.connected || this.closing || this.recovery) && operation !== 'stop') throw new Error('请先连接手机');
    if (!this.hostPort) throw new Error('手机执行器未连接');
    return this.requestDevice(operation, params);
  }
  async requestDevice(operation, params, timeout = 125000) {
    let response;
    try { response = await fetch(`http://127.0.0.1:${this.hostPort}/control`, { method: 'POST',
      headers: { Authorization: `Bearer ${this.hostToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ operation, params }), signal: AbortSignal.timeout(timeout) }); }
    catch (error) { throw Object.assign(error, { channel: ['frame', 'previewSources'].includes(operation) ? 'observation' : 'control' }); }
    const value = await response.json();
    if (!response.ok) throw Object.assign(new Error(value.error), { channel: value.channel, code: value.code });
    return value;
  }
  async refreshScreens() {
    const result = await this.bridge.call('列出屏幕', {});
    this.screens = result.details.屏幕会话列表 || []; this.publish();
  }
  async refreshExternalScreens() {
    if (!this.connected || this.closing) return;
    const bridge = this.bridge, port = this.hostPort;
    try {
      const screens = await this.requestDevice('previewSources', {}, 4000);
      if (this.bridge !== bridge || this.hostPort !== port || !this.connected || this.closing || !Array.isArray(screens)) return;
      this.externalDiscoveryError = null;
      if (JSON.stringify(screens) !== JSON.stringify(this.externalScreens)) { this.externalScreens = screens; this.publish(); }
    } catch (error) {
      // Discovery is independent from device health; retain the known list.
      if (this.bridge === bridge && this.hostPort === port) this.externalDiscoveryError = error.message;
    }
  }
  async handleAgent(request, response) {
    const send = (status, value) => { if (!response.destroyed) { response.writeHead(status, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(value)); } };
    const authorization = Buffer.from(request.headers.authorization || '');
    const expected = Buffer.from(`Bearer ${this.agentToken || ''}`);
    if (!this.state.active || request.method !== 'POST' || expected.length !== authorization.length || !timingSafeEqual(authorization, expected)) return send(403, { error: '运行代次无效' });
    try {
      let text = ''; for await (const chunk of request) { text += chunk; if (text.length > 131072) throw new Error('请求过大'); }
      const body = JSON.parse(text);
      // Aborting Pi during recovery also aborts its in-flight tool signal. That
      // /cancel is acknowledgement of our own STOP, not a new user STOP.
      if (request.url === '/cancel' && this.recovery) return send(200, { stopped: true });
      if (request.url === '/stop' || (request.url === '/cancel' && this.control.mode !== 'taking_over')) {
        // Actual STOP always wins, including a transport failure during takeover.
        void this.stop().catch(error => this.fail(error)); return send(200, { stopped: true });
      }
      if (request.url === '/cancel') return send(200, { held: true });
      if (this.control.mode !== 'running') return send(409, { error: '本运行已暂停' });
      if (request.url === '/phone') {
        if (this.deviceCapabilities?.phoneOperations && !this.deviceCapabilities.phoneOperations.includes(body.operation)) throw new Error('当前设备不支持该操作');
        if (!body.params?.屏幕会话) throw new Error('必须明确指定目标屏幕');
        body.params.动作编号 = createHash('sha256').update(this.runId + ':' + body.params.动作编号).digest('hex');
        send(200, this.checkToolConnection(await this.bridge.call(body.operation, body.params)));
        if (['创建屏幕', '关闭屏幕'].includes(body.operation)) await this.refreshScreens();
      } else if (request.url === '/system') {
        if (this.deviceCapabilities?.systemOperations && !this.deviceCapabilities.systemOperations[body.group]?.includes(body.operation)) throw new Error('当前设备不支持该系统操作');
        body.actionId = createHash('sha256').update(this.runId + ':' + body.actionId).digest('hex');
        send(200, this.checkToolConnection(await this.bridge.invoke('system_action', body, reads[body.group]?.includes(body.operation) === true)));
      }
      else if (request.url === '/questions') {
        if (this.question || !Array.isArray(body.questions) || body.questions.length < 1 || body.questions.length > 4) throw new Error('提问格式错误');
        const record = { requestId: randomUUID(), runId: this.runId, sessionId: this.state.active.sessionId,
          toolCallId: body.toolCallId, questions: body.questions, status: 'pending' };
        this.state.questions.push(record);
        const result = await new Promise(resolve => { this.question = { record, resolve }; this.publish(); });
        send(200, result);
      } else send(404, { error: '不支持的工具入口' });
    } catch (error) { send(409, { error: error.message }); }
  }
  async runNext() {
    if (this.closing || this.state.active || this.starting || this.finishing || this.state.paused || !this.state.queue.length || this.pendingSubmissions.has(this.state.queue[0].id)) return;
    if (!this.connected || !this.hasModel()) { this.pause('environment'); this.publish(); return; }
    this.starting = true;
    const epoch = this.stopEpoch, task = this.state.queue[0];
    const cancelled = () => this.closing || this.state.paused || epoch !== this.stopEpoch || this.state.queue[0] !== task;
    try {
      await this.sessionWrites;
      await this.ensureDevice();
      if (cancelled()) return;
      const sealed = await readJson(path.join(this.data, 'configurations', task.configRevision + '.json'));
      const settings = this.platform.unseal(sealed.value);
      await this.requireTaskDevice(task, settings);
      const connection = settings.connections.find(c => c.id === task.selection.connectionId);
      const model = connection.models.find(m => m.id === task.selection.modelId);
      await this.memoryWorker?.close(); this.memoryWorker = null;
      if (cancelled()) return;
      this.runId = randomUUID(); this.agentToken = randomBytes(32).toString('hex');
      this.state.active = this.state.queue.shift();
      this.control = { id: randomUUID(), mode: 'running', sessionId: task.sessionId, taskId: task.id, canResume: false, canSteer: true };
      this.status = { phase: 'running', message: '正在执行' };
      await this.save({ failure: () => {
        this.state.active = null; this.state.queue.unshift(task);
        if (this.control.mode === 'running') this.control = { id: randomUUID(), mode: 'error', sessionId: task.sessionId, canResume: false, canSteer: false };
      } });
      if (epoch !== this.stopEpoch || this.closing || this.control.mode !== 'running' || this.state.paused) { await this.finishRun(true); return; }
      this.agent = new PiProcess(this.platform, { ...connection, ...model, model: model.id, platform: 'desktop',
        thinkingLevel: task.selection.thinkingLevel || undefined, sessionId: task.sessionId,
        bridgeUrl: this.bridgeUrl, bridgeToken: this.agentToken, tools: settings.tools, deviceCapabilities: this.deviceCapabilities,
        memoryDir: path.join(this.data, 'memory') }, this.home);
      this.finished = new Promise(resolve => { this.finishResolve = resolve; });
      const agent = this.agent;
      this.agent.on('event', event => { if (this.agent === agent) this.onAgentEvent(event); });
      this.agent.on('failure', error => { if (this.agent === agent) { this.fail(error); void this.finishRun(true); } });
      this.agent.on('closed', () => { if (this.agent === agent && this.state.active) void this.finishRun(true); });
      await this.agent.request({ type: 'get_state' });
      if (epoch !== this.stopEpoch || this.closing || this.control.mode !== 'running') { await this.finishRun(true); return; }
      this.publish();
      await this.agent.request({ type: 'prompt', message: task.text }, 120000);
    } catch (error) { this.fail(error); if (this.state.active) await this.finishRun(true); }
    finally { this.starting = false; this.emit('sessionWritable'); if (!this.state.active && !this.state.paused) void this.runNext(); }
  }
  onAgentEvent(event) {
    if (!this.state.active) return;
    const id = this.state.active.sessionId;
    if (event.message) {
      const messages = this.messages.get(id) || [];
      const at = messages.findLastIndex(message => message.role === event.message.role &&
        (message.role === 'toolResult' ? message.toolCallId === event.message.toolCallId : message.timestamp === event.message.timestamp));
      if (at >= 0) messages[at] = event.message;
      else messages.push(event.message);
      this.messages.set(id, messages);
    }
    if (event.type === 'tool_execution_end') {
      const messages = this.messages.get(id) || [];
      const at = messages.findLastIndex(message => message.role === 'toolResult' && message.toolCallId === event.toolCallId);
      const result = { ...event.result, role: 'toolResult', toolName: event.toolName, toolCallId: event.toolCallId, timestamp: Date.now() };
      if (at >= 0) messages[at] = result; else messages.push(result);
      this.messages.set(id, messages);
    }
    if (event.type === 'agent_end') void this.finishRun(false);
    else this.publish();
  }
  async finishRun(failed) {
    if (this.finishing || !this.state.active) return;
    this.finishing = true;
    const task = this.state.active;
    if (failed) this.pause('error');
    for (const pending of this.pendingSteering.values()) if (pending.task === task) pending.settled = true;
    try {
      await this.sessionWrites;
      if (failed || this.control.mode !== 'running') this.state.interrupted.push({ ...task, interruptedReason: 'running' });
      this.cancelQuestion();
      const agent = this.agent; this.agent = null;
      this.state.active = null; this.agentToken = null;
      await agent?.close(); await this.bridge?.settled();
      await this.loadHistory(task.sessionId); await this.refreshSessions();
      if (this.control.mode === 'running') {
        this.control = { id: randomUUID(), mode: failed ? 'error' : 'idle', sessionId: task.sessionId, taskId: task.id, canResume: failed, canSteer: false };
        this.status = { phase: failed ? 'error' : 'idle', message: failed ? '运行中断，已保留记录' : '本轮回复结束' };
      }
      this.publish();
    } finally { this.finishing = false; this.emit('sessionWritable'); this.finishResolve?.(); if (!this.state.paused) void this.runNext(); }
  }
  cancelQuestion() {
    if (this.question) { this.question.record.status = 'interrupted'; this.question.resolve({ cancelled: true }); this.question = null; }
  }
  fail(error) { this.sessionError = error.message; this.pause('error'); this.status = { phase: 'error', message: error.message }; this.publish(); }
  rejectCommand(command, error) {
    this.sessionError = error.message;
    if (['send', 'steer'].includes(command.type)) this.submissionResult = { id: command.submissionId, sessionId: command.sessionId, accepted: false };
    if (['selectModel', 'setThinkingLevel'].includes(command.type)) this.modelSelectionResult = { id: command.requestId, sessionId: command.sessionId, accepted: false, error: error.message };
    if (command.type === 'answerQuestion') this.questionResult = { requestId: command.requestId, sessionId: command.sessionId, runId: command.runId, accepted: false, error: error.message };
    this.publish();
  }
  continuation() {
    const sessionId = this.control.sessionId;
    if (!sessionId) return undefined;
    if (this.state.active?.sessionId === sessionId) return this.state.active;
    return this.state.interrupted?.find(task => task.id === this.control.taskId && task.sessionId === sessionId && task.interruptedReason === 'running');
  }
  async stop({ cancelRecovery = true, skipDevice = false } = {}) {
    skipDevice ||= Boolean(this.recovery);
    if (cancelRecovery) this.cancelRecovery();
    this.stopEpoch++; this.pause('stop'); this.manualToken = null;
    this.control = { ...this.control, id: randomUUID(), mode: 'stopped', canResume: Boolean(this.continuation()), canSteer: false };
    this.cancelQuestion(); this.publish();
    if (this.bridge) {
      await this.bridge.setStopped(true);
      try { if (!skipDevice) await this.deviceControl('stop'); }
      catch (error) { this.sessionError = `停止已记录，设备通道不可用：${error.message}`; this.publish(); }
    }
    if (this.agent) { await this.agent.request({ type: 'abort' }); await this.finished; }
  }
  async resumeDevice() {
    const epoch = this.stopEpoch;
    await this.deviceControl('resume'); await this.bridge.setStopped(false);
    if (epoch !== this.stopEpoch || this.closing) {
      await this.bridge.setStopped(true); await this.deviceControl('stop');
      throw new Error('用户 STOP 已生效');
    }
  }
  async takeover(controlId) {
    if (controlId !== this.control.id || !['running', 'idle', 'stopped', 'error'].includes(this.control.mode)) throw new Error('控制权已变化');
    const epoch = this.stopEpoch;
    this.pause('stop'); this.control = { ...this.control, id: randomUUID(), mode: 'taking_over', canSteer: false };
    this.cancelQuestion(); this.publish();
    try {
      await this.deviceControl('hold');
      if (this.agent) { await this.agent.request({ type: 'abort' }); await this.finished; }
      await this.bridge.settled();
      if (epoch !== this.stopEpoch || this.closing) throw new Error('用户 STOP 已生效');
      // Clear STOP only for this explicit manual grant; the model's input gate stays held.
      this.manualToken = (await this.deviceControl('manual', { resumeStopped: true })).token;
      if (epoch !== this.stopEpoch || this.closing) {
        this.manualToken = null;
        await this.bridge.setStopped(true); await this.deviceControl('stop');
        throw new Error('用户 STOP 已生效');
      }
      this.control = { ...this.control, mode: 'manual', canResume: Boolean(this.continuation()) }; this.publish();
    } catch (error) {
      this.manualToken = null;
      if (epoch === this.stopEpoch && !this.closing) {
        this.control = { ...this.control, mode: 'error', canResume: Boolean(this.continuation()) }; this.publish();
      }
      throw error;
    }
  }
  acknowledgeSubmission(command) {
    this.submissionResult = { id: command.submissionId, sessionId: command.sessionId, accepted: true };
  }
  clearSubmittedDraft(sessionId, revision) {
    if ((this.draftRevisions.get(sessionId) || 0) !== revision) return;
    this.state.views[sessionId] = { ...this.state.views[sessionId], text: '' };
    this.draftRevisions.set(sessionId, revision + 1);
  }
  async submit(command) {
    const { submissionId, sessionId } = command;
    const pending = this.pendingSubmissions.get(submissionId);
    if (pending) {
      if (pending.task.sessionId !== sessionId) throw new Error('该提交编号已使用');
      await pending.finished; this.acknowledgeSubmission(command); return;
    }
    this.state.submissions ||= {};
    const previous = this.state.submissions[submissionId];
    if (previous) {
      if (previous.sessionId !== sessionId || previous.type !== 'send' || previous.status !== 'accepted') throw new Error('该提交编号已使用');
      this.acknowledgeSubmission(command); return;
    }
    this.requireReady();
    const ticket = this.freshSubmissionPause();
    const task = bindSubmission(this.state, this.settings, command);
    const connection = this.settings.connections.find(item => item.id === task.selection.connectionId);
    const titleConfig = structuredClone({ ...connection, ...connection.models.find(item => item.id === task.selection.modelId), model: task.selection.modelId });
    const draftRevision = this.draftRevisions.get(sessionId) || 0;
    const entry = { task, durable: false };
    this.pendingSubmissions.set(submissionId, entry);
    this.state.queue.push(task);
    this.state.submissions[submissionId] = { sessionId, type: 'send', status: 'accepted' };
    let titleMarker, previousTitle;
    const rollback = () => {
      this.state.queue = this.state.queue.filter(item => item !== task);
      delete this.state.submissions[submissionId];
      if (titleMarker && this.state.titleGeneration[sessionId] === titleMarker) this.state.titleGeneration[sessionId] = previousTitle;
    };
    entry.finished = (async () => {
      try {
        await this.catalog.resolve(sessionId); this.requireReady();
        if (!this.state.queue.includes(task)) throw new Error('任务已取消');
        await this.save({ includingSubmission: submissionId, prepare: () => {
          previousTitle = this.state.titleGeneration[sessionId];
          if (previousTitle?.status === 'eligible') {
            this.state.titleGeneration[sessionId] = titleMarker = { status: 'attempted', submissionId };
            entry.titleMarker = titleMarker; entry.previousTitle = previousTitle;
          }
        }, failure: rollback, success: () => {
          entry.durable = true;
          this.clearSubmittedDraft(sessionId, draftRevision);
        } });
        if (titleMarker) this.generateTitle(task, titleMarker, titleConfig);
        // A new, durably saved goal may release only an automatic idle pause.
        // Keep its queue head blocked until the separate release is saved too.
        if (this.validFreshPause(ticket, task)) {
          try {
            await this.resumeDevice();
            if (this.validFreshPause(ticket, task)) {
              this.unpause(); const revision = this.pauseRevision;
              await this.save({ failure: () => {
                for (const reason of ticket.reasons) this.pause(reason);
              } });
              if (revision === this.pauseRevision) this.control = { id: randomUUID(), mode: 'idle', sessionId: '', canResume: false, canSteer: false };
            }
          } catch (error) { this.sessionError = `任务已排队，队列保持暂停：${error.message}`; }
        }
        this.acknowledgeSubmission(command);
      } catch (error) { if (!entry.durable) rollback(); throw error; }
      finally { this.pendingSubmissions.delete(submissionId); }
    })();
    return entry.finished;
  }
  async steer(command) {
    const { submissionId, sessionId, text } = command;
    if (!submissionId || !text?.trim()) throw new Error('缺少补充内容');
    const pending = this.pendingSteering.get(submissionId);
    if (pending) {
      if (pending.task.sessionId !== sessionId) throw new Error('该提交编号已使用');
      await pending.finished; this.acknowledgeSubmission(command); return;
    }
    this.state.submissions ||= {};
    const previous = this.state.submissions[submissionId];
    if (this.pendingSubmissions.has(submissionId) || previous?.type === 'send') { await this.submit(command); return; }
    if (previous) {
      if (previous.sessionId !== sessionId || previous.type !== 'steer' || previous.status !== 'accepted') throw new Error('此前补充未确认接收，请检查会话后重新提交');
      this.acknowledgeSubmission(command); return;
    }
    if (this.question) { await this.submit(command); return; }
    const agent = this.agent, task = this.state.active, controlId = this.control.id;
    const draftRevision = this.draftRevisions.get(sessionId) || 0;
    if (!agent || sessionId !== task?.sessionId || this.control.mode !== 'running') throw new Error('该会话没有正在运行的任务');
    const receipt = { sessionId, type: 'steer', status: 'pending' };
    const entry = { task, settled: false };
    this.state.submissions[submissionId] = receipt;
    this.pendingSteering.set(submissionId, entry);
    entry.finished = (async () => {
      try {
        await this.save({ failure: () => { delete this.state.submissions[submissionId]; } });
        if (entry.settled || this.agent !== agent || this.state.active !== task || this.control.id !== controlId || this.control.mode !== 'running') throw new Error('本轮已结束，补充内容未发送');
        await agent.request({ type: 'steer', message: text }, 15000);
        if (entry.settled || this.agent !== agent || this.state.active !== task || this.control.id !== controlId || this.control.mode !== 'running') throw new Error('本轮已结束，补充内容未确认接收');
        (task.steering ||= []).push(text);
        receipt.status = 'accepted'; this.clearSubmittedDraft(sessionId, draftRevision); this.acknowledgeSubmission(command);
      } catch (error) { receipt.status = 'unconfirmed'; throw error; }
      finally { this.pendingSteering.delete(submissionId); }
    })();
    return entry.finished;
  }
  async steerQueued(command) {
    const { sessionId, submissionId, controlId, runId } = command;
    const agent = this.agent, task = this.state.active;
    const sameRun = () => !this.closing && agent && this.agent === agent && this.state.active === task &&
      task?.sessionId === sessionId && this.runId === runId && this.control.id === controlId &&
      this.control.mode === 'running';
    const current = () => sameRun() && this.control.canSteer !== false && !this.question;
    if (!submissionId || !runId || !controlId || !current()) throw new Error('当前执行状态已变化，无法立即补充');
    const pending = this.pendingSteering.get(submissionId);
    if (pending) {
      if (pending.kind !== 'queued' || pending.task !== task || pending.runId !== runId || pending.controlId !== controlId) throw new Error('该消息已在其他执行中处理');
      return pending.finished;
    }
    const previous = this.state.submissions[submissionId];
    const prior = previous?.queuedSteering;
    if (prior) {
      if (previous.sessionId === sessionId && prior.runId === runId && prior.controlId === controlId && prior.status === 'accepted') return;
      throw new Error('该补充已处理，请核对接收状态，不会自动重发');
    }
    const queued = this.state.queue.find(item => item.id === submissionId && item.sessionId === sessionId);
    if (!queued || this.pendingSubmissions.has(submissionId) || previous?.type !== 'send' || previous.sessionId !== sessionId || previous.status !== 'accepted') throw new Error('该等待消息不存在或尚未保存');
    const recovery = { ...queued, interruptedReason: 'steering', steeringStatus: 'unconfirmed' };
    const receipt = { runId, controlId, status: 'pending' };
    const entry = { kind: 'queued', task, runId, controlId, settled: false };
    this.pendingSteering.set(submissionId, entry);
    const accepted = () => {
      receipt.status = recovery.steeringStatus = 'accepted';
      if (!receipt.recorded) {
        const original = [task, ...this.state.interrupted.filter(item => item.id === task.id && item.sessionId === sessionId && item.interruptedReason === 'running')];
        const directions = new Set(original.map(item => item.steering ||= []));
        for (const values of directions) values.push(queued.text);
        receipt.recorded = true;
      }
      const remove = this.state.interrupted.includes(recovery) && sameRun() && !entry.settled;
      if (remove) this.state.interrupted = this.state.interrupted.filter(item => item !== recovery);
      return remove;
    };
    let claimed = false, position, before, after;
    entry.finished = (async () => {
      try {
        await this.save({ prepare: () => {
          if (!current() || entry.settled) throw new Error('当前执行已结束，补充内容未发送');
          position = this.state.queue.indexOf(queued);
          if (position < 0) throw new Error('该等待消息已取消');
          before = this.state.queue.slice(0, position); after = this.state.queue.slice(position + 1);
          this.state.queue.splice(position, 1); this.state.interrupted.push(recovery);
          this.state.submissions[submissionId] = { ...previous, queuedSteering: receipt }; claimed = true;
        }, failure: () => {
          this.state.interrupted = this.state.interrupted.filter(item => item !== recovery);
          const next = this.state.queue.findIndex(item => after.includes(item));
          const previousPosition = this.state.queue.findLastIndex(item => before.includes(item));
          this.state.queue.splice(next >= 0 ? next : previousPosition + 1, 0, queued);
          this.state.submissions[submissionId] = previous; claimed = false;
        } });
        if (!current() || entry.settled) {
          receipt.status = recovery.steeringStatus = 'not_sent';
          await this.save(); throw new Error('当前执行已结束，补充内容未发送，已保留待核对');
        }
        // From this point a transport failure cannot prove Pi did not receive it.
        await agent.request({ type: 'steer', message: queued.text }, 15000, { onLateResponse: async received => {
          if (this.closing || this.state.submissions[submissionId]?.queuedSteering !== receipt) return;
          let removed = false;
          if (!this.state.interrupted.includes(recovery)) receipt.status = received ? 'accepted' : 'not_sent';
          else if (received) removed = accepted(); else receipt.status = recovery.steeringStatus = 'not_sent';
          await this.save({ failure: () => {
            if (removed && this.state.submissions[submissionId]?.queuedSteering === receipt && !this.state.interrupted.includes(recovery)) this.state.interrupted.push(recovery);
          } }); this.publish();
        } });
        const removed = accepted();
        await this.save({ failure: () => {
          if (removed && this.state.submissions[submissionId]?.queuedSteering === receipt && !this.state.interrupted.includes(recovery)) this.state.interrupted.push(recovery);
        } });
      } catch (error) {
        if (claimed && receipt.status === 'pending') receipt.status = recovery.steeringStatus = error.responseReceived ? 'not_sent' : 'unconfirmed';
        throw error;
      } finally {
        this.pendingSteering.delete(submissionId);
        this.publish();
      }
    })();
    return entry.finished;
  }
  async command(command) {
    this.sessionError = undefined;
    const id = command.sessionId || this.state.selected;
    switch (command.type) {
      case 'ready': break;
      case 'rendered': return;
      case 'openSettings': this.emit('settings'); return;
      case 'openPreview': this.emit('preview'); return;
      case 'viewState':
        if (this.state.views[id]?.text !== command.text) this.draftRevisions.set(id, (this.draftRevisions.get(id) || 0) + 1);
        this.state.views[id] = { text: command.text, scrollTop: command.scrollTop }; break;
      case 'selectSession': await this.catalog.resolve(id); this.state.selected = id; if (id !== this.state.active?.sessionId) await this.loadHistory(id); break;
      case 'newSession': this.state.selected = await this.createSession(); await this.refreshSessions(); break;
      case 'refreshSessions': await this.refreshSessions(); break;
      case 'renameSession':
        this.state.titleGeneration[id] = { status: 'manual' };
        await this.mutateSession(() => this.writeSessionName(id, command.title)); break;
      case 'deleteSession':
        if (id === this.state.active?.sessionId) throw new Error('请先停止该会话的任务');
        if ([...this.pendingSteering.values()].some(item => item.kind === 'queued' && item.task.sessionId === id)) throw new Error('该会话的补充消息正在确认接收，请稍后删除');
        delete this.state.titleGeneration[id];
        this.state.queue = this.state.queue.filter(q => q.sessionId !== id); this.state.interrupted = this.state.interrupted.filter(q => q.sessionId !== id);
        await this.mutateSession(async () => {
          if (id === this.state.active?.sessionId) throw new Error('请先停止该会话的任务');
          await this.catalog.execute({ action: 'delete', sessionId: id });
          for (const [submissionId, receipt] of Object.entries(this.state.submissions)) if (receipt.sessionId === id) delete this.state.submissions[submissionId];
        }); this.messages.delete(id); await this.refreshSessions();
        if (this.state.selected === id) { this.state.selected = this.sessions[0]?.id || await this.createSession(); await this.loadHistory(this.state.selected); await this.refreshSessions(); } break;
      case 'loadOlder': this.pages.set(id, (this.pages.get(id) || 40) + 40); break;
      case 'send': {
        await this.submit({ ...command, sessionId: id }); break;
      }
      case 'steer':
        await this.steer({ ...command, sessionId: id }); break;
      case 'steerQueued':
        await this.steerQueued({ ...command, sessionId: id }); break;
      case 'pauseQueue': this.pause('user'); break;
      case 'resumeQueue':
        this.requireReady();
        if (['manual', 'taking_over'].includes(this.control.mode)) throw new Error('请先结束人工接管');
        { const revision = this.pauseRevision; await this.resumeDevice(); if (revision === this.pauseRevision) this.unpause(); } break;
      case 'cancelQueued':
        if (this.pendingSteering.get(command.submissionId)?.kind === 'queued') throw new Error('该消息正在立即补充，无法取消等待项');
        this.state.queue = this.state.queue.filter(q => q.id !== command.submissionId); break;
      case 'stop': await this.stop(); break;
      case 'takeOver': await this.takeover(command.controlId); break;
      case 'endManual':
        if (command.controlId !== this.control.id || this.control.mode !== 'manual') throw new Error('控制权已过期');
        await this.stop(); break;
      case 'resumeTask': {
        this.requireReady();
        if (command.controlId !== this.control.id || !['manual', 'stopped', 'error'].includes(this.control.mode)) throw new Error('控制权已过期');
        const interrupted = this.continuation();
        if (!interrupted || this.state.active) throw new Error('没有可继续的中断任务');
        const revision = this.pauseRevision;
        await this.requireTaskDevice(interrupted);
        if (revision !== this.pauseRevision || command.controlId !== this.control.id) throw new Error('控制权或队列状态已变化');
        await this.resumeDevice(); this.manualToken = null;
        if (revision !== this.pauseRevision) throw new Error('队列暂停状态已变化');
        if (command.controlId !== this.control.id) throw new Error('控制权已过期');
        if (this.continuation()?.id !== interrupted.id) { await this.stop(); throw new Error('该中断任务已处理'); }
        this.state.interrupted = this.state.interrupted.filter(q => q.id !== interrupted.id);
        this.state.queue.unshift({ ...interrupted, id: randomUUID(), steering: [], text: `用户明确要求继续未完成目标：${[interrupted.text, ...(interrupted.steering || [])].join('\n')}\n先重新观察设备，检查之前已派发操作的实际结果；不要重放不确定输入，再完成尚未完成的部分。` });
        this.control = { ...this.control, id: randomUUID(), mode: 'idle', canResume: false }; this.unpause(); break;
      }
      case 'resumeInterrupted': {
        this.requireReady();
        if (this.state.active || ['manual', 'taking_over'].includes(this.control.mode)) throw new Error('请先结束当前执行或人工接管');
        const task = this.state.interrupted.find(item => item.id === command.submissionId);
        if (!task) throw new Error('该中断任务已处理');
        const revision = this.pauseRevision, controlId = this.control.id;
        await this.requireTaskDevice(task);
        if (revision !== this.pauseRevision || controlId !== this.control.id) throw new Error('控制权或队列状态已变化');
        await this.resumeDevice();
        if (revision !== this.pauseRevision || controlId !== this.control.id || !this.state.interrupted.includes(task)) throw new Error('该中断任务或队列状态已变化');
        this.state.interrupted = this.state.interrupted.filter(item => item.id !== task.id);
        this.state.queue.push({ ...task, id: randomUUID(), steering: [], text: task.interruptedReason === 'steering'
          ? `用户明确要求核对并继续这条补充：${task.text}\n此前补充接收状态：${task.steeringStatus === 'accepted' ? 'Pi 已确认接收，但完成情况需核对' : task.steeringStatus === 'not_sent' ? '未派发' : '接收结果未确认'}。先检查会话中之前的补充及已派发操作的实际结果，只完成尚未完成的部分，不要重放不确定输入。`
          : task.interruptedReason === 'queued' ? task.text
          : `用户明确要求继续未完成目标：${[task.text, ...(task.steering || [])].join('\n')}\n先重新观察并核对已派发操作的实际结果，不要重放不确定输入。` });
        this.unpause(); await this.save(); break;
      }
      case 'selectModel': case 'setThinkingLevel': {
        const previous = this.state.selections[id] || this.settings.defaultModel;
        const selection = command.type === 'selectModel' ? { connectionId: command.connectionId, modelId: command.modelId, thinkingLevel: '' } : { ...previous, thinkingLevel: command.thinkingLevel };
        const option = this.snapshot().modelOptions.find(m => m.connectionId === selection.connectionId && m.modelId === selection.modelId);
        if (!option || (selection.thinkingLevel && !option.thinkingLevels.some(l => l.id === selection.thinkingLevel))) throw new Error('模型或思考档位不可用');
        this.state.selections[id] = selection;
        this.modelSelectionResult = { id: command.requestId, sessionId: id, accepted: true }; break;
      }
      case 'questionDraft': case 'answerQuestion': {
        const q = this.question?.record;
        if (command.type === 'questionDraft') {
          if (q?.requestId === command.requestId && q.runId === command.runId && q.sessionId === command.sessionId) q.draft = command.answers;
        } else {
          validateAnswer(q, command); q.status = command.cancelled ? 'cancelled' : 'answered'; q.answers = command.answers;
          this.question.resolve({ cancelled: Boolean(command.cancelled), answers: command.answers }); this.question = null;
          this.questionResult = { requestId: q.requestId, sessionId: q.sessionId, runId: q.runId, accepted: true };
        } break;
      }
      default: throw new Error('不支持的界面命令');
    }
    this.publish(); void this.runNext();
  }
  publicSettings() {
    return { ...this.settings, devicePlatform: this.settings.devicePlatform || 'android', connections: this.settings.connections.map(c => ({ ...c, apiKey: '', hasKey: Boolean(c.apiKey) })),
      tools: { search: { ...this.settings.tools.search, apiKey: '', hasKey: Boolean(this.settings.tools.search.apiKey) } } };
  }
  updateSettings(value) {
    const write = this.settingsWrites.then(() => this.persistSettings(value()));
    this.settingsWrites = write.catch(() => {});
    return write;
  }
  saveSettings(value) {
    if (this.closing) return Promise.reject(new Error('应用正在关闭'));
    return this.updateSettings(() => {
      if (this.deviceSelecting && deviceIdentity(value) !== deviceIdentity(this.settings)) throw new Error('手机正在连接，请稍后修改设备');
      return value;
    });
  }
  async persistSettings(value) {
    if (value.devicePlatform && !['android', 'ios'].includes(value.devicePlatform)) throw new Error('不支持的设备平台');
    if ((this.bridge || this.deviceStarting || this.state.active || this.state.queue.length) && (deviceIdentity(value) !== deviceIdentity(this.settings) || JSON.stringify(value.blocked_packages) !== JSON.stringify(this.settings.blocked_packages))) throw new Error('修改设备或应用禁止列表前，请断开设备并移除等待任务');
    if (!Array.isArray(value.connections) || !Array.isArray(value.blocked_packages) || value.blocked_packages.some(p => !/^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$/.test(p))) throw new Error('设置格式错误');
    for (const connection of value.connections) {
      const url = new URL(connection.baseUrl);
      if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password || !connection.models.every(m => m.id?.trim())) throw new Error('请输入有效 API 地址和模型 ID');
    }
    const next = { ...value, devicePlatform: value.devicePlatform || 'android', version: 1, revision: randomUUID() };
    next.connections = value.connections.map(c => ({ ...c, apiKey: c.apiKey || this.settings.connections.find(old => old.id === c.id)?.apiKey || '',
      models: c.models.map(m => ({ ...m, ...(this.modelCatalog.find(known => known.provider === c.provider && known.api === c.api && known.id === m.id) || {} ) })) }));
    next.tools.search.apiKey ||= this.settings.tools.search.apiKey || '';
    const sealed = { value: await this.platform.seal(next) };
    await atomicJson(path.join(this.data, 'configurations', next.revision + '.json'), sealed);
    await atomicJson(path.join(this.data, 'settings.json'), sealed);
    if (deviceIdentity(next) !== deviceIdentity(this.settings)) this.deviceCapabilities = undefined;
    this.settings = next;
    if (!this.hasModel()) this.pause('environment');
    this.publish(); this.emit('preferences');
    return this.publicSettings();
  }
  async memory(command) {
    let worker = this.agent;
    if (!worker) {
      this.memoryWorker ||= new PiProcess(this.platform, { catalogOnly: true, memoryDir: path.join(this.data, 'memory') }, this.home);
      worker = this.memoryWorker;
    }
    return worker.request({ ...command, type: 'bbui_memory' });
  }
  async importFiles(files) {
    if (this.state.active) throw new Error('请先停止当前任务');
    for (const file of files) {
      if (path.extname(file).toLowerCase() !== '.jsonl') throw new Error('请选择 Pi JSONL 会话');
      const first = (await readFile(file, 'utf8')).split('\n', 1)[0];
      if (JSON.parse(first).type !== 'session') throw new Error('不是 Pi 会话文件');
      if (!(await this.catalog.list()).some(s => s.id === JSON.parse(first).id)) await copyFile(file, path.join(this.home, 'sessions', `import-${randomUUID()}.jsonl`));
    }
    await this.refreshSessions(); this.publish();
  }
  async close() {
    this.closing = true; this.cancelRecovery(); clearTimeout(this.deviceCheckTimer);
    await this.recovery?.promise;
    await this.settingsWrites;
    await this.deviceStarting?.catch(() => {});
    const titleClosing = this.titleWorker?.close();
    // Retry normal shutdown below even if the earlier disconnect cleanup failed.
    await this.deviceDisconnecting?.catch(() => {}); await this.stop();
    await Promise.allSettled([...this.hostCalls]);
    await this.memoryWorker?.close(); await this.bridge?.settled(); await this.closeExecutor();
    await titleClosing; await Promise.allSettled([...this.titleJobs]);
    if (this.endpointFile) await unlink(this.endpointFile).catch(error => { if (error.code !== 'ENOENT') throw error; });
    this.server?.closeAllConnections(); await new Promise(resolve => this.server?.close(resolve));
    await this.save();
  }
}
