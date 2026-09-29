// Limit provider response bodies before JSON decoding; cancellation retains its signal.
export async function boundedSearchFetch(url, init, fetchImpl = fetch) {
  const response = await fetchImpl(url, init);
  const limit = 2 * 1024 * 1024;
  if (Number(response.headers.get('content-length')) > limit) {
    await response.body?.cancel(); throw new Error('搜索响应超过 2 MiB 限制');
  }
  if (!response.body) return response;
  const reader = response.body.getReader(); const chunks = []; let size = 0;
  try {
    while (true) {
      init.signal?.throwIfAborted();
      const { done, value } = await reader.read(); if (done) break;
      size += value.byteLength;
      if (size > limit) { await reader.cancel(); throw new Error('搜索响应超过 2 MiB 限制'); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  return new Response(Buffer.concat(chunks), { status: response.status, statusText: response.statusText, headers: response.headers });
}
