import { isReadOnlyOperation, toolFailure } from '../phone-contract.js';

export type AndroidPhoneResult = {
  content: ({ type: 'text'; text: string } | { type: 'image'; data: string; mimeType: string })[];
  details: Record<string, unknown>;
};

export class AndroidPhoneBridge {
  // A response timeout does not prove the native request has finished. Only a
  // host-led runtime restart/reconnect can release this mutation quarantine.
  private inputUncertain = false;
  private userStopped = false;
  constructor(private readonly baseUrl: string, private readonly token: string) {
    const url = new URL(baseUrl);
    if (url.protocol !== 'http:' || !['127.0.0.1', '[::1]', 'localhost'].includes(url.hostname)) {
      throw new Error('Android phone bridge must use a loopback URL');
    }
    if (!token) throw new Error('Missing Android bridge token');
  }

  async setStopped(stopped: boolean, source: 'user' | 'transport' = 'user'): Promise<void> {
    if (source === 'user' && stopped) this.userStopped = true;
    const response = await fetch(`${this.baseUrl}/stop`, {
      method: 'POST', headers: this.headers(), body: JSON.stringify({ stopped, source }),
      signal: AbortSignal.timeout(5000),
    });
    if (!response.ok) throw new Error(`Android STOP failed (${response.status})`);
    if (source === 'user' && !stopped) this.userStopped = false;
  }

  private headers() {
    // NanoHTTPD requires an explicit charset for the Chinese action schema.
    return { 'Content-Type': 'application/json; charset=utf-8', Authorization: `Bearer ${this.token}` };
  }

  async call(operation: string, params: Record<string, unknown>, signal?: AbortSignal): Promise<AndroidPhoneResult> {
    const readOnly = isReadOnlyOperation(operation);
    if (this.userStopped && !readOnly) return toolFailure(operation, '用户已停止输入，本次未派发。只读观察仍可用。');
    if (this.inputUncertain && !readOnly) return toolFailure(operation,
      '此前输入的派发结果不确定，本次未派发。可继续只读观察；输入通道需要宿主恢复，禁止自动重放。');
    if (signal?.aborted) return toolFailure(operation, '操作在派发前已取消');
    let stopping: Promise<void> | undefined;
    const stop = () => { stopping = this.setStopped(true); stopping.catch(() => {}); };
    signal?.addEventListener('abort', stop, { once: true });
    try {
      // Never abort/retry an already dispatched action. Send user STOP separately
      // and retain its final outcome, including images in a rejected result.
      const response = await fetch(`${this.baseUrl}/action`, {
        method: 'POST', headers: this.headers(),
        body: JSON.stringify({ 操作: operation, 参数: params }),
        signal: AbortSignal.timeout(120000),
      });
      const data = await response.json() as AndroidPhoneResult & { error?: string; isError?: boolean };
      if (Array.isArray(data.content) && data.details && typeof data.details === 'object') {
        // isError describes this invocation; it does not invalidate the channel.
        if (data.isError) data.details = { ...data.details, 工具错误: true };
        return { content: data.content, details: data.details };
      }
      if (!response.ok && response.status < 500) {
        return toolFailure(operation, data.error ?? `请求被拒绝 (${response.status})`);
      }
      throw new Error('无法取得完整的执行器结果');
    } catch {
      if (!readOnly) {
        this.inputUncertain = true;
        await this.setStopped(true, 'transport').catch(() => {});
      }
      return toolFailure(operation, readOnly
        ? '未取得只读查询结果，可重新查询。'
        : '未取得输入执行结果，派发情况未知。可继续只读观察；输入通道需要宿主恢复，禁止自动重试。', '未知', !['列出应用', '列出屏幕'].includes(operation));
    } finally {
      signal?.removeEventListener('abort', stop);
      // Failure to acknowledge STOP must not erase the action outcome. The host
      // also owns the cancellation state; the caller signal remains aborted.
      await stopping?.catch(() => {});
    }
  }
}
