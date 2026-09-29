import { boundedSearchFetch } from './bounded-http.mjs';
// Qianfan AI Search HTTP API. Normalized to pi-web-access SearchResponse.
export async function searchWithBaidu(query, { apiKey, numResults = 8, signal, fetchImpl = fetch } = {}) {
  if (!apiKey) throw new Error('请在设置中配置百度 AI 搜索密钥');
  signal?.throwIfAborted();
  const response = await boundedSearchFetch('https://qianfan.baidubce.com/v2/ai_search/web_search', {
    method: 'POST', headers: { Authorization: `Bearer ${apiKey}`, 'X-Appbuilder-Authorization': `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ messages: [{ role: 'user', content: query }], search_source: 'baidu_search_v2',
      resource_type_filter: [{ type: 'web', top_k: numResults }] }),
    signal: AbortSignal.any([AbortSignal.timeout(60000), ...(signal ? [signal] : [])]),
  }, fetchImpl);
  const redact = value => String(value).split(apiKey).join('[redacted]').slice(0, 500);
  if (!response.ok) throw new Error(`百度 AI 搜索 HTTP ${response.status}: ${redact(await response.text())}`);
  const data = await response.json();
  if (data.error_code || data.error || !Array.isArray(data.references)) throw new Error(`百度 AI 搜索返回错误: ${redact(data.error_msg || data.message || JSON.stringify(data.error || data))}`);
  const results = data.references.filter(item => typeof item.url === 'string').slice(0, numResults)
    .map(item => ({ title: item.title || item.url, url: item.url, snippet: item.content || '' }));
  return { answer: results.map((item, index) => `[${index + 1}] ${item.title}\n${item.url}\n${item.snippet}`).join('\n\n'), results };
}
