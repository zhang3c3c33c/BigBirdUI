# Structured non-GUI additions (2026-09-28)

Android only; reuse `/system`, current-user Shizuku UID 2000, STOP, serialization, deduplication and independent dispatch receipts. No arbitrary shell, SQL, URI, provider column or intent extras from the model. Existing calendar behavior must remain intact. Errors must remain failed in the actual Pi loop while preserving receipts. UI receives intent + bounded summary, never provider inventories or private message bodies.

## Groups and operations

- `contacts`: `list`, `details`, `create`, `update`. Query `query`, `offset`, `limit` (1..100), optional current `userId`. Details require `contactId`, return aggregate identity and raw contact identities. Create uses local unsynced raw contact (report this explicitly), `name`, optional `phones: string[]`, `emails: string[]`; return `rawContactId` and available `contactId`. Update requires `rawContactId`, optional `contactId` identity assertion; updates only supplied name/phones/emails (empty array clears that category), preserves unrelated rows and other raw contacts. No deletion tool this round. Batched mutations and readback with independent bounded receipt.
- `sms`: `list`, `details`. Read only. List filters optional `query` (literal body/address match), `address`, `startMs`, `endMs`, `offset`, `limit`. Details require `messageId`; returns bounded text with `textOffset`/`textLimit` pagination (UTF-16 with surrogate-safe boundaries). List excludes full bodies, uses short snippet. No SMS sending or database writing.
- `call_log`: `list`, `details`. Read only. List filters `number`, `startMs`, `endMs`, optional `type` (Android standard integer), offset/limit. Details require `callId`. No call initiation or modifications.
- `media`: `list`, `details`. Read only, current user's external MediaStore. List filters `kind` = image/video/audio, `query` (literal display name match), `startMs`/`endMs` for dateAdded (convert seconds explicitly), offset/limit. Details require `kind` and `mediaId`. Metadata only, return content URI plus available path; no Base64, reading image content or app-private storage.
- `clock`: `capabilities`, `create_alarm`, `create_timer`. Standard AlarmClock intents; fixed action/extras and current user only. create_alarm hour 0..23, minute 0..59, optional label, days integers 1..7 (Calendar convention), vibrate boolean; create_timer seconds 1..86400 and optional label. Request EXTRA_SKIP_UI=true but explicitly report this is a request, handler may display UI, and dispatch is not proof of successful alarm creation. Return resolved handler/dispatch facts, no fake IDs or verification. Never auto-retry uncertain intents. capabilities is read-only. No generic intent tool or settings expansion.

All provider results have per-field and page-wide bounds, explicit nextOffset, stable sorts, escaped literal filters and bound arguments. Missing provider/permissions/unsupported version return facts; no fallback permissions or GUI control. Shared current-user provider client helper may be factored from calendar as long as calendar regressions pass. No task-level mandatory confirmation policy.

## Ownership

- Backend agent: android/device main/test sources for new provider/clock tools and routing; no app/frontend/runtime edits or device access.
- Runtime agent: pi/android/agent-tools.mjs and tests, protocol fixtures, runtime payload required names, android/chat-ui tool title mapping and tests. No Android Kotlin.
- Question agent: MainActivity question routing, frontend questions component/submit feedback, question-focused tests and Android instrumentation; no device module or tool registry. No real phone actions.
- Primary: app AppCoordinator readonly capabilities routing, ChatStore titles, docs, integration review, builds and sole device operator.

## Acceptance

Question regression must traverse actual MainActivity -> service routing, not call SessionController directly only. Cover draft and submit/cancel acknowledgements. Keep active phone answers until saved; installing kills the active turn, so inspect before installation. No automatic submission or resumption of the user's medical-calendar task.

Provider tests use isolated temporary local contacts and test fixtures; no changes to real contacts/messages/calls/media. Read only minimal fixture-specific device queries. Clock dispatch is verified against a test handler or pure intent fixture; do not leave real alarms/timers running. Three model protocols use local HTTP fixtures only. No real model/search requests.
