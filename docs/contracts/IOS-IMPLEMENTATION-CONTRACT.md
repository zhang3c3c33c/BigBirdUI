# iOS desktop implementation coordination

Scope: Windows x64, USB only, pymobiledevice3, DVT screenshots at 15 FPS target. No phone helper app, AirPlay, network-control fallback, or driver changes. Existing Android behavior and lock locations must remain compatible.

## Shared contract

- Settings add `devicePlatform: 'android' | 'ios'`; missing means android. Existing `serial` remains the transport identifier. Stable logical device ID is platform plus serial (iOS serial normalized by stripping hyphens and uppercasing). Host binds submissions to this identity and prevents device changes while active/queued.
- `snapshot.desktop` retains existing fields and adds `devicePlatform`, `deviceId`, and `connectionStatus: { code: string, message: string }`.
- Existing `snapshot.capabilities` retains computer `platform`; add optional `devicePlatform`, `phoneOperations: string[]`, `phoneKeys: string[]`, `systemOperations: Record<string,string[]>`, `appRestrictions: boolean`, `previewMode: 'scrcpy' | 'screenshots'`, `previewFps: number`. Defaults preserve Android rendering. Host owns the authoritative snapshot.
- Device host `status` returns existing `connected`, `stopped` plus the same capability fields inside `capabilities`, and optional `connectionStatus`. iOS main screen only; `previewSources` returns []. Model `列出屏幕` returns the existing Chinese screen-list shape with main.
- iOS entrypoint is `bbui.ios_mcp`; existing MCP `phone_action` and `system_action` signatures and authenticated localhost `/control` protocol stay compatible. Do not import the Android ScreenRegistry merely to reuse response formatting. Backend file reads `BBUI_CONFIG_FILE`, `BBUI_RUNS_DIR`, `BBUI_DEVICE_STATE_DIR`, `BBUI_HOST_TOKEN`, `BBUI_HOST_ENDPOINT` like the current backend.
- Host operations remain `status`, `stop`, `hold`, `manual`, `resume`, `frame`, `input`, `previewSources` as applicable in current host. `frame` remains request-driven with `screen`, optional `afterFrameId`, returning null or existing frameId/screen/width/height/image shape. No continuous capture without a frame request. iOS snapshot capture is single-flight, latest-only; explicit model observation gets a fresh post-action capture, independent of preview IDs.
- Host supplies Pi bootstrap/runtime environment `BBUI_DEVICE_CAPABILITIES` as JSON containing these device capability fields, so tool registration and prompts expose only supported operations. Device selection/capability change requires fresh runtime before next model execution; never swap tools beneath an active run.
- iOS initial operations: 查看, 等待, 点击, 双击, 长按, 滑动, 拖拽, 放大, 缩小, 输入内容, 删除内容, 全选, 按键, 打开应用, 列出应用, 列出屏幕. Keys: 主页, 最近任务, 回车, 删除, 向前删除, 上, 下, 左, 右. No 返回, 系统面板, virtual screens, or nodes. Portrait input only until separately accepted; changes invalidate prior frames.
- iOS system operations: apps=list/details/launch/force_stop; clipboard=read/write/clear; files=list/stat/search/read_text/write_text/mkdir/copy/move/rename/delete. AFC phone media scope explicitly reported; no host paths or unrestricted app containers. Other groups omitted. appRestrictions=false on iOS; Android restriction behavior preserved.
- GUI/system/manual mutations share lock, STOP, gate generation and durable action deduplication. Preserve known dispatch independently from observation failure. Never replay uncertain input. Disconnect does not resume. Text insertion preserves clipboard unless intervening user changes; explicit clipboard mutations persist.

## Ownership of changes

1. iOS backend agent: new bbui/ios*.py and new tests/test_ios*.py, small reusable lock helper only if needed. No desktop/ or frontend changes, no real device access.
2. Host agent: desktop/*.mjs, pi/desktop/*, pi/phone-contract.ts, relevant pi/android runtime and tool registration modules, host/protocol tests. Do not edit frontend contract.ts (UI agent owns it), Python, or build scripts. Communicate necessary build changes to root.
3. UI agent: android/chat-ui/src and frontend tests. Implements the contract above, platform-aware labels/readiness/control keys; no host, Python, or build files. No real device access.
4. Root: dependency/build/package changes, integration corrections, docs, independent review, all actual device tests and release artifacts. Root coordinates shared-contract changes before agents diverge.

No agent commits or modifies user configuration, credentials, personal phone data or the reference BigBirdTouch project. Reference code may be read; product must not import anything from that project or runs/. Reuse the installed upstream APIs through a small maintained adapter, not a copy of the whole reference project.
