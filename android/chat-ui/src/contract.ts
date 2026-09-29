import type { ThreadMessageLike } from '@assistant-ui/react';

export type ChatPart = {
  id: string;
  type: 'text' | 'reasoning' | 'tool';
  state: 'streaming' | 'running' | 'complete' | 'stopped' | 'error';
  text?: string;
  durationMs?: number;
  toolCallId?: string;
  questionRequestId?: string;
  toolName?: string;
  title?: string;
  summary?: string;
  sources?: Array<{ title: string; url: string }>;
  error?: string;
  executionState?: '未派发' | '已派发' | '部分派发' | '未知' | '无需派发';
  observationState?: '已取得' | '失败' | '未请求';
  taskAction?: 'read' | 'update';
};

export type UserQuestion = {
  id: string;
  header: string;
  question: string;
  options: Array<{ label: string; description?: string }>;
  multiSelect?: boolean;
};
export type QuestionAnswer = { questionId: string; selected: string[]; text: string };
export const QUESTION_TEXT_LIMIT = 10000;
export type PendingQuestion = {
  requestId: string;
  sessionId: string;
  runId: string;
  toolCallId: string;
  questions: UserQuestion[];
  status: 'pending' | 'answered' | 'cancelled' | 'interrupted';
  draft?: QuestionAnswer[];
  answers?: QuestionAnswer[];
  messageSourceKey?: string;
};

export function questionAnswers(question: PendingQuestion, draft = question.status === 'answered' ? question.answers ?? question.draft : question.draft): QuestionAnswer[] {
  return question.questions.map(item => {
    const saved = draft?.find(answer => answer.questionId === item.id);
    const labels = new Set(item.options.map(option => option.label));
    const selected = [...new Set(saved?.selected ?? [])].filter(label => labels.has(label));
    let text = (saved?.text ?? '').slice(0, QUESTION_TEXT_LIMIT);
    // Do not restore a draft ending with half of an emoji at the native limit.
    if (text.length && /[\uD800-\uDBFF]/.test(text.at(-1)!)) text = text.slice(0, -1);
    return { questionId: item.id, selected: item.multiSelect ? selected : selected.slice(0, 1), text };
  });
}
export function questionComplete(question: PendingQuestion, answers: QuestionAnswer[]): boolean {
  return question.questions.length > 0 && question.questions.length <= 4 &&
    questionAnswers(question, answers).every(answer => answer.selected.length > 0 || !!answer.text.trim());
}

const toolTitles: Record<string, string> = {
  read: '读取手机操作指南',
  phone_action: '操作手机', phone_history: '读取操作记录',
  system_apps: '管理应用', system_notifications: '查看通知',
  system_clipboard: '操作剪贴板', system_files: '操作共享文件', system_calendar: '管理日程',
  system_contacts: '管理联系人', system_sms: '读取短信', system_call_log: '查询通话记录',
  system_media: '检索媒体', system_clock: '闹钟与倒计时',
  web_search: '搜索网页', fetch_content: '读取网页', get_search_content: '读取搜索结果',
  get_web_search_result: '读取搜索结果',
  memory_write: '更新记忆', memory_read: '读取记忆', memory_forget: '删除记忆',
  memory_restore: '恢复记忆', memory_status: '查看记忆状态', scratchpad: '更新记事',
  ask_user_question: '向你提问',
};
export function toolTitle(part: ChatPart): string {
  return (part.title?.trim() || (part.toolName === 'task_state'
    ? part.taskAction === 'update' ? '更新任务记录' : '读取任务记录'
    : toolTitles[part.toolName ?? ''] || '调用工具')).slice(0, 200);
}

// Only the bounded native display projection reaches this component. Never
// render raw result JSON or fetch a source URL automatically.
export function toolSources(part: ChatPart): Array<{ title: string; url: string }> {
  return (part.sources ?? []).slice(0, 10).flatMap(source => {
    try {
      const url = new URL(source.url);
      if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return [];
      return [{ title: (source.title || url.hostname).slice(0, 200), url: url.href }];
    } catch { return []; }
  });
}

export type TaskState = {
  version: 1;
  id: string;
  goal: string;
  status: 'active' | 'waiting_user' | 'completed' | 'blocked';
  summary: string;
  constraints?: Array<{ text: string; source: 'user' | 'preference' | 'assumption' }>;
  facts?: string[];
  unknowns?: Array<{ text: string; resolveBy: 'observe' | 'user' }>;
  steps?: Array<{ id: string; title: string; status: 'pending' | 'in_progress' | 'completed' }>;
  question?: string;
  completionEvidence?: string;
};
export type TaskDisplay = { state: 'idle' | 'running' | 'active' | 'unconfirmed' | 'waiting_user' | 'completed' | 'blocked' | 'stopped' | 'error'; label: string };

