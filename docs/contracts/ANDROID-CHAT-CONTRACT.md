# Android chat integration contract

The native service owns chat state; Pi remains the sole agent and conversation authority.
Frontend assets live in `android/chat-ui/` with their own package lock and build output.

## Native to UI

Listen to `window` event `bbui-message`; `event.detail` is an object:

```ts
type Snapshot = {
  type: 'snapshot'; revision: number; runId: string; sessionId: string;
  status: { phase: string; message: string }; isRunning: boolean;
  task?: TaskState | null; taskUpdatedThisRun?: boolean;
  taskDisplay?: { state: string; label: string };
  messages: ChatMessage[]; hasOlder: boolean;
  timing: { piEmittedAtMs: number; runtimeReceivedAtMs: number; nativeReceivedAtMs: number; projectionAtMs: number };
};
type ChatMessage = {
  id: string; role: 'user' | 'assistant';
  status: 'streaming' | 'complete' | 'stopped' | 'error';
  parts: ChatPart[];
};
type ChatPart = {
  id: string; type: 'text' | 'reasoning' | 'tool';
  state: 'streaming' | 'running' | 'complete' | 'stopped' | 'error';
  text?: string; durationMs?: number;
  toolCallId?: string; toolName?: string; title?: string; summary?: string; error?: string;
  executionState?: '未派发' | '已派发' | '部分派发' | '未知' | '无需派发';
  observationState?: '已取得' | '失败' | '未请求';
};
```

Snapshots replace visible state, never append duplicate messages. Last 40 messages initially;
loadOlder adds another 40 without discarding newer messages. Reasoning and tool details default
collapsed. No image bytes, credentials, raw tool argument JSON, or transport diagnostics here.
Pagination can expand the visible window at the same revision. Consumers accept this without
duplicating messages and keep the current reading anchor. Native cache also carries `sourceKey`
and `timestamp` for matching Pi messages across restarts; these are not displayed.

Pi history is merged by role and original message timestamp. Display-only stopped/error replies
and older messages removed from Pi context by compaction remain visible, never fed back to Pi.
Unfinished historical tools are stopped, not promoted to success by the next task.

`task_state` is a record tool, not a phone operation. Only a successful tool result's
`details.bbuiTask` updates `snapshot.task` (`TaskState | null`); proposed tool arguments
never announce completion. The projection allowlists version 1 task fields, sanitizes
display strings, and preserves the original task only in Pi's own session. Its tool
card is `读取任务记录` or `更新任务记录`, with no raw task JSON in conversational text.

`snapshot.taskUpdatedThisRun` tracks a successful update in the current user turn;
read calls preserve the record without reasserting old completion. A new user run
clears this freshness flag while retaining the prior record for inspection. Phone
actions after a terminal record (`completed`, `waiting_user`, `blocked`) invalidate
that terminal assertion; current `active` records remain current during actions.
`snapshot.taskDisplay` has a `state` and `label`: STOP/error override running and
model declarations, running shows `进行中`, and a settled reply with no current update
shows `本轮回复结束 · 任务状态未确认`. A freshly recorded active task after a settled reply
shows `本轮回复结束 · 任务尚未完成`. Model-recorded waiting/completion/blocking are shown
only when current. This projection never triggers a prompt or resumes phone input.

History replay derives freshness after the latest user message and retains successful
read/update records. Authoritative Pi history can replace a cached record; old startup
history cannot overwrite a record received live in the running turn. Cache restoration
keeps task records and settled state, while a formerly running turn restores as stopped.
If compacted history contains a task result without its call arguments, the record is
restored but its update/read provenance is unknown, so terminal display is unconfirmed.
The collapsible panel uses `.task-record`; its status container uses `data-task-state`.

Tool `state: complete` means that the call returned, not that the requested action or
business goal succeeded. The native projection allowlists `details.执行.状态` and
`details.观察.状态`, including on restored Pi history and display-cache reloads. The UI
shows dispatch and observation facts independently (for example `未执行 · 已观察` or
`已派发 · 观察失败`), with no success checkmark. A normal returned `未派发`, `部分派发`,
or `未知` result does not turn the assistant message into an error. Legacy results
without these facts show `已返回`; they remain readable without invented evidence.

New tool calls require nonblank top-level `意图` (at most 80 characters), a model-authored description
of the next operation. The native projection uses this as the card title and falls back to
the operation name for older calls. It is display-only: device parameters and action IDs
do not depend on it. Tool status is always derived from actual execution events.

An assistant message containing tool calls displays its ordinary commentary in collapsed
`执行说明` sections; actual reasoning stays in `思考过程`. A final text-only answer remains
expanded. Empty-part placeholders do not indicate ongoing work: only a genuinely empty,
running message displays one waiting label. Completed tool-only messages have neither a
waiting label nor an empty copy button.

## UI to native

`TaskState` is the versioned model-authored record defined in `pi/task-state.ts`.
Only successful `task_state` results (`details.bbuiTask`) update its display projection.
The task record is collapsed by default. `taskDisplay` distinguishes generating a reply,
waiting for the user, model-reported completion, a blocker, interruption, and an unconfirmed
outcome. A completed reply without a current task declaration does not imply task completion.
Raw arguments are never used as proof that a task update succeeded. Cache/history restoration
only updates the projection; it cannot call a tool or resume the agent. See `docs/development/AGENT-TASK-DESIGN.md`.

`window.BBUI.postMessage(JSON.stringify(command))`, only from the bundled trusted origin.
Commands: `{type:'ready'}`, `{type:'send',text:string}`, `{type:'stop'}`,
`{type:'loadOlder'}`, `{type:'openPreview'}`, `{type:'openSettings'}`,
`{type:'rendered',revision:number}`. No browser-to-model requests or direct phone operations.

## Kotlin service contract

`AssistantService.subscribeChat((JSONObject)->Unit)`, `unsubscribeChat(listener)`,
`loadOlderChat()`, `recordChatRendered(revision:Long)`, `coordinator`, plus existing overlay methods.
Subscriptions immediately receive the current snapshot. The service owns 50ms coalescing;
non-delta boundaries flush immediately. MainActivity loads SettingsStore and calls
coordinator.start(config,text) for send, preserving all native permission/input gates.

## Ownership

- Root: AppCoordinator, AssistantService, ChatStore and its tests, root/Gradle builds, integration.
- Frontend worker: android/chat-ui/** only, including lockfile and frontend tests.
- Runtime worker: pi/android/**, android/runtime/**, scripts/android-runtime/** and runtime tests.
- Android worker: MainActivity, ChatWebView/new UI helpers, UI instrumentation tests; no coordinator,
  service, store, root Gradle or frontend edits. Ask root for dependencies/build changes.
- Only root installs, operates or instruments the phone. No cloud API requests during this work.
