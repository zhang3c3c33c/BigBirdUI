# BBUI Android implementation

This is the Android-local app. The other supported user-facing product is the Windows desktop app. Standalone Python/Pi launchers are retained as internal development tools.

## Ownership and integration

The main agent owns root build configuration, `core`, `AppCoordinator`, shared Pi contracts,
integration, and the only device-operation lease. Runtime, device, and UI subagents own their
respective modules. Shared interface changes are agreed before implementation. Do not run
competing ADB inputs, install operations, or device tests from worker agents.

The checkout initially had no Git commits and contained existing untracked source. No
automatic cleanup, reset, baseline commit, or worktree replacement is part of this migration.

## Build

Requires JDK 17, Android SDK 36, NDK 28.2.13676358, CMake 3.22.1, Node, and installed npm dependencies.
Run `powershell -File scripts/build-android.ps1` from the repository root. The host downloads
and verifies the pinned Node library, prepares the original Pi payload, and builds the APK.
No npm installation or external Node runtime is required on the phone.

The build also installs the locked, independent `android/chat-ui` dependency tree and builds
React/assistant-ui into APK assets. These dependencies never enter the Pi ZIP. For incremental
native-only builds, use `-SkipChat -SkipRuntime` after both asset bundles have been prepared.

## Streaming chat

`AssistantService` owns `ChatStore`, not the Activity or WebView. Pi messages are projected
into stable message/content/tool identities. Text and reasoning deltas accumulate separately;
message_end corrects the accumulated content. Rendering is coalesced at 50ms, while terminal
events flush immediately. A private AtomicFile display cache preserves complete history and
partial replies across process death; interrupted work is never resumed automatically.

Pi get_state/get_messages must complete before a prompt can be sent. Display history merges
by Pi role/timestamp rather than replacing older rows with current model context. The latest
40 messages are shown first, with 40 more per page. Pi alone owns model context and reasoning
protocol fields. Known DeepSeek models use Pi's native metadata and existing thinking settings.

`ChatWebView` loads only bundled assets through AndroidX WebViewAssetLoader. The exact-origin,
main-frame bridge accepts a small UI command whitelist. CSP and interception block remote
resources; external links require a user gesture and open outside the WebView. Keys remain
native; screenshots and raw tool arguments never enter the display projection. Native STOP,
preview, takeover and settings remain available when WebView fails.

See `docs/contracts/ANDROID-CHAT-CONTRACT.md` for integration ownership, event shapes and bridge operations.
Local diagnostics log only timing fields under `BBUI.ChatTiming`: Pi RPC emission, native
transport receipt, reducer receipt, projection and browser paint acknowledgement. These measure
client delivery and rendering; they do not establish the model provider's network latency.

The initial target is vivo V2366GA / Android 16 / arm64, using Shizuku with shell UID 2000.
The application ID is `io.bbui.assistant`. Shizuku and overlay permissions are separate
Android authorizations. Start Shizuku before connecting, then grant the app access.

## Required gates

1. Runtime: clean APK startup, native Node/ESM/WASM/worker support, original Pi RPC, two
   image/tool rounds against a local mock provider, cancellation and process restart.
2. Display: APK-owned Shizuku UserService, original scrcpy 4.1 virtual display, isolated
   screenshots and targeted input, rotation/stale-observation rejection, preview detach,
   manual takeover, STOP and process/binder failure cleanup.
3. Integration: actual configured vision API, WeChat self-message fixture, Bilibili search,
   shopping comparison with no purchase. Missing credentials or untested app compatibility
   must be reported explicitly, never counted as a pass.

The debug runtime test calls a local mock provider and cannot operate real apps. The mock
bridge permits only observation operations. Every run rotates its bearer token to revoke
old requests. Runtime failure never silently switches to Termux, root, accessibility, or a
computer-hosted agent.

## Execution invariants

Only the `virtual` screen is actionable. Model screenshots come from the decoded virtual
screen texture, not the preview window. Physical-screen controls never enter that texture.
Unknown screen ownership is an error. Screenshots expire and are consumed before input.
Action IDs are recorded before dispatch; uncertain actions must not be automatically retried.

STOP revokes the executor and manual-input generation immediately, before waiting for Pi.
The runtime clears queued prompts before abort. A stalled runtime is closed after a bounded
grace period; the next run starts a fresh process. Locking the screen or removing the app task
stops execution. Resuming requires new observation.

## Baseline recorded before integration

- Python: 42 unit tests passed.
- TypeScript: typecheck and 3 Pi tests passed, including actual SDK/mock image consumption.
- Shared contract extraction: the same TypeScript checks passed again.
- Device initially connected and Shizuku server confirmed as shell UID; Android-local
  behavior is not considered verified until the gates above run.

Final device results and remaining limits are recorded separately in `docs/reports/ANDROID-TEST-RESULTS.md`.
