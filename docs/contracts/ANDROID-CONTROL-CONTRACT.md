# Control handoff implementation contract

Shared contract for the 2026-09-26 task. This document describes the target, not already released behavior.

## Snapshot

Add optional `control` for backward-compatible clients:

```ts
control?: {
  id: string; // changes on ownership/control transition; stale handoff/resume rejected
  mode: 'idle' | 'running' | 'taking_over' | 'manual' | 'resuming' | 'stopped' | 'error';
  sessionId: string; // execution/continuation owner, never inferred from selected session
  canResume: boolean;
  canSteer: boolean;
};
queuePauseReasons?: string[]; // e.g. user, stop, restart, error; manual handoff is separate
submissionResult?: { id: string; sessionId: string; accepted: boolean };
```

`runningSessionId` remains the actual currently running session, not a browsing selection. `control.sessionId` retains continuation ownership while manually controlling or stopped. Clients use flags rather than guessing availability from labels.

## Commands

- `takeOver`, `resumeTask`, `endManual`: require `{controlId: string}`. Native preview/overlay passes the latest snapshot id; web passes the displayed snapshot id. Service revalidates.
- `steer`: `{sessionId, submissionId, text}`. Explicitly targets current running conversation; reject mismatches without executing or silently queueing elsewhere. Deduplicate.
  The optional submissionResult acknowledges the matching Pi RPC response. Keep the draft until accepted; rejection must not discard text or silently enqueue it. Acceptance means Pi accepted the instruction, not that an action completed.
- `stop`: global immediate STOP remains available without an id, from every stop surface.
- Existing queue/session/preview commands remain compatible.

All commands are routed through AssistantService.sessionCommand; MainActivity and overlay must not bypass the service handoff state. Existing coordinator direct APIs used by device tests may remain for compatibility, but app surfaces use the shared controller.

## Behavior

- Default send runs when safe/idle or queues; no blanket restart pause for a brand-new submission when no older pending/interrupted work exists. Previously waiting/interrupted items never auto-replay on restart.
- Takeover preserves owner/task, immediately revokes AI input, waits for old action/channel retirement before enabling manual touches. No other queued task starts during manual control. Idle takeover never calls a model.
- Resume from manual continues the interrupted task in its original Pi session after fresh observation; no user retyping and no action replay. Resume current task must not clear explicitly paused/error/restart/STOP waiting work. Normal handoff can release only the temporary handoff hold; queue scheduling otherwise retains previous eligibility.
- EndManual exits manual control without a model call (used when canResume=false). Resume pending tasks is an explicit separate queue operation only when needed.
- STOP retains partial output and waiting tasks, blocks next dispatch, and provides a continuation target for an interrupted known task. Repeated/stale resume taps do not duplicate requests.
- New submissions/replies during manual mode stay waiting unless explicit resume; don't steal the device. Global queue resume cannot revoke manual ownership.
- Lock, failure, delete, restart, or stale events cannot re-enable automation; preserve explicit STOP/coordinate/latest screenshot guards.
- Viewing/switching/closing a preview never changes execution ownership.

## Ownership

Backend agent owns AppCoordinator.kt, ConversationStore.kt, SessionController.kt, AssistantService.kt and associated backend tests/runtime changes needed for explicit steering. UI agent owns MainActivity.kt, ChatWebView.kt, android/chat-ui source/tests and ChatWebViewTest.kt. Root reviews integration and exclusively operates installation/device tests. Coordinate proposed contract changes through root before changing these shared shapes.