// Transport completion is separate from dispatch and observation facts. None
// of these labels claims that the user's business goal has been achieved.
export function toolPresentation(part: ChatPart) {
  const running = part.state === 'streaming' || part.state === 'running';
  if (part.toolName === 'task_state') return {
    running, label: running ? '记录中' : part.state === 'error' ? '记录失败'
      : part.state === 'stopped' ? '已停止' : part.taskAction === 'update' ? '已更新' : '已读取',
    tone: part.state === 'error' ? 'error' : 'neutral', facts: '',
  };
  const dispatch = part.executionState === '未派发' ? '未执行'
    : part.executionState === '未知' ? '结果待确认'
      : part.executionState === '无需派发' ? '' : part.executionState;
  const observation = part.observationState === '已取得' ? '已观察'
    : part.observationState === '失败' ? '观察失败' : '';
  const facts = [dispatch, observation].filter(Boolean).join(' · ');
  const label = running ? '执行中' : facts || (part.state === 'error' ? '调用失败'
    : part.state === 'stopped' ? '已停止' : '已返回');
  const tone = part.state === 'error' ? 'error'
    : part.executionState === '未派发' || part.executionState === '部分派发' ||
      part.executionState === '未知' || part.observationState === '失败' ? 'uncertain' : 'neutral';
  return { running, label, tone, facts };
}
export type ChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  status: 'streaming' | 'complete' | 'stopped' | 'error';
  parts: ChatPart[];
};
export type ModelSelection = { connectionId: string; modelId: string; thinkingLevel: string };
export type ModelOption = {
  connectionId: string; connectionName: string; modelId: string; modelName: string;
  thinkingLevels: Array<{ id: string; label: string }>; defaultThinkingLevel: string;
};
export type Snapshot = {
  capabilities?: { platform: 'android' | 'windows' | 'macos'; primaryModifier?: 'Control' | 'Meta'; embeddedPreview: boolean; multipleScreens: boolean; systemTools: boolean;
    devicePlatform?: 'android' | 'ios'; phoneOperations?: string[]; phoneKeys?: string[];
    systemOperations?: Record<string, string[]>; appRestrictions?: boolean;
    previewMode?: 'scrcpy' | 'screenshots'; previewFps?: number };
  desktop?: { connected: boolean; configured: boolean; devicePlatform?: 'android' | 'ios'; deviceId?: string; deviceName?: string;
    connectionStatus?: { code: string; message: string };
    screens: Array<{ 屏幕会话: string; 显示屏编号: number; external?: boolean; readOnly?: boolean; label?: string }> };
  type: 'snapshot';
  revision: number;
  stateId?: string;
  runId: string;
  sessionId: string;
  status: { phase: string; message: string };
  isRunning: boolean;
  task?: TaskState | null;
  taskUpdatedThisRun?: boolean;
  taskDisplay?: TaskDisplay;
  messages: ChatMessage[];
  hasOlder: boolean;
  timing: { nativeReceivedAtMs: number; projectionAtMs: number };
  sessionsReady?: boolean;
  sessions?: { id: string; title: string; modified: number; running: boolean; queued: number; state: string; pinned?: boolean }[];
  runningSessionId?: string;
  control?: {
    id: string;
    mode: 'idle' | 'running' | 'taking_over' | 'manual' | 'resuming' | 'stopped' | 'error';
    sessionId: string;
    canResume: boolean;
    canSteer: boolean;
  };
  queuePauseReasons?: string[];
  submissionResult?: { id: string; sessionId: string; accepted: boolean };
  modelOptions?: ModelOption[];
  modelSelection?: ModelSelection;
  modelSelectionResult?: { id: string; sessionId: string; accepted: boolean; error?: string };
  sessionError?: string;
  queuePaused?: boolean;
  queue?: { id: string; sessionId: string; text: string }[];
  viewState?: { text: string; scrollTop: number };
  focusQuestion?: { id: string; sessionId: string; requestId: string };
  interruptedTasks?: { id: string; sessionId: string; text: string; interruptedReason?: string; steeringStatus?: 'not_sent' | 'unconfirmed' | 'accepted' }[];
  pendingQuestion?: PendingQuestion | null;
  questionHistory?: PendingQuestion[];
  questionResult?: { requestId: string; sessionId: string; runId: string; revision?: number; accepted: boolean; error?: string };
};

