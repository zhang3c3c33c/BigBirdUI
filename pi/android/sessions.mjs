import { mkdir, open, realpath, unlink, readdir, stat } from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { PassThrough } from 'node:stream';
import { SessionManager } from '@earendil-works/pi-coding-agent';
import { displayMessage } from './display-projection.mjs';
import { generateSessionTitle } from './session-title.mjs';
// Reuse the pinned Pi reader: Node readline also splits valid JSON string separators.
const { attachJsonlLineReader } = await import(new URL('./modes/rpc/jsonl.js', import.meta.resolve('@earendil-works/pi-coding-agent')));

// Pi owns the file format, branches and migration. Only opaque IDs cross the UI bridge.
export class SessionCatalog {
  constructor(directory, cwd) { this.directory = directory; this.cwd = cwd; this.signature = null; this.index = []; }
  async list() {
    await mkdir(this.directory, { recursive: true });
    const names = (await readdir(this.directory)).filter(name => name.endsWith('.jsonl')).sort();
    const signature = JSON.stringify(await Promise.all(names.map(async name => {
      const value = await stat(path.join(this.directory, name)).catch(() => null);
      return [name, value?.size, value?.mtimeMs, value?.ctimeMs];
    })));
    if (signature === this.signature) return this.index;
    // Android's /data/data and /data/user/0 aliases can change between releases.
    // This private directory belongs to one app, so do not filter legacy cwd strings.
    this.index = (await SessionManager.listAll(this.directory)).map(item => ({
      id: item.id, path: item.path, created: item.created, modified: item.modified,
      title: item.name || (item.firstMessage === '(no messages)' ? '新会话' : Array.from(item.firstMessage).slice(0, 24).join('')),
    }));
    // Do not retain Pi's allMessagesText or a full SessionManager in this index.
    this.signature = signature;
    return this.index;
  }
  async resolve(id) {
    const item = (await this.list()).find(item => item.id === id);
    if (!item) throw new Error('会话不存在或已删除');
    const root = await realpath(this.directory);
    const file = await realpath(item.path);
    if (path.dirname(file) !== root) throw new Error('会话路径不在私有目录内');
    return file;
  }
  async execute(command) {
    if (command.action === 'list') return { sessions: (await this.list()).map(item => ({
      id: item.id, title: item.title, modified: item.modified.getTime(), created: item.created.getTime(),
    })) };
    if (command.action === 'create') {
      await mkdir(this.directory, { recursive: true });
      const file = path.join(this.directory, `${Date.now()}_${randomUUID()}.jsonl`);
      // Pi's public open API initializes an empty file, including its canonical header.
      const handle = await open(file, 'wx'); await handle.close();
      const manager = SessionManager.open(file, this.directory, this.cwd);
      this.signature = null;
      return { sessionId: manager.getSessionId() };
    }
    const file = await this.resolve(command.sessionId);
    if (command.action === 'delete') { await unlink(file); this.signature = null; return {}; }
    const manager = SessionManager.open(file, this.directory, this.cwd);
    if (command.action === 'rename') {
      const name = String(command.title || '').trim();
      if (!name || name.length > 200) throw new Error('会话标题需为 1～200 个字符');
      manager.appendSessionInfo(name);
      this.signature = null;
      return {};
    }
    if (command.action === 'history') {
      // Display the complete active branch, including messages preceding compaction.
      const messages = manager.getBranch().filter(entry => entry.type === 'message').map(entry => entry.message);
      return { sessionId: manager.getSessionId(), messages: messages.map(displayMessage) };
    }
    throw new Error('不支持的会话操作');
  }
}

export function installSessionRouter(catalog, { catalogOnly = false, sessionId = '', memoryCommand, titleCommand = generateSessionTitle } = {}) {
  const originalInput = process.stdin;
  // The shipped rpc-entry bundles its own output-guard module instance. Capture
  // the timestamped protocol writer before that bundle redirects ordinary stdout.
  const protocolWrite = process.stdout.write.bind(process.stdout);
  const input = new PassThrough();
  Object.defineProperty(process, 'stdin', { configurable: true, value: input });
  let serial = Promise.resolve();
  const reply = (command, data, error) => new Promise((resolve, reject) => protocolWrite(JSON.stringify({
    type: 'response', id: command.id, command: command.type, success: !error,
    ...(error ? { error: error.message } : { data }),
  }) + '\n', failure => failure ? reject(failure) : resolve()));
  attachJsonlLineReader(originalInput, line => {
    let command;
    try { command = JSON.parse(line); } catch { input.write(line + '\n'); return; }
    if (command.type === 'bbui_title') {
      // Naming must not queue behind, or block, session/catalogue operations.
      // Hosts apply the result only if the session is still eligible for naming.
      void Promise.resolve().then(() => titleCommand(command.text, command.config))
        .then(title => reply(command, { title }), () => reply(command, { title: null }))
        .catch(() => {});
    } else if (command.type === 'bbui_memory') {
      serial = serial.then(async () => {
        try {
          if (!memoryCommand) throw new Error('记忆管理不可用');
          await reply(command, await memoryCommand(command));
        } catch (error) { await reply(command, null, error); }
      });
    } else if (command.type === 'bbui_sessions') {
      // Mutating the live manager behind Pi would lose its leaf/name state.
      if (!catalogOnly && command.action === 'rename' && command.sessionId === sessionId) {
        input.write(JSON.stringify({ id: command.id, type: 'set_session_name', name: command.title }) + '\n');
      } else {
        serial = serial.then(async () => {
          try { await reply(command, await catalog.execute(command)); }
          catch (error) { await reply(command, null, error); }
        });
      }
    } else if (catalogOnly) {
      if (command.type === 'get_state') reply(command, { sessionId: '', isStreaming: false });
      else if (command.type === 'get_messages') reply(command, { messages: [] });
      else reply(command, null, new Error('会话管理模式不执行任务'));
    } else input.write(line + '\n');
  });
  originalInput.on('end', () => { void serial.finally(() => input.end()); });
  return input;
}
