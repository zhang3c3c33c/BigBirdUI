// Synthetic local data only. Run: node scripts/benchmark-android-catalog.mjs
import { mkdtemp, stat, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import { SessionManager } from '@earendil-works/pi-coding-agent';
import { SessionCatalog } from '../pi/android/sessions.mjs';

const root = await mkdtemp(path.join(tmpdir(), 'bbui-catalog-benchmark-'));
try {
  const directory = path.join(root, 'sessions');
  const catalog = new SessionCatalog(directory, root);
  const { sessionId } = await catalog.execute({ action: 'create' });
  const file = await catalog.resolve(sessionId);
  const manager = SessionManager.open(file);
  manager.appendMessage({ role: 'user', content: '本地性能样本', timestamp: 1 });
  for (let i = 0; i < 48; i++) {
    manager.appendMessage({ role: 'assistant', content: [{ type: 'text', text: `步骤 ${i} 中文👋` }], timestamp: 2 * i + 2 });
    manager.appendMessage({ role: 'toolResult', toolName: 'phone_action', toolCallId: `call-${i}`,
      content: [{ type: 'image', data: 'a'.repeat(2 * 1024 * 1024), mimeType: 'image/jpeg' }],
      details: { 成功: true, 执行: { 状态: '已派发' } }, isError: false, timestamp: 2 * i + 3 });
  }
  const ms = async fn => { const start = performance.now(); const value = await fn(); return { ms: Math.round((performance.now() - start) * 10) / 10, value }; };
  const previous = await ms(async () => {
    const rows = await SessionManager.listAll(directory);
    return rows.map(item => {
      const session = SessionManager.open(item.path);
      const messages = session.getBranch().filter(entry => entry.type === 'message');
      return { id: item.id, count: messages.length };
    });
  });
  const first = await ms(() => catalog.execute({ action: 'list' }));
  const cached = await ms(() => catalog.execute({ action: 'list' }));
  const history = await catalog.execute({ action: 'history', sessionId });
  const raw = manager.getBranch().filter(entry => entry.type === 'message').map(entry => entry.message);
  console.log(JSON.stringify({ synthetic: true, fileBytes: (await stat(file)).size,
    previousListMs: previous.ms, indexedListMs: first.ms, cachedListMs: cached.ms,
    rawHistoryBytes: Buffer.byteLength(JSON.stringify({ sessionId, messages: raw })),
    displayHistoryBytes: Buffer.byteLength(JSON.stringify(history)), messageCount: history.messages.length }, null, 2));
} finally {
  const resolved = path.resolve(root);
  if (path.dirname(resolved) === path.resolve(tmpdir()) && path.basename(resolved).startsWith('bbui-catalog-benchmark-'))
    await rm(resolved, { recursive: true, force: true });
}
