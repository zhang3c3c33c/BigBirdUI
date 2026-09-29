import { Component, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import {
  ActionBarPrimitive, AssistantRuntimeProvider, ComposerPrimitive,
  MessagePrimitive, ThreadPrimitive, useAuiState, useExternalStoreRuntime, useAui,
  type AppendMessage, type ReasoningMessagePartProps, type TextMessagePartProps, type ToolCallMessagePartProps,
} from '@assistant-ui/react';
import { MarkdownTextPrimitive } from '@assistant-ui/react-markdown';
import remarkGfm from 'remark-gfm';
import { acceptSnapshot, convertMessage, initialSnapshot, post, indexQuestions, toolPresentation, toolTitle, toolSources, type ChatPart, type Snapshot } from './contract';
import './style.css';
import { SessionSidebar } from './sessions';
import { Panel, QueuedSteerButton, QueuePanel, TaskProgress } from './panels';
import { ModelControls, useModelSelection } from './models';
import { QuestionCard, QuestionContext, type QuestionDraft } from './questions';
import { BrandMark } from './brand';
import { viewStateSync } from './view-state';

const markdownComponents = {
  // Markdown images are deliberately not fetched by the local chat surface.
  img: () => null,
  a: ({ href, children }: { href?: string; children?: ReactNode }) =>
    <a href={href} target="_blank" rel="noreferrer noopener">{children}</a>,
};
function Markdown() {
  return <MarkdownTextPrimitive className="markdown" smooth={false} remarkPlugins={[remarkGfm]} components={markdownComponents} />;
}
function AssistantText(props: TextMessagePartProps) {
  if (!props.text.trim()) return null;
  return <Markdown />;
}
function Reasoning(props: ReasoningMessagePartProps) {
  const metadata = props.providerMetadata?.bbui;
  const state = metadata?.state;
  if (!props.text.trim() && state !== 'streaming' && state !== 'running') return null;
  const duration = typeof metadata?.durationMs === 'number' ? metadata.durationMs : 0;
  const label = state === 'streaming' || state === 'running' ? '正在思考'
    : state === 'stopped' ? '思考已停止' : state === 'error' ? '思考已中断' : '思考过程';
  return <details className="reasoning">
    <summary><span className="reasoning-icon"><BrandMark small /></span><span>{label}</span>
      {duration > 0 && <span className="duration">{(duration / 1000).toFixed(1)} 秒</span>}
      <span className="chevron">⌄</span></summary>
    <div className="reasoning-content">{props.text || '等待模型返回思考内容…'}</div>
  </details>;
}
function Tool(props: ToolCallMessagePartProps) {
  const part = props.artifact as ChatPart;
  return part.toolName === 'ask_user_question' ? <QuestionTool part={part} /> : <ToolDetails part={part} />;
}
function QuestionTool({ part }: { part: ChatPart }) {
  const context = useContext(QuestionContext);
  const question = context?.index.forPart(part);
  return question && context ? <QuestionCard key={`${question.sessionId}:${question.runId}:${question.requestId}`} question={question} snapshot={context.snapshot} /> : <ToolDetails part={part} />;
}
function ToolDetails({ part }: { part: ChatPart }) {
  const { running, label, tone, facts } = toolPresentation(part);
  const taskTool = part.toolName === 'task_state';
  if (taskTool && part.state !== 'error') return null;
  const title = toolTitle(part);
  const sources = toolSources(part);
  return <details className={`tool-card ${part.state} outcome-${tone} ${taskTool ? 'task-tool' : ''}`}>
    <summary><span className={`tool-symbol ${running ? 'pulse' : ''}`}>{running ? '◌' : tone === 'uncertain' ? '!' : '−'}</span>
      <span className="tool-title">{title}</span><span className="tool-state">{label}</span><span className="chevron">⌄</span></summary>
    <div className="tool-detail">{[facts, (part.error || part.summary)?.slice(0, 4000)].filter(Boolean).join('\n')}
      {sources.length > 0 && <ul className="tool-sources">{sources.map((source, index) => <li key={`${source.url}-${index}`}><a href={source.url} target="_blank" rel="noreferrer noopener">{source.title}</a></li>)}</ul>}
    </div>
  </details>;
}

function UserMessage() {
  const id = useAuiState((state) => state.message.id);
  return <MessagePrimitive.Root className="message user-message" data-message-id={id}><div className="user-bubble"><MessagePrimitive.Parts components={{ Text: Markdown }} /></div></MessagePrimitive.Root>;
}
// The library emits Empty after a terminal tool call as well as for empty
// messages. Waiting is a message-level state, not an empty-part renderer.
function EmptyPart() { return null; }
function AssistantMessage() {
  const status = useAuiState((state) => state.message.status);
  const id = useAuiState((state) => state.message.id);
  const waiting = useAuiState((state) => state.message.status?.type === 'running' &&
    !state.message.content.some((part) => part.type === 'tool-call' ||
      ((part.type === 'text' || part.type === 'reasoning') && part.text.trim().length > 0)));
  const hasCopyableText = useAuiState((state) => state.message.content.some((part) => part.type === 'text' && part.text.trim().length > 0));
  return <MessagePrimitive.Root className="message assistant-message" data-message-id={id}>
    <div className="assistant-label"><span className="avatar"><BrandMark small /></span>BBUI</div>
    <MessagePrimitive.Parts components={{ Text: AssistantText, Reasoning, tools: { Fallback: Tool }, Empty: EmptyPart }} />
    {waiting && <span className="waiting pulse">正在准备回复…</span>}
    {status?.type === 'incomplete' && <div className={`message-notice ${status.reason === 'error' ? 'error' : ''}`}>
      {status.reason === 'cancelled' ? '已停止 · 已保留生成的内容' : '回复中断 · 已保留生成的内容'}
    </div>}
    {hasCopyableText && <ActionBarPrimitive.Root className="message-actions" hideWhenRunning={false}>
      <ActionBarPrimitive.Copy className="copy-button" title="复制回复" aria-label="复制回复"><span className="copy-label">复制</span><span className="copied-label">已复制</span></ActionBarPrimitive.Copy>
    </ActionBarPrimitive.Root>}
  </MessagePrimitive.Root>;
}

function SessionComposer({ snapshot, save, begin, shouldClear, restored, selectionPending }: { snapshot: Snapshot; save: (text: string) => void; begin: (id: string, text: string) => void; shouldClear: (id: string) => boolean; restored: boolean; selectionPending: boolean }) {
  const thread = useAui();
  const text = useAuiState(s => s.composer.text);
  const [pendingSubmission, setPendingSubmission] = useState<{ id: string; sessionId: string; text: string } | null>(null);
  const [draftSession, setDraftSession] = useState(snapshot.sessionId);
  useLayoutEffect(() => {
    thread.composer.setText(snapshot.viewState?.text ?? '');
    setDraftSession(snapshot.sessionId);
  }, [snapshot.sessionId, thread]);
  useEffect(() => {
    const result = snapshot.submissionResult;
    if (!pendingSubmission || result?.id !== pendingSubmission.id || result.sessionId !== pendingSubmission.sessionId) return;
    if (!result.accepted) { setPendingSubmission(null); return; }
    if (snapshot.sessionId !== pendingSubmission.sessionId || draftSession !== snapshot.sessionId || !restored) return;
    if (text === pendingSubmission.text && shouldClear(pendingSubmission.id)) { thread.composer.setText(''); save(''); }
    setPendingSubmission(null);
  }, [snapshot.submissionResult, snapshot.sessionId, draftSession, pendingSubmission, text, thread, save, shouldClear, restored]);
  const stop = !text.trim() && snapshot.isRunning && snapshot.runningSessionId === snapshot.sessionId &&
    (!snapshot.control || snapshot.control.mode === 'running');
  const sending = pendingSubmission?.sessionId === snapshot.sessionId && pendingSubmission.text === text;
  const disconnected = snapshot.desktop?.connected === false;
  const send = () => {
    if (disconnected || !text.trim() || !snapshot.sessionsReady || selectionPending || !restored || draftSession !== snapshot.sessionId || sending) return;
    const id = crypto.randomUUID();
    begin(id, text);
    setPendingSubmission({ id, sessionId: snapshot.sessionId, text });
    post({ type: 'send', sessionId: snapshot.sessionId, submissionId: id, text: text.trim() });
  };
  return <ComposerPrimitive.Root className="composer" style={{ visibility: !restored || !snapshot.sessionId || draftSession !== snapshot.sessionId ? 'hidden' : undefined }} onSubmit={event => event.preventDefault()}>
    <ComposerPrimitive.Input className="composer-input" placeholder="输入消息" aria-label="消息" minRows={1} maxRows={5} submitMode="none" addAttachmentOnPaste={false}
      unstable_focusOnRunStart={false} unstable_focusOnScrollToBottom={false} unstable_focusOnThreadSwitched={false}
      onChange={event => save(event.target.value)} />
    <button type="button" className="send-button" aria-label={stop ? '停止任务' : snapshot.runningSessionId ? '加入队列' : '发送消息'}
      disabled={!stop && (disconnected || !text.trim() || !snapshot.sessionsReady || selectionPending || !restored || draftSession !== snapshot.sessionId || sending)}
      onClick={() => stop ? post({ type: 'stop' }) : send()}>
      <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        {stop ? <rect x="6" y="6" width="12" height="12" rx="1" fill="currentColor" stroke="none" /> : <path d="M12 19V5m-6 6 6-6 6 6" />}
      </svg>
    </button>
  </ComposerPrimitive.Root>;
}

function ExecutionControls({ snapshot }: { snapshot: Snapshot }) {
  if (snapshot.desktop?.connectionStatus?.code === 'reconnecting') return <div className="global-execution" role="status">
    <span className="execution-dot pulse" aria-hidden="true" /><span>{snapshot.desktop.connectionStatus.message}</span>
    <button className="stop-action" onClick={() => post({ type: 'stop' })}>停止</button>
  </div>;
  const control = snapshot.control;
  const question = snapshot.pendingQuestion;
  const waiting = question?.status === 'pending' && question.sessionId === snapshot.runningSessionId;
  const owner = control?.sessionId || snapshot.runningSessionId;
  const mode = control?.mode ?? (snapshot.runningSessionId ? 'running' : 'idle');
  if (mode === 'idle' || (mode === 'error' && !control?.canResume)) return null;
  const elsewhere = owner && owner !== snapshot.sessionId;
  const label = mode === 'manual' ? '你正在操作' : mode === 'taking_over' ? '正在交接' : mode === 'resuming' ? '正在恢复' : mode === 'stopped' || mode === 'error' ? '已停止' : waiting ? '等待回答' : '执行中';
  const title = snapshot.sessions?.find(s => s.id === owner)?.title ?? '会话';
  return <div className="global-execution" role="status" data-control-mode={mode}>
    <span className={`execution-dot ${mode === 'running' ? 'pulse' : ''}`} aria-hidden="true" />
    <span>{elsewhere ? `${mode === 'running' && !waiting ? '正在执行' : label}：${title}` : label}</span>
    {elsewhere && <button onClick={() => post({ type: 'selectSession', sessionId: owner })}>返回</button>}
    {control && mode === 'running' && <button onClick={() => post({ type: 'takeOver', controlId: control.id })}>我来操作</button>}
    {control && mode === 'manual' && <button onClick={() => post({ type: control.canResume ? 'resumeTask' : 'endManual', controlId: control.id })}>{control.canResume ? '交给 AI 继续' : '结束操作'}</button>}
    {control?.canResume && (mode === 'stopped' || mode === 'error') && <button onClick={() => post({ type: 'resumeTask', controlId: control.id })}>继续任务</button>}
    {['running', 'taking_over', 'resuming', 'manual'].includes(mode) && <button className="stop-action" aria-label={mode === 'manual' ? '紧急停止' : '停止'} onClick={() => post({ type: 'stop' })}>{mode === 'manual' ? '■' : '停止'}</button>}
  </div>;
}
export function Chat({ aside, previewControl }: { aside?: ReactNode; previewControl?: { expanded: boolean; onToggle(): void } }) {
  const [snapshot, setSnapshot] = useState(initialSnapshot);
  const [loaded, setLoaded] = useState(false);
  const selection = useModelSelection(snapshot);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [panel, setPanel] = useState<'progress' | 'queue' | null>(null);
  useEffect(() => setPanel(null), [snapshot.sessionId]);
  const [restoredSession, setRestoredSession] = useState<string | null>(null);
  const draft = useRef('');
  const viewport = useRef<HTMLDivElement>(null);
  const anchor = useRef<{ height: number; top: number; firstId?: string } | null>(null);
  const viewWriter = useMemo(() => viewStateSync(post), []);
  const questionDrafts = useRef(new Map<string, QuestionDraft>());
  const questionContext = useMemo(() => ({ index: indexQuestions(snapshot), drafts: questionDrafts.current, snapshot: {
    sessionId: snapshot.sessionId, runId: snapshot.runId, runningSessionId: snapshot.runningSessionId,
    isRunning: snapshot.isRunning, questionResult: snapshot.questionResult,
  } }), [snapshot.messages, snapshot.pendingQuestion, snapshot.questionHistory, snapshot.questionResult,
    snapshot.sessionId, snapshot.runId, snapshot.runningSessionId, snapshot.isRunning]);
  const currentSession = useRef(snapshot.sessionId);
  const focusedQuestion = useRef('');
  useEffect(() => {
    const target = snapshot.focusQuestion;
    if (!target || target.id === focusedQuestion.current || target.sessionId !== snapshot.sessionId || restoredSession !== snapshot.sessionId) return;
    let frame = 0;
    let attempts = 0;
    const reveal = () => {
      const card = Array.from(viewport.current?.querySelectorAll<HTMLElement>('.question-card[data-request-id]') ?? [])
        .find(element => element.dataset.requestId === target.requestId);
      if (card) {
        focusedQuestion.current = target.id;
        card.scrollIntoView({ block: 'start', behavior: 'instant' });
        post({ type: 'questionFocused', ...target });
      } else if (++attempts < 60) frame = requestAnimationFrame(reveal);
    };
    frame = requestAnimationFrame(reveal);
    return () => cancelAnimationFrame(frame);
  }, [snapshot.focusQuestion?.id, snapshot.sessionId, snapshot.messages, snapshot.pendingQuestion, restoredSession]);
  useEffect(() => {
    const receive = (event: Event) => {
      let value = (event as CustomEvent<Snapshot>).detail;
      if (!value || value.type !== 'snapshot' || !Array.isArray(value.messages)) return;
      if (value.sessionId !== currentSession.current) viewWriter.flush();
      const settledView = viewWriter.settle(value.submissionResult);
      if (settledView && viewWriter.shouldClear(value.submissionResult!.id)) {
        if (currentSession.current === settledView.sessionId) draft.current = '';
        if (value.sessionId === settledView.sessionId) value = { ...value, viewState: settledView };
      }
      setSnapshot((previous) => acceptSnapshot(previous, value));
      setLoaded(true);
    };
    window.addEventListener('bbui-message', receive);
    post({ type: 'ready' });
    return () => window.removeEventListener('bbui-message', receive);
  }, []);
  useEffect(() => {
    const flush = () => viewWriter.flush();
    const visibility = () => { if (document.visibilityState === 'hidden') flush(); };
    window.addEventListener('pagehide', flush);
    document.addEventListener('visibilitychange', visibility);
    return () => { flush(); window.removeEventListener('pagehide', flush); document.removeEventListener('visibilitychange', visibility); };
  }, [viewWriter]);
  useLayoutEffect(() => {
    if (snapshot.revision < 0) return;
    // The external runtime projects the new snapshot in an effect. Restore the
    // reading anchor after its DOM commit, not before the older page is present.
    let frame = 0;
    const afterProjection = () => {
      const element = viewport.current;
      if (anchor.current && element && (anchor.current.firstId !== snapshot.messages[0]?.id || !snapshot.hasOlder)) {
        if (element.querySelector<HTMLElement>('.message')?.dataset.messageId !== snapshot.messages[0]?.id) {
          frame = requestAnimationFrame(afterProjection);
          return;
        }
        element.scrollTop = anchor.current.top + element.scrollHeight - anchor.current.height;
        anchor.current = null;
        setLoadingOlder(false);
      }
      post({ type: 'rendered', revision: snapshot.revision });
    };
    frame = requestAnimationFrame(afterProjection);
    return () => cancelAnimationFrame(frame);
  }, [snapshot.revision, snapshot.messages.length, snapshot.hasOlder]);
  const onNew = useCallback(async (message: AppendMessage) => {
    const text = message.content.filter((part) => part.type === 'text').map((part) => part.text).join('');
    if (text.trim() && !selection.pending && snapshot.desktop?.connected !== false) post({ type: 'send', text: text.trim(), sessionId: snapshot.sessionId, submissionId: crypto.randomUUID() });
  }, [snapshot.sessionId, selection.pending, snapshot.desktop?.connected]);
  const onCancel = useCallback(async () => post({ type: 'stop' }), []);
  const runtime = useExternalStoreRuntime({
    messages: snapshot.messages, convertMessage, isRunning: snapshot.isRunning,
    isSendDisabled: !loaded || !snapshot.sessionsReady || selection.pending || snapshot.desktop?.connected === false, onNew, onCancel,
    adapters: { threadList: {
      threadId: snapshot.sessionId || undefined,
      threads: (snapshot.sessions ?? []).map(item => ({ id: item.id, title: item.title, status: 'regular' as const })),
      onSwitchToNewThread: () => post({ type: 'newSession' }),
      onSwitchToThread: id => { post({ type: 'selectSession', sessionId: id }); },
      onRename: (id, title) => post({ type: 'renameSession', sessionId: id, title }),
      onDelete: id => post({ type: 'deleteSession', sessionId: id }),
    } },
  });
  useLayoutEffect(() => {
    viewWriter.flush();
    currentSession.current = snapshot.sessionId;
    anchor.current = null; setLoadingOlder(false);
    draft.current = snapshot.viewState?.text ?? '';
    const top = snapshot.viewState?.scrollTop ?? 0;
    let frame = 0;
    const restore = () => {
      const element = viewport.current;
      if (element && snapshot.messages.length > 0 && element.querySelector<HTMLElement>('.message')?.dataset.messageId !== snapshot.messages[0]?.id) {
        frame = requestAnimationFrame(restore); return;
      }
      if (element) element.scrollTop = top;
      setRestoredSession(snapshot.sessionId);
    };
    frame = requestAnimationFrame(restore);
    return () => cancelAnimationFrame(frame);
  }, [snapshot.sessionId]);
  const saveView = (text = draft.current) => {
    draft.current = text;
    if (snapshot.sessionId && restoredSession === snapshot.sessionId) viewWriter.save({ type: 'viewState', sessionId: snapshot.sessionId, text, scrollTop: viewport.current?.scrollTop ?? 0 });
  };
  const loadOlder = () => {
    if (!viewport.current || loadingOlder) return;
    anchor.current = { height: viewport.current.scrollHeight, top: viewport.current.scrollTop, firstId: snapshot.messages[0]?.id };
    setLoadingOlder(true);
    post({ type: 'loadOlder', sessionId: snapshot.sessionId });
  };
  return <QuestionContext.Provider value={questionContext}><AssistantRuntimeProvider runtime={runtime}>
    <div className="session-layout">
    <SessionSidebar snapshot={snapshot} expanded={sidebarOpen} close={() => setSidebarOpen(false)} />
    <ThreadPrimitive.Root className="chat-shell">
      <header className="session-heading">
        <button className="menu-button" aria-label="打开会话栏" onClick={() => setSidebarOpen(true)}>☰</button>
        <strong>{snapshot.sessions?.find(s => s.id === snapshot.sessionId)?.title ?? 'BBUI'}</strong>
        {snapshot.task && <button className="progress-button" onClick={() => setPanel('progress')}>进度</button>}
        {previewControl ? <button className="preview-entry" aria-expanded={previewControl.expanded} aria-pressed={previewControl.expanded} onClick={previewControl.onToggle}>手机画面</button>
          : <button className="preview-entry" aria-label="查看执行画面" onClick={() => post({ type: 'openPreview' })}>画面</button>}
      </header>
      <ExecutionControls snapshot={snapshot} />
      {snapshot.sessionError && <div className="session-error" role="alert">{snapshot.sessionError}<button onClick={() => post({ type: 'refreshSessions' })}>刷新</button></div>}
      <ThreadPrimitive.Viewport className="viewport" ref={viewport} autoScroll={restoredSession === snapshot.sessionId} scrollToBottomOnRunStart={false} scrollToBottomOnInitialize={false} scrollToBottomOnThreadSwitch={false} onScroll={() => saveView()}>
        <div className="message-list">
          {snapshot.hasOlder && <button className="load-older" onClick={loadOlder} disabled={loadingOlder}>{loadingOlder ? '正在加载…' : '查看更早的对话'}</button>}
          {snapshot.messages.length === 0 && !(snapshot.queue ?? []).some(q => q.sessionId === snapshot.sessionId) && <div className="welcome" aria-hidden="true"><BrandMark className="welcome-mark" /></div>}
          <ThreadPrimitive.Messages components={{ UserMessage, AssistantMessage }} />
          {questionContext.index.fallback && <QuestionCard
            key={`${questionContext.index.fallback.sessionId}:${questionContext.index.fallback.runId}:${questionContext.index.fallback.requestId}`}
            question={questionContext.index.fallback} snapshot={questionContext.snapshot} />}
          {(snapshot.interruptedTasks ?? []).map(item => <div className="message-notice" key={item.id}>{item.interruptedReason === 'steering'
            ? item.steeringStatus === 'not_sent' ? '补充未发送，已保留内容' : item.steeringStatus === 'accepted' ? '补充已收到，原任务已结束' : '补充是否收到尚未确认，未自动重发'
            : '上次任务未完成，未自动重试'}：{item.text}
            {snapshot.capabilities?.platform === 'windows' && <button disabled={snapshot.isRunning || snapshot.desktop?.connected === false} onClick={() => post({ type: 'resumeInterrupted', submissionId: item.id })}>明确继续</button>}</div>)}
        </div>
        <ThreadPrimitive.ViewportFooter className="viewport-footer"><ThreadPrimitive.ScrollToBottom className="scroll-bottom" aria-label="回到底部">↓ 回到底部</ThreadPrimitive.ScrollToBottom></ThreadPrimitive.ViewportFooter>
      </ThreadPrimitive.Viewport>
      <footer className="composer-footer">
        {!!snapshot.queue?.length && <div className="queue-strip">
          <button onClick={() => setPanel('queue')}>待发送 · {snapshot.queue.length}</button>
          {snapshot.queuePaused && <span>已暂停</span>}
          <button className="queue-toggle" disabled={snapshot.queuePaused && snapshot.desktop?.connected === false} onClick={() => post({ type: snapshot.queuePaused ? 'resumeQueue' : 'pauseQueue' })}>{snapshot.queuePaused ? '继续队列' : '暂停队列'}</button>
        </div>}
        <div className="pending-list">{(snapshot.queue ?? []).filter(q => q.sessionId === snapshot.sessionId).map(q => <div className="pending-message" key={q.id}><span>{q.text}</span><QueuedSteerButton snapshot={snapshot} item={q} /><button aria-label={`取消排队 ${q.text}`} onClick={() => post({ type: 'cancelQueued', submissionId: q.id })}>取消</button></div>)}</div>
        <ModelControls snapshot={snapshot} selection={selection} />
        <SessionComposer snapshot={snapshot} save={saveView} shouldClear={viewWriter.shouldClear}
          begin={(id, text) => viewWriter.begin(id, { type: 'viewState', sessionId: snapshot.sessionId, text, scrollTop: viewport.current?.scrollTop ?? 0 })}
          restored={restoredSession === snapshot.sessionId} selectionPending={selection.pending} /></footer>
    </ThreadPrimitive.Root>
    {aside}
    </div>
    {panel && <Panel title={panel === 'progress' ? '任务进度' : '待发送'} close={() => setPanel(null)}>
      {panel === 'progress' ? <TaskProgress snapshot={snapshot} /> : <QueuePanel snapshot={snapshot} close={() => setPanel(null)} />}
    </Panel>}
  </AssistantRuntimeProvider></QuestionContext.Provider>;
}

export class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    if (this.state.failed) return <div className="error-fallback"><h2>聊天界面暂时无法显示</h2><p>对话已保存在本机。你可以停止当前操作，然后重新打开界面。</p><button onClick={() => post({ type: 'stop' })}>停止操作</button><button onClick={() => location.reload()}>重新打开界面</button></div>;
    return this.props.children;
  }
}

// Both hosts use this component; only their entrypoints and bridges differ.