export type QuestionSnapshot = Pick<Snapshot, 'isRunning' | 'sessionId' | 'runningSessionId' | 'runId' | 'questionResult'>;
export function canAnswerQuestion(snapshot: QuestionSnapshot, question: PendingQuestion): boolean {
  return question.status === 'pending' && snapshot.isRunning &&
    question.sessionId === snapshot.sessionId && question.sessionId === snapshot.runningSessionId &&
    question.runId === snapshot.runId && !!question.requestId && !!question.toolCallId;
}

const questionPartKey = (part: ChatPart) => JSON.stringify([part.id, part.toolCallId || part.id, part.questionRequestId ?? '']);
export function indexQuestions(snapshot: Snapshot) {
  const questions = [...new Map([...(snapshot.questionHistory ?? []), snapshot.pendingQuestion]
    .filter((item): item is PendingQuestion => !!item && item.sessionId === snapshot.sessionId)
    .map(item => [item.requestId, item])).values()];
  const byRequest = new Map(questions.map(question => [question.requestId, question]));
  const byCall = new Map<string, PendingQuestion[]>();
  for (const question of questions) byCall.set(question.toolCallId, [...(byCall.get(question.toolCallId) ?? []), question]);
  const calls = snapshot.messages.flatMap(message => message.parts).filter(part => part.type === 'tool');
  const counts = new Map<string, number>();
  for (const part of calls) { const callId = part.toolCallId || part.id; counts.set(callId, (counts.get(callId) ?? 0) + 1); }
  const bindings = new Map<string, PendingQuestion>();
  const boundRequests = new Set<string>();
  for (const part of calls) {
    const callId = part.toolCallId || part.id;
    const candidates = byCall.get(callId) ?? [];
    const question = part.questionRequestId ? byRequest.get(part.questionRequestId)
      : candidates.length === 1 && counts.get(callId) === 1 && !candidates[0].messageSourceKey ? candidates[0] : undefined;
    if (question?.toolCallId === callId && !boundRequests.has(question.requestId)) {
      bindings.set(questionPartKey(part), question); boundRequests.add(question.requestId);
    }
  }
  const pending = snapshot.pendingQuestion;
  return {
    forPart: (part: ChatPart) => bindings.get(questionPartKey(part)),
    fallback: pending && canAnswerQuestion(snapshot, pending) && !boundRequests.has(pending.requestId) ? pending : undefined,
  };
}
export function questionForPart(snapshot: Snapshot, part: ChatPart): PendingQuestion | undefined {
  return indexQuestions(snapshot).forPart(part);
}

export function taskPresentation(snapshot: Snapshot): TaskDisplay {
  if (snapshot.taskDisplay) return snapshot.taskDisplay;
  // Legacy snapshots have no task declaration. Ending a reply is not proof of
  // completing the task, and simply retaining a record is not a new update.
  if (snapshot.status.phase === 'error') return { state: 'error', label: '回复出错 · 已保留内容' };
  if (['stopping', 'stopped', 'manual'].includes(snapshot.status.phase)) return { state: 'stopped', label: '已停止 · 已保留内容' };
  if (snapshot.isRunning) return { state: 'running', label: '进行中' };
  return snapshot.messages.length || snapshot.task ? { state: 'unconfirmed', label: '本轮回复结束 · 任务状态未确认' }
    : { state: 'idle', label: '准备开始' };
}
export type Command =
  | { type: 'send' | 'steer'; text: string; sessionId: string; submissionId: string }
  | { type: 'steerQueued'; sessionId: string; submissionId: string; controlId: string; runId: string }
  | { type: 'takeOver' | 'resumeTask' | 'endManual'; controlId: string }
  | { type: 'selectSession' | 'deleteSession' | 'loadOlder'; sessionId: string }
  | { type: 'renameSession'; sessionId: string; title: string }
  | { type: 'pinSession'; sessionId: string; pinned: boolean }
  | { type: 'viewState'; sessionId: string; text: string; scrollTop: number }
  | { type: 'cancelQueued'; submissionId: string }
  | { type: 'resumeInterrupted'; submissionId: string }
  | { type: 'newSession' | 'refreshSessions' | 'pauseQueue' | 'resumeQueue' }
  | { type: 'rendered'; revision: number }
  | { type: 'questionFocused'; id: string; sessionId: string; requestId: string }
  | { type: 'selectModel'; sessionId: string; requestId: string; connectionId: string; modelId: string }
  | { type: 'setThinkingLevel'; sessionId: string; requestId: string; thinkingLevel: string }
  | { type: 'answerQuestion'; sessionId: string; runId: string; requestId: string; answers: QuestionAnswer[]; cancelled?: boolean }
  | { type: 'questionDraft'; sessionId: string; runId: string; requestId: string; answers: QuestionAnswer[] }
  | { type: 'openSettings'; page?: 'models' }
  | { type: 'ready' | 'stop' | 'openPreview' };

