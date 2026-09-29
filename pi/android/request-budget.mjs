// Application transport budget, not a claimed limit of any API protocol.
export const REQUEST_BUDGET_BYTES = 24 * 1024 * 1024;

/** Last-mile guard also covers Pi summaries, SDK retries and restored sessions. */
export function budgetedFetch(fetchImpl, baseUrl, limit = REQUEST_BUDGET_BYTES) {
  const origin = new URL(baseUrl).origin;
  return async (input, init) => {
    const url = new URL(input instanceof Request ? input.url : String(input));
    if (url.origin === origin) {
      const body = init?.body;
      let bytes = 0;
      if (typeof body === 'string') bytes = Buffer.byteLength(body, 'utf8');
      else if (body instanceof ArrayBuffer) bytes = body.byteLength;
      else if (ArrayBuffer.isView(body)) bytes = body.byteLength;
      else if (body instanceof Blob) bytes = body.size;
      else if (input instanceof Request && body === undefined) bytes = (await input.clone().arrayBuffer()).byteLength;
      else if (body != null) throw new Error('模型请求使用了未支持的请求体类型，无法校验大小');
      if (bytes > limit) {
        // Return a non-retryable API response. Throwing would become a retryable
        // SDK connection error. No request or phone operation has been sent.
        return new Response(JSON.stringify({ error: { type: 'invalid_request_error',
          code: 'bbui_request_budget', message: `大鸟手机助手本地请求预算超限（${bytes} / ${limit} 字节）。已保留历史与执行结果；未发送本次模型请求，禁止重放手机动作。` } }),
        { status: 413, headers: { 'content-type': 'application/json', 'x-bbui-local-error': 'request-budget' } });
      }
    }
    return fetchImpl(input, init);
  };
}
