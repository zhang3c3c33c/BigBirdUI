# 文档索引

使用入口与安装步骤见 [项目首页](../README.md)。以下文档中的命令和代码路径默认相对仓库根目录；Markdown 链接相对所在文件。

`reports/` 保存特定日期、设备和构建的历史结果，不代表本次重新验收。报告引用的 `runs/` 为本机证据，默认不公开；旧设计中的任务分工和讨论不构成当前执行授权。当前工具行为以契约和实现为准。

## 使用指南

- [Android 本机使用](../README.md#android-本机使用)
- [电脑连接 Android](../README.md#电脑连接-android)
- [电脑连接 iPhone](../README.md#电脑连接-iphone)

面向用户的入口为 Windows 桌面应用和 Android 本地应用。

- [BigBirdUI · 大鸟手机助手 Windows 桌面端](guides/DESKTOP.md)
- [Android 本地版构建与架构](development/ANDROID-IMPLEMENTATION.md)
- [iPhone USB 能力清单](guides/IOS-DESKTOP-CAPABILITIES.md)
- [无 GUI 能力接入状态](guides/NON-GUI-CAPABILITIES.md)

## 内部开发与历史接口

以下入口不作为对外支持的使用方式，仅用于理解应用内部实现、开发调试和查阅历史兼容行为。

- [Pi 命令行调试](development/PI-HARNESS.md)
- [内部执行器与 MCP 协议](development/UNIFIED-TOOL.md)
- [旧版 CLI 与 MCP 接口](development/LEGACY-CLI.md)
- [内部 MCP 调试示例](development/examples/mcp.unified.example.json)
- [旧 MCP 调试示例](development/examples/mcp.example.json)

## 接口与行为契约

- [Android chat integration contract](contracts/ANDROID-CHAT-CONTRACT.md)
- [Control handoff implementation contract](contracts/ANDROID-CONTROL-CONTRACT.md)
- [BBUI Calendar tool contract](contracts/CALENDAR-TOOL-CONTRACT.md)
- [iOS desktop implementation coordination](contracts/IOS-IMPLEMENTATION-CONTRACT.md)
- [Structured non-GUI additions (2026-09-28)](contracts/NON-GUI-TOOLS-CONTRACT.md)
- [Settings/model selection implementation contract](contracts/SETTINGS-IMPLEMENTATION-CONTRACT.md)
- [手机工具契约](contracts/TOOL-CONTRACT.md)
- [BBUI 工具职责审查](contracts/TOOL-DESIGN-REVIEW.md)
- [System and agent tools implementation contract](contracts/TOOLS-IMPLEMENTATION-CONTRACT.md)

## 开发与发布

- [产品定位与介绍约定](development/PRODUCT-POSITIONING.md)

- [任务控制与人工交接：行业调研](development/AGENT-CONTROL-RESEARCH.md)
- [Agent 任务记录与自主推进](development/AGENT-TASK-DESIGN.md)
- [BBUI Agent 界面调研与布局建议](development/AGENT-UI-RESEARCH.md)
- [BBUI Android implementation](development/ANDROID-IMPLEMENTATION.md)
- [首次公开发布清单](development/RELEASE-CHECKLIST.md)
- [scrcpy 接入修复与复核（2026-09-28）](development/SCRCPY-INTEGRATION-REVIEW.md)
- [scrcpy 输入通道接入](development/SCRCPY-INTEGRATION.md)
- [BBUI 独立设置页调研与规划](development/SETTINGS-RESEARCH.md)
- [Android storage lifecycle](development/STORAGE-LIFECYCLE.md)

## 历史验收记录

- [开源准备记录](reports/OPEN-SOURCE-PREP.md)

- [Agent 任务能力验收](reports/AGENT-TASK-RESULTS.md)
- [BBUI 流式聊天改造验收](reports/ANDROID-CHAT-RESULTS.md)
- [Android 本地版验收记录](reports/ANDROID-TEST-RESULTS.md)
- [日程工具交付与验收](reports/CALENDAR-TOOL-RESULTS.md)
- [问题卡片与聊天滚动优化](reports/CHAT-SCROLL-RESULTS.md)
- [输入框发送、停止与队列补充](reports/COMPOSER-CONTROLS-RESULTS.md)
- [任务控制交接实现与审查](reports/CONTROL-HANDOFF-RESULTS.md)
- [桌面端断线恢复验收（2026-09-29）](reports/DESKTOP-RECONNECT-RESULTS.md)
- [Windows 桌面验收记录](reports/DESKTOP-RESULTS.md)
- [2026-09-28 任务受阻根因](reports/DEVICE-BLOCKAGE-ROOT-CAUSE.md)
- [虚拟屏生命周期](reports/DISPLAY-LIFECYCLE-RESULTS.md)
- [iOS 无辅助 App 路线实机验证](reports/IOS-CAPABILITY-VALIDATION.md)
- [iPhone USB 桌面接入验收记录](reports/IOS-DESKTOP-ACCEPTANCE.md)
- [三协议配置与截图请求预算](reports/MODEL-API-RESULTS.md)
- [供应商模型列表](reports/MODEL-DISCOVERY-RESULTS.md)
- [提问提交修复与无 GUI 工具验收](reports/NON-GUI-TOOLS-RESULTS.md)
- [初次使用与日常权限恢复](reports/ONBOARDING-RESULTS.md)
- [后台任务状态胶囊](reports/OVERLAY-STATUS-RESULTS.md)
- [Android 第一轮性能优化](reports/PERFORMANCE-RESULTS.md)
- [手机操作 Skill 实施与验收](reports/PHONE-SKILL-RESULTS.md)
- [执行画面：工具意图与边缘状态](reports/PREVIEW-EXECUTION-RESULTS.md)
- [新消息与队列暂停：调研、修复与验收](reports/QUEUE-SUBMISSION-RESULTS.md)
- [scrcpy 虚拟屏输入实测（2026-09-26）](reports/SCRCPY-INPUT-RESULTS.md)
- [侧边栏会话管理与任务排队验收](reports/SESSION-MANAGEMENT-RESULTS.md)
- [会话自动命名](reports/SESSION-TITLE-RESULTS.md)
- [设置与输入区模型选择验收](reports/SETTINGS-RESULTS.md)
- [真机测试结果](reports/TEST-RESULT.md)
- [工具职责改造验证](reports/TOOL-RESULTS.md)
- [系统能力与 Agent 工具交付记录](reports/TOOLS-IMPLEMENTATION-RESULTS.md)
- [简洁聊天界面改造](reports/UI-REFACTOR-RESULTS.md)