declare global {
  interface Window { BBUI?: { postMessage(message: string): void } }
}

export const initialSnapshot: Snapshot = {
  type: 'snapshot', revision: -1, runId: '', sessionId: '',
  status: { phase: 'connecting', message: '正在连接手机助手' },
  isRunning: false, messages: [], hasOlder: false,
  timing: { nativeReceivedAtMs: 0, projectionAtMs: 0 },
};

export function post(command: Command): void {
  window.BBUI?.postMessage(JSON.stringify(command));
}

// Keep message references stable when an unrelated chunk arrives. The runtime caches
// conversions by object identity; snapshots remain the sole authority, not a delta log.
export function acceptSnapshot(previous: Snapshot, next: Snapshot): Snapshot {
  if (next.type !== 'snapshot' || !Number.isFinite(next.revision)) return previous;
  if (next.stateId && next.stateId === previous.stateId && next.revision < previous.revision) return previous;
  if ((!next.stateId || next.stateId === previous.stateId) && next.sessionId === previous.sessionId && (next.revision < previous.revision ||
    (next.revision === previous.revision && next.messages.length <= previous.messages.length && next.hasOlder === previous.hasOlder))) return previous;
  const previousById = new Map(previous.messages.map((message) => [message.id, message]));
  const seen = new Set<string>();
  const messages = next.messages.filter((message) => {
    if (seen.has(message.id)) return false;
    seen.add(message.id);
    return true;
  }).map((message) => {
    const existing = previousById.get(message.id);
    return existing && JSON.stringify(existing) === JSON.stringify(message) ? existing : message;
  });
  const sameSession = next.sessionId === previous.sessionId;
  const stable = <T,>(old: T, value: T): T => sameSession && JSON.stringify(old) === JSON.stringify(value) ? old : value;
  return { ...next,
    messages: sameSession && messages.length === previous.messages.length && messages.every((message, index) => message === previous.messages[index]) ? previous.messages : messages,
    pendingQuestion: stable(previous.pendingQuestion, next.pendingQuestion),
    questionHistory: stable(previous.questionHistory, next.questionHistory),
    questionResult: stable(previous.questionResult, next.questionResult),
  };
}

function partStatus(state: ChatPart['state']) {
  if (state === 'streaming' || state === 'running') return { type: 'running' } as const;
  if (state === 'error') return { type: 'incomplete', reason: 'error' } as const;
  if (state === 'stopped') return { type: 'incomplete', reason: 'cancelled' } as const;
  return { type: 'complete' } as const;
}

export function convertMessage(message: ChatMessage): ThreadMessageLike {
  return {
    id: message.id,
    role: message.role,
    ...(message.role === 'assistant' ? {
      status: message.status === 'streaming' ? { type: 'running' } as const
        : message.status === 'complete' ? { type: 'complete', reason: 'stop' } as const
          : { type: 'incomplete', reason: message.status === 'stopped' ? 'cancelled' : 'error' } as const,
    } : {}),
    content: message.parts.map((part) => {
      if (part.type === 'tool') {
        return {
          type: 'tool-call' as const,
          toolCallId: part.toolCallId || part.id,
          toolName: part.toolName || 'tool',
          args: {}, argsText: '',
          artifact: part,
          ...(part.state === 'complete' || part.state === 'error' || part.state === 'stopped'
            ? { result: part.summary || part.error || '', isError: part.state !== 'complete' } : {}),
        };
      }
      return {
        type: part.type, text: part.text || '', status: partStatus(part.state),
        providerMetadata: { bbui: { id: part.id, state: part.state, durationMs: part.durationMs ?? 0 } },
      };
    }),
    metadata: { custom: { displayStatus: message.status } },
  };
}
