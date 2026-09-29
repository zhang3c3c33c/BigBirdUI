import { isReadOnlyOperation, toolFailure } from './phone-contract.ts';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { randomUUID, createHash } from 'node:crypto';
import { homedir } from 'node:os';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { readFile, writeFile, unlink, mkdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { pythonRuntime } from './runtime-paths.ts';

export const ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)));
export type PhoneParams = { 执行后等待毫秒?: number; 屏幕会话?: string; [key: string]: unknown };
export type PhoneContent = { type: 'text'; text: string } | { type: 'image'; data: string; mimeType: string };

export function convertContent(content: any[]): PhoneContent[] {
  return content.flatMap(item => {
    if (item.type === 'text') return [{ type: 'text', text: item.text } as PhoneContent];
    if (item.type === 'image') return [{ type: 'image', data: item.data, mimeType: item.mimeType ?? item.mime_type } as PhoneContent];
    return [];
  });
}

const execFileAsync = promisify(execFile);

async function bounded<T>(promise: Promise<T>, milliseconds: number, message: string): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([promise, new Promise<never>((_resolve, reject) => {
      timer = setTimeout(() => reject(new Error(message)), milliseconds);
    })]);
  } finally { if (timer) clearTimeout(timer); }
}

export class PhoneBridge {
  constructor(private readonly root = ROOT) {}
  private client?: Client;
  private transport?: StdioClientTransport;
  private starting?: Promise<Client>;
  private inputUncertain = false;
  private userStopped = false;
  private readonly owner = randomUUID();
  private connectionFailed = false;
  private recovering = false;
  private readonly pendingCalls = new Set<Promise<void>>();
  private exitState?: { ended: boolean; promise: Promise<void> };


  private async connect(): Promise<Client> {
    if (this.connectionFailed) throw new Error('执行器连接不可用，需要宿主恢复连接。不会自动重启或重放动作。');
    if (this.client) return this.client;
    if (this.starting) return this.starting;
    this.starting = (async () => {
      const command = pythonRuntime(this.root);
      const transport = new StdioClientTransport({
        command, args: ['-X', 'utf8', '-m', process.env.BBUI_MCP_MODULE || 'bbui.unified_mcp'], cwd: this.root,
        env: { ...Object.fromEntries(Object.entries(process.env).filter((pair): pair is [string, string] => pair[1] !== undefined)), PYTHONUTF8: '1', BBUI_BRIDGE_OWNER: this.owner },
        stderr: 'pipe',
      });
      let confirmExit!: () => void;
      const exitState = { ended: false, promise: new Promise<void>(resolve => { confirmExit = resolve; }) };
      // StdioClientTransport invokes onclose from the child-process close event,
      // unlike close(), which may return before its final kill has completed.
      transport.onclose = () => { exitState.ended = true; confirmExit(); };
      this.exitState = exitState;
      this.transport = transport;
      transport.stderr?.on('data', () => {}); // tool errors travel in MCP results; never corrupt protocol stdout
      const client = new Client({ name: 'bbui-pi', version: '0.1.0' });
      await client.connect(transport);
      this.client = client;
      return client;
    })();
    try { return await this.starting; }
    catch (error) { this.connectionFailed = true; await this.transport?.close(); throw error; }
    finally { this.starting = undefined; }
  }

  async listTools() { return (await this.connect()).listTools(); }

  async call(operation: string, params: PhoneParams, signal?: AbortSignal) {
    if (this.recovering) return toolFailure(operation, '宿主正在恢复连接，本次未派发。');
    let finish!: () => void;
    const pending = new Promise<void>(resolve => { finish = resolve; });
    this.pendingCalls.add(pending);
    try { return await this.performCall(operation, params, signal); }
    finally { this.pendingCalls.delete(pending); finish(); }
  }

  async invoke(name: string, args: Record<string, unknown>, readOnly: boolean) {
    if (this.recovering) return toolFailure(name, '连接正在恢复');
    let finish!: () => void;
    const pending = new Promise<void>(resolve => { finish = resolve; });
    this.pendingCalls.add(pending);
    try { return await this.performCall(name, {}, undefined, { name, args, readOnly }); }
    finally { this.pendingCalls.delete(pending); finish(); }
  }

  async settled() { await Promise.all([...this.pendingCalls]); }

