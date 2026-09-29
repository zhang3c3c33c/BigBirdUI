# Embedded Pi runtime

This Android library packages Pi 0.87.0 with the full arm64 library from the
community `fogtape/nodejs-mobile` v24.21.0-0 prerelease. It does not require Termux,
run npm on the phone, or lower the application's target SDK.

From repository root, run `node scripts/android-runtime/prepare.mjs` before the
Gradle build. The script verifies the pinned archive SHA256, extracts headers
and `libnode.so`, installs the repository's locked production npm dependencies
on the build host, compiles the existing phone schema and Android extension,
and generates an integrity-checked ZIP asset. The generated artifacts are ignored.
The preparation requires host Node, npm, Python 3, and network for the first run.

`EmbeddedPiRuntime` implements `io.bbui.core.AgentRuntime`. It binds a non-exported
`:agent` process, which uses JNI pipes to execute Pi's original RPC entry with
`node::Start()` on a dedicated thread. No child executable is spawned for Pi.
Pipe file descriptors cross Binder; screenshot data never enters Binder messages.
JSONL stdout is separated from stderr. A `runtime_ready` event is emitted only
after the original Pi `get_state` command succeeds. `runtime_boot` contains the
actual Node version and basic WASM/crypto feature checks.

Only one Node instance may run per agent process. Close the runtime, then create a
new instance to restart. Call the independent Android executor's STOP before
closing, aborting, or handing over control. To stop pending model continuations,
send `clear_queue` and then `abort`, and wait for `agent_settled`.

Config: `bridgeUrl`, `bridgeToken`, `apiKey`, `baseUrl`, `provider`, `model`;
optional `contextWindow`, `maxTokens`, and `gate` (default false). Real tasks use
Pi's `--continue` and a stable private workspace so APK updates preserve session
identity. Gate tasks always start new sessions in a separate gate directory.
The fresh-turn instructions invalidate historical observations and action IDs:
continuing a conversation never authorizes replaying an earlier phone action.
Initial model configuration uses Pi's
OpenAI-compatible protocol and declares text/image input. Secrets are app-private,
omitted from logs, and never passed on the native argv. The duplicate config key
is cleared after loading. API credentials remain in memory until process exit.

Acceptance gate: install only BBUI and Shizuku on the target device; verify
`runtime_boot`, `runtime_ready`, mock-provider two-round image/tool execution,
STOP plus abort/queue clearing, session persistence, and isolated process restart.
Do not claim native compatibility from host tests alone. A failing gate blocks
further rollout rather than switching to desktop Pi or a companion Termux APK.

The runtime upstream and Pi use MIT with bundled third-party notices. Preserve
the Node archive's license files and npm package license notices when distributing.
Source: https://github.com/fogtape/nodejs-mobile/releases/tag/v24.21.0-0
