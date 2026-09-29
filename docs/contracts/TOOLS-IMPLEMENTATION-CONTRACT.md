# System and agent tools implementation contract

## Ownership and gates

Shizuku readiness is required for the entire product. Loss invalidates execution, cancels questions and requests, pauses the queue, closes preview and returns to onboarding. Reauthorization never replays interrupted work. Virtual display creation remains lazy; binding a system service does not create a display.

Foundation changes and regression tests land before tool implementations. Main owns integration, native settings, runtime configuration and device installation. Android worker owns device backend, coordinator, session/permission lifecycle and question host. Runtime worker owns Pi adapters, dependencies, build payload and runtime tests. Frontend worker owns chat-ui only.

## Native bridge

All requests use existing loopback bearer authentication bound to a run. System mutations share the device action serialization and deduplication gate, with STOP checked before dispatch. No arbitrary shell endpoint.

- `POST /system`: `{group: 'apps'|'notifications'|'clipboard'|'files'|'calendar'|'contacts'|'sms'|'call_log'|'media'|'clock', operation, params, actionId}`. Existing `content` / `details` result shape; execution and observation facts remain separate. Calendar operations follow `docs/contracts/CALENDAR-TOOL-CONTRACT.md`; other additions follow `docs/contracts/NON-GUI-TOOLS-CONTRACT.md`. Bounded `details.receipt` retains created/changed identity even when readback fails or a request is deduplicated. Clock dispatch is not proof of alarm creation or a guarantee of invisible UI.
- `POST /questions`: `{toolCallId, questions:[{id,header,question,options:[{label,description?}],multiSelect?}]}`. Waits for explicit response or abort, no automatic answer timeout. Host supplies request/session/run identities.
- Question response: `{requestId, answers:[{questionId,selected:string[],text:string}], cancelled:boolean}`.
- UI command `answerQuestion`: same answers plus `sessionId`, `runId`, `requestId`, optional `cancelled`. At most one accepted response; reject stale identity.
- UI command `questionDraft`: same identity and draft answers; saves without resolving. Drafts persist natively because WebView DOM storage is disabled.
- Snapshot `pendingQuestion`: identities, toolCallId, questions and status. Question history is persistent; live pending promise is not resurrected after process death. Frontend drafts are keyed by question identity.
- Question history is paged with visible messages. Each question records its stable Pi `messageSourceKey`; the matching tool part projects `questionRequestId`. Reused provider tool-call IDs must never attach a later question to an older message. `questionResult` acknowledges accepted/rejected submissions with a revision.
- `question_requested` / `question_resolved` events carry session/run identities. Waiting occupies the global execution slot; ordinary composer submission queues instead of steering into the pending question.

## Configuration and memory

- Runtime config `tools.search`: `{provider:'bocha'|'baidu', apiKey:string}`. Read current encrypted configuration at task launch. Never project credentials to WebView or history. Test/probe runtimes receive no personal tool config.
- `memoryDir`: private no-backup directory outside replaceable payload and Pi sessions; test/gate directories are isolated. Bootstrap sets `PI_MEMORY_DIR` before loading memory.
- Pi command `bbui_memory` with `id`, `action:'read'|'write'|'delete'`, optional `content`, `revision`; standard RPC response (`type:response`, `command:bbui_memory`, `id`, `success`, `data` or `error`). Management targets upstream long-term MEMORY.md; optimistic revision prevents overwrites. Return data `{content,revision}`. All extension and management writes coordinate over the same authoritative store.
- Native `AppCoordinator.memoryCommand(command, done)` and `AssistantService.memoryCommand(command, done)` forward to active or catalog runtime without model calls.

## Display projection

New tool summaries use `details.bbuiTool: {title:string, summary?:string}`; native limits lengths and never forwards raw results by default. Runtime retains full model-facing data and Pi history. Unknown tools get a generic tool label, not phone-specific text. Sources must be bounded and validated HTTP(S) links if separately projected.

## Validation

No paid search or model requests without separate authorization. Use fixtures/local servers and dedicated device test apps/files/notifications. Do not modify real user app data for tests. Main alone installs APKs and performs device acceptance. Preserve existing configuration and sessions.