  private async performCall(operation: string, params: PhoneParams, signal?: AbortSignal, request?: { name: string; args: Record<string, unknown>; readOnly: boolean }) {
    const readOnly = request?.readOnly ?? isReadOnlyOperation(operation);
    if (this.userStopped && !readOnly) return toolFailure(operation, '用户已停止输入，本次未派发。只读观察仍可用。');
    if (this.inputUncertain && !readOnly) return toolFailure(operation,
      '此前输入的派发结果不确定，本次未派发。可继续只读观察；输入通道需要宿主恢复，禁止自动重放。');
    if (signal?.aborted) return toolFailure(operation, '操作在派发前已取消');
    let client: Client;
    try { client = await this.connect(); }
    catch {
      const result = toolFailure(operation, '执行器连接不可用，未派发本次操作。需要宿主恢复连接。');
      return { ...result, details: { ...result.details, 通道: { 控制: '断开' } } };
    }
    if (signal?.aborted) return toolFailure(operation, '操作在派发前已取消');
    let stopRequest: Promise<void> | undefined;
    const stop = () => { stopRequest = this.setStopped(true); stopRequest.catch(() => {}); };
    signal?.addEventListener('abort', stop, { once: true });
    try {
      // Keep the in-flight request alive to retain its outcome. Never replay it.
      const response = await client.callTool({ name: request?.name ?? 'phone_action', arguments: request?.args ?? { 操作: operation, 参数: params } }, undefined, { timeout: 120000 });
      const content = convertContent(response.content as any[]);
      const first = content.find(x => x.type === 'text');
      let details: any = {};
      if (first?.type === 'text') { try { details = JSON.parse(first.text); } catch {} }
      if (!details || typeof details !== 'object' || Array.isArray(details)) details = {};
      if (response.isError) details = { ...details, 工具错误: true };
      // A tool rejection is an observed outcome, not a transport failure. Preserve
      // every content block so the model can inspect the current image and adapt.
      return { content, details };
    } catch {
      if (!readOnly) {
        this.inputUncertain = true;
        await this.quarantineInput().catch(() => {});
      }
      const result = toolFailure(operation, readOnly
        ? '未取得只读查询结果，可重新查询。'
        : '未取得输入执行结果，派发情况未知。可继续只读观察；输入通道需要宿主恢复，禁止自动重试。', '未知', !['列出应用', '列出屏幕'].includes(operation));
      return { ...result, details: { ...result.details, 通道: { 控制: '断开' } } };
    } finally {
      signal?.removeEventListener('abort', stop);
      await stopRequest?.catch(() => {});
    }
  }

  private async runDirectory() {
    const config = JSON.parse(await readFile(process.env.BBUI_CONFIG_FILE || join(this.root, 'config.local.json'), 'utf8'));
    if (config.devicePlatform === 'ios') {
      const base = join(process.env.LOCALAPPDATA || join(homedir(), '.local', 'share'), 'BBUI', 'devices');
      const identity = 'ios:' + String(config.serial).replace(/-/g, '').toUpperCase();
      return join(base, createHash('sha256').update(identity).digest('hex').slice(0, 24));
    }
    if (!process.env.BBUI_DEVICE_STATE_DIR && this.root !== ROOT) return join(this.root, 'runs', config.serial);
    const base = process.env.BBUI_DEVICE_STATE_DIR || join(process.env.LOCALAPPDATA || join(homedir(), '.local', 'share'), 'BBUI', 'devices');
    return join(base, createHash('sha256').update(config.serial).digest('hex').slice(0, 24));
  }

  private async ownedQuarantine(path: string) {
    let marker: string;
    try { marker = await readFile(path, 'utf8'); }
    catch (error) { if ((error as NodeJS.ErrnoException).code === 'ENOENT') return false; throw error; }
    let owner: unknown;
    try { owner = JSON.parse(marker).owner; } catch {}
    if (owner !== this.owner) throw new Error('输入隔离标记不属于当前桥接实例，未清除。需要原宿主确认旧执行器已退出。');
    return true;
  }

