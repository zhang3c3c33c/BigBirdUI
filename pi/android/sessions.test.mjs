import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { SessionManager } from '@earendil-works/pi-coding-agent';
import { SessionCatalog } from './sessions.mjs';

test('Pi catalog: empty sessions persist, independent history, rename and confined deletion', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'bbui-sessions-'));
  const catalog = new SessionCatalog(path.join(root, 'sessions'), root);
  const a = await catalog.execute({ action: 'create' });
  const b = await catalog.execute({ action: 'create' });
  assert.notEqual(a.sessionId, b.sessionId);
  assert.equal((await catalog.execute({ action: 'list' })).sessions.length, 2);
  const file = await catalog.resolve(a.sessionId);
  const manager = SessionManager.open(file);
  manager.appendMessage({ role: 'user', content: '第一条中文 👋', timestamp: 1 });
  await catalog.execute({ action: 'rename', sessionId: a.sessionId, title: '购物' });
  assert.equal((await catalog.execute({ action: 'list' })).sessions.find(s => s.id === a.sessionId).title, '购物');
  assert.equal((await catalog.execute({ action: 'history', sessionId: a.sessionId })).messages[0].content, '第一条中文 👋');
  assert.deepEqual((await catalog.execute({ action: 'history', sessionId: b.sessionId })).messages, []);
  await assert.rejects(catalog.execute({ action: 'delete', sessionId: '../other' }));
  await catalog.execute({ action: 'delete', sessionId: a.sessionId });
  await assert.rejects(readFile(file));
  assert.equal((await catalog.execute({ action: 'list' })).sessions.length, 1);
});

test('imports legacy sessions with a different Android cwd alias', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'bbui-legacy-sessions-'));
  const directory = path.join(root, 'sessions');
  const old = new SessionCatalog(directory, path.join(root, 'old-workspace'));
  const { sessionId } = await old.execute({ action: 'create' });
  const file = await old.resolve(sessionId);
  SessionManager.open(file).appendMessage({ role: 'user', content: '旧会话', timestamp: 1 });
  const before = await readFile(file);
  const current = new SessionCatalog(directory, path.join(root, 'new-workspace'));
  assert.equal((await current.execute({ action: 'list' })).sessions[0].id, sessionId);
  assert.equal((await current.execute({ action: 'history', sessionId })).messages[0].content, '旧会话');
  assert.deepEqual(await readFile(file), before);
});

test('catalog history projects large image results before serialization and leaves Pi history intact', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'bbui-image-history-'));
  const catalog = new SessionCatalog(path.join(root, 'sessions'), root);
  const { sessionId } = await catalog.execute({ action: 'create' });
  const file = await catalog.resolve(sessionId);
  const manager = SessionManager.open(file);
  manager.appendMessage({ role: 'user', content: '查看屏幕', timestamp: 1 });
  manager.appendMessage({ role: 'assistant', content: [{ type: 'thinking', thinking: '真实思考' },
    { type: 'text', text: '正文👋' }], timestamp: 2 });
  manager.appendMessage({ role: 'toolResult', toolName: 'phone_action', toolCallId: 'one',
    content: [{ type: 'image', data: 'a'.repeat(12 * 1024 * 1024), mimeType: 'image/jpeg' }],
    details: { 成功: true, 执行: { 状态: '已派发' } }, isError: false, timestamp: 3 });
  const before = await readFile(file);
  const history = await catalog.execute({ action: 'history', sessionId });
  assert.equal(history.messages.length, 3);
  assert.ok(JSON.stringify(history).length < 1000);
  assert.equal(history.messages[1].content[0].thinking, '真实思考');
  assert.deepEqual(history.messages[2].content, []);
  assert.deepEqual(await readFile(file), before);
});

test('catalog reuses the Pi index until files change, without opening every full session', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'bbui-index-cache-'));
  const catalog = new SessionCatalog(path.join(root, 'sessions'), root);
  const { sessionId } = await catalog.execute({ action: 'create' });
  const originalList = SessionManager.listAll;
  const originalOpen = SessionManager.open;
  let scans = 0;
  SessionManager.listAll = async (...args) => { scans++; return originalList.apply(SessionManager, args); };
  try {
    SessionManager.open = () => { throw new Error('Listing must use Pi index metadata'); };
    await catalog.execute({ action: 'list' });
    await catalog.execute({ action: 'list' });
    const file = await catalog.resolve(sessionId);
    assert.equal(scans, 1);
    SessionManager.open = originalOpen;
    const manager = SessionManager.open(file);
    manager.appendMessage({ role: 'user', content: '外部写入', timestamp: 1 });
    manager.appendSessionInfo('外部改名');
    const updated = await catalog.execute({ action: 'list' });
    assert.equal(updated.sessions[0].title, '外部改名');
    assert.equal(scans, 2);
    await catalog.execute({ action: 'delete', sessionId });
    assert.equal((await catalog.execute({ action: 'list' })).sessions.length, 0);
    await assert.rejects(catalog.resolve(sessionId));
  } finally { SessionManager.listAll = originalList; SessionManager.open = originalOpen; }
});
