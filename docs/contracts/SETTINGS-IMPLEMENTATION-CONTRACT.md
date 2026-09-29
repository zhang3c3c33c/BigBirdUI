# Settings/model selection implementation contract

2026-09-27. Implements first-release scope of SETTINGS-RESEARCH.md including its composer addendum. Do not implement deferred display presets, cache deletion, dark mode, or notification event preferences. Root reviews and alone operates the phone.

## Ownership

- settings_backend: SettingsStore, new model catalog/config helpers, SessionController, ConversationStore, AssistantService/AppCoordinator as needed, Pi bootstrap/config and catalog generator, backend tests. Preserve STOP, manual handoff, mock test hooks.
- settings_native: new SettingsActivity and native settings fragments/forms, MainActivity settings route, manifest/build dependencies, native settings instrumentation tests. Do not edit ChatWebView or service/controller/store; coordinate API additions with backend.
- settings_composer: android/chat-ui src/tests, ChatWebView bridge allowlist/tests. Do not edit MainActivity or native settings form.
- root: shared contract, integration review/builds/device validation/results.

## State/commands shared with web

Snapshot adds:

```
modelOptions?: Array<{
 connectionId: string; connectionName: string;
 modelId: string; modelName: string;
 thinkingLevels: Array<{id: string; label: string}>;
 defaultThinkingLevel: string;
}>;
modelSelection?: {connectionId: string; modelId: string; thinkingLevel: string};
```

No endpoint, API key, raw provider config or screenshots enter these fields. Empty thinkingLevels means no adjustable thinking entry. Use metadata-supported values, not invented universal low/medium/high.

Commands:
- selectModel {sessionId, requestId, connectionId, modelId}
- setThinkingLevel {sessionId, requestId, thinkingLevel}
- snapshot modelSelectionResult?: {id: requestId, sessionId, accepted, error?}; acknowledged only after local selection persistence succeeds. Web blocks send/steer while awaiting matching acknowledgement; stale results do not clear newer requests.
- existing openSettings optional page='models' navigates to model management; native route validates allowed pages.
- existing send keeps its schema; service binds current per-session selection atomically when accepting a submission. Web prevents stale-selection send while awaiting selection acknowledgement or includes selection identity validated by service; coordinate implementation.

## Native store API (backend owns implementation)

SettingsStore(context): preserve load()/save(JSONObject) compatibility for legacy consumers/tests, never discard old keys/settings on migration.
- registry(): JSONObject -> secure native-only {connections:[{id,name,provider,api,baseUrl,apiKey,revision,models:[{id,name,...capabilities/limits}]}], defaultSelection:{connectionId,modelId,thinkingLevel}}
- saveConnection(connection: JSONObject): String -> id; native passes whole edited connection, store validates and bumps revision.
- deleteConnection(id: String)
- setDefaultSelection(connectionId: String, modelId: String)
- modelOptions(): JSONArray -> above sanitized choices
- modelMetadata(provider: String, api: String, modelId: String): JSONObject -> catalog-derived supported capability/limits/thinking details for native form. Explicitly distinguish unknown metadata.

Native forms and backend may agree additional APIs; notify root of shape changes. Passwords stay in existing Keystore-encrypted storage, never Preference persistence/savedInstanceState. Ordinary UI preferences use native preferences.

## Behavior

Model controls sit immediately above message input, fixed with composer. Model picker groups saved models by connection, with Manage models link. Thinking options reflect selected model; reasoning display remains folded independently.

Per-session selection persists; new sessions use default. Switching A/B restores own choice. Existing/queued legacy work receives an explicit migrated binding without execution. Queue item binds nonsecret connection/config revision + model + thinking at submission; edits/default changes cannot silently change its provider/model. Resolve keys only native at execution; missing/deleted config fails visibly and holds item, never silently fallback. Keep retained config versions encrypted as needed.

Active run keeps configuration; steer uses active run. Explicit continuation uses suspended owner's current selection (never browsing session). Selecting a model does not connect phone/call API/change queue pause. Runtime resolves and validates actual supported thinking via Pi APIs and metadata; preserve existing Pi defaults when no explicit override, especially migrated settings. Reuse Pi, no second loop.

Settings full-screen native page, same warm-white/green style. Categories: models/API; permissions; UI (overlay preference); help/diagnostics. Execution specs/data statements can be factual read-only info, no placeholder switches. Dialog only for bounded edits/confirmation; model editor is page. Back/rotation preserves legitimate editing state but no secret saved to plaintext saved state. Opening/closing settings leaves task/session intact.

Diagnostics must use existing coordinator safely only when execution/manual/continuation queue ownership allows; no model tests on live provider without user's distinct action. This development run makes no live model API requests. Any optional online probe must be isolated and use fixed synthetic data, not phone/session data.

Settings changes notify service via settingsChanged command or Activity service method, to refresh sanitized catalog without execution. Overlay preference 'execution_overlay_enabled' persists; service shows only during active run/handoff/manual when authorized, never repeatedly requests permission.

Preserve configuration, Pi history, device permissions. Add meaningful migration/binding/capability/security/UI tests. Agents do not run Gradle concurrently; root centralizes Android and device validation. Frontend agent runs frontend tests. Backend runs local Pi/unit scripts as needed. No installation/device access by agents.