  private async verifyLeaseReleased() {
    const command = pythonRuntime(this.root);
    const script = process.env.BBUI_MCP_MODULE === 'bbui.ios_mcp'
      ? 'from bbui.ios_runtime import verify_lease_released\nverify_lease_released()\nprint("BBUI_LEASE_RELEASED")\n'
      : 'import sys\nfrom bbui.runtime import PhoneTools\np = PhoneTools(sys.argv[1])\nwith p.lock():\n print("BBUI_LEASE_RELEASED")\n';
    const { stdout } = await execFileAsync(command, ['-X', 'utf8', '-c', script, this.root], {
      cwd: this.root, windowsHide: true, timeout: 10000, maxBuffer: 8192,
    });
    if (stdout.trim() !== 'BBUI_LEASE_RELEASED') throw new Error('未确认旧设备连接的租约已释放');
  }

  /** Explicit host command only. Never registered as a model-accessible tool. */
  async confirmReleased() {
    if (!this.exitState?.ended) throw new Error('尚未确认旧执行器进程退出');
    await this.verifyLeaseReleased();
  }

  /** Explicit host command only. Never registered as a model-accessible tool. */
  async recover() {
    if (this.recovering) throw new Error('连接恢复已在进行中');
    this.recovering = true;
    this.inputUncertain = true;
    try {
      const markerPath = join(await this.runDirectory(), 'INPUT_UNCERTAIN');
      await this.ownedQuarantine(markerPath);
      if (this.starting) await bounded(this.starting.catch(() => {}), 10000, '旧连接尚未完成初始化，未恢复');
      if ((this.client || this.transport) && !this.exitState) throw new Error('缺少旧进程退出证据，未清除输入隔离');
      const exitState = this.exitState;
      await bounded(this.close(), 10000, '旧执行器关闭超时，未清除输入隔离');
      if (exitState) {
        await bounded(exitState.promise, 5000, '尚未确认旧执行器进程退出，未清除输入隔离');
        if (!exitState.ended) throw new Error('缺少旧进程退出确认，未清除输入隔离');
      }
      await bounded(Promise.all([...this.pendingCalls]), 10000, '旧调用尚未结束，未清除输入隔离');
      await this.verifyLeaseReleased();
      // Recheck after pending calls settle: a late transport failure may have
      // written the marker while close was waiting. Never remove another owner.
      if (await this.ownedQuarantine(markerPath)) await unlink(markerPath);
      this.connectionFailed = false;
      this.inputUncertain = false;
      const screens = await this.performCall('列出屏幕', {});
      const observation = await this.performCall('查看', { 屏幕会话: 'main', 执行后等待毫秒: 0 });
      if (observation.details.错误 || observation.details.通道?.控制 === '断开' ||
          observation.details.观察?.状态 !== '已取得' || !observation.details.截图编号) {
        throw new Error(observation.details.错误 || '恢复后未取得新画面');
      }
      return { 状态: '旧执行器已退出，设备租约已释放', 用户STOP保持不变: true,
        说明: '已重建连接并只读查询。旧虚拟屏幕不再受新会话管理，需要时明确重新分配；未创建虚拟屏、启动应用或重放输入。手机端之前操作是否最终生效仍以新观察为准。',
        屏幕: screens.details, 观察: observation.details, content: observation.content };
    } finally { this.recovering = false; }
  }

  private async quarantineInput() {
    const dir = await this.runDirectory();
    await mkdir(dir, { recursive: true });
    // Do not replace another owner's quarantine or conflate this with user STOP.
    // Only host recovery that confirms old work has ended may clear this marker.
    await writeFile(join(dir, 'INPUT_UNCERTAIN'), JSON.stringify({ owner: this.owner,
      reason: 'MCP mutation response unavailable', at: Date.now() }), { encoding: 'utf8', flag: 'wx' })
      .catch((error: NodeJS.ErrnoException) => { if (error.code !== 'EEXIST') throw error; });
  }

  async setStopped(stop: boolean) {
    if (stop) this.userStopped = true;
    const dir = await this.runDirectory();
    await mkdir(dir, { recursive: true });
    if (stop) await writeFile(join(dir, 'STOP'), 'Stopped by Pi user/cancellation', 'utf8');
    else await unlink(join(dir, 'STOP')).catch((error: NodeJS.ErrnoException) => { if (error.code !== 'ENOENT') throw error; });
    if (!stop) this.userStopped = false;
  }

  async close() {
    if (this.starting) await this.starting.catch(() => {});
    await this.client?.close();
    await this.transport?.close();
    if (this.exitState) await bounded(this.exitState.promise, 10000, '执行器尚未退出');
    this.client = undefined;
    this.transport = undefined;
  }
}

