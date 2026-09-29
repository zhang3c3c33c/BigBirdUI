import { mkdir, readFile, writeFile, rename } from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';

export async function atomicJson(file, data) {
  await mkdir(path.dirname(file), { recursive: true });
  const temp = file + '.' + randomUUID() + '.tmp';
  await writeFile(temp, JSON.stringify(data), { mode: 0o600 });
  await rename(temp, file);
}
export async function readJson(file, fallback) {
  try { return JSON.parse(await readFile(file, 'utf8')); }
  catch (error) { if (error.code === 'ENOENT') return fallback; throw error; }
}
export function deviceIdentity(settings) {
  const platform = settings.devicePlatform || 'android';
  const serial = platform === 'ios' ? (settings.serial || '').replace(/-/g, '').toUpperCase() : (settings.serial || '');
  return `${platform}:${serial}`;
}
export function restoreState(saved = {}) {
  if (saved.version !== undefined && saved.version !== 1) throw new Error('此数据版本需要更新的大鸟手机助手，未改写原数据');
  return { version: 1, selected: saved.selected || '', views: saved.views || {}, selections: saved.selections || {},
    queue: [], paused: true, pauseReasons: [...new Set(['restart', ...(saved.pauseReasons || (saved.paused ? ['user'] : []))])],
    submissions: saved.submissions || {}, titleGeneration: saved.titleGeneration || {},
    questions: (saved.questions || []).map(q => q.status === 'pending' ? { ...q, status: 'interrupted' } : q),
    interrupted: [...(saved.interrupted || []), ...(saved.active ? [{ ...saved.active, interruptedReason: 'running' }] : []),
      ...(saved.queue || []).map(task => ({ ...task, interruptedReason: 'queued' }))] };
}
export function bindSubmission(state, settings, command) {
  if (!command.text?.trim() || !command.submissionId || !command.sessionId) throw new Error('缺少任务内容或会话');
  if ([...state.queue, ...state.interrupted, ...(state.active ? [state.active] : [])].some(item => item.id === command.submissionId)) throw new Error('任务已经提交');
  const selection = state.selections[command.sessionId] || settings.defaultModel;
  const connection = settings.connections.find(item => item.id === selection?.connectionId);
  if (!connection?.models?.some(model => model.id === selection?.modelId) || !connection.baseUrl?.trim() || !connection.apiKey?.trim()) throw new Error('请先配置模型');
  return { id: command.submissionId, sessionId: command.sessionId, text: command.text.trim(),
    configRevision: settings.revision, deviceId: deviceIdentity(settings), selection: structuredClone(selection) };
}
export function validateAnswer(question, command) {
  if (!question || question.status !== 'pending' || question.requestId !== command.requestId ||
      question.runId !== command.runId || question.sessionId !== command.sessionId) throw new Error('提问已过期或已回答');
  if (command.cancelled) return;
  const answers = command.answers;
  if (!Array.isArray(answers) || answers.length !== question.questions.length) throw new Error('请回答所有问题');
  for (const item of question.questions) {
    const matches = answers.filter(answer => answer.questionId === item.id);
    const answer = matches[0];
    if (matches.length !== 1 || !Array.isArray(answer.selected) || new Set(answer.selected).size !== answer.selected.length ||
      answer.selected.some(label => !item.options.some(option => option.label === label)) ||
      (!item.multiSelect && answer.selected.length > 1) || typeof answer.text !== 'string' || answer.text.length > 10000 ||
      (!answer.selected.length && !answer.text.trim())) throw new Error('回答格式不正确');
  }
}
