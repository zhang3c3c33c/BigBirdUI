# Agent 任务能力验收

日期：2026-09-26。实现说明见 `docs/development/AGENT-TASK-DESIGN.md`。

## 交付

- `phone_action.意图` 必填；缺失、空白及超长调用均不派发到手机。旧历史继续按操作名展示。
- `task_state` 通过 Pi 原生会话工具结果持久化，并从当前分支恢复；上下文压缩后仍可获得最新任务笔记。
- 增加观察后决策、保留已知约束、限定偏好适用范围及真正缺少用户决定时才提问的模型指导。
- 聊天界面增加默认折叠任务记录，区分运行中、等待用户、模型报告完成、受阻、停止、错误和状态未确认。
- 没有新增 Agent 循环、数据库、框架依赖或自动续跑；任务状态不成为手机执行门槛。

## 自动化结果

| 验证 | 结果 |
| --- | --- |
| Python unittest | 62 项通过 |
| 桌面 Pi 测试 | 11 项通过 |
| Android TypeScript bridge | 15 项通过 |
| 任务记录测试 | 8 项通过，含真实 Pi 同消息 update→read、分支、压缩、落盘重开、新任务替换和无手机动作恢复 |
| 模型配置与 RPC 时戳 | 6 项通过 |
| TypeScript typecheck | 通过 |
| Android 应用 JVM | 35 项通过，其中任务状态 11、聊天 17、事件展示 7 |
| Android 设备 / 运行时 JVM | 16 / 4 项通过 |
| 前端数据 / Chrome 浏览器 | 8 / 1 项通过 |
| 不带 --device 的 MCP smoke | 通过 |
| 实际 APK Pi 载荷，本地模拟模型 | 3 个 Pi 进程、19 次 localhost 请求；意图缺失校验及修正、任务生命周期、上下文注入、重启恢复、gate 隔离、reasoning_content 与图片往返通过 |

真实 Pi 事件夹具由 `scripts/android-runtime/write-chat-fixture.mjs` 清洗本地模拟记录生成，共 973 个事件；图片与认证字段不保留。JVM 回放核对原始中文、Emoji、Markdown、思考及工具隔离，并检查失败意图调用、有效观察及最终等待用户状态。

交叉审查修复了两个状态问题：正常 active 任务继续操作后不应变成此前记录；旧用户轮次遗留的未完成工具不应使新任务完成状态降级。

## 真机

设备：vivo V2366GA，Android 16 / API 36，USB 序列号 `REDACTED_DEVICE_SERIAL`。

`ChatWebViewTest` 7 项和 `RuntimeGateTest` 1 项全部通过。新增任务详情用例又以实际触摸重跑一次通过；验证默认折叠、约束与事实展示、WebView 重建、旧完成状态不冒充当前结果。其余测试覆盖滚动、复制、键盘、原生预览、旋转和后台恢复。

手机内嵌 Pi 使用本地模拟服务完成两次观察和一次任务状态更新；确认工具事件保留意图、任务完成记录含观察依据，`task_state` 不进入手机输入桥。没有真实模型联网、点单或其他业务应用操作。

可视证据：`runs/agent-task/task-state-ui.png`（合成测试内容，展开任务详情）；报告：`runs/agent-task/runtime-gate.json`。截图已人工查看，任务面板展开后独立滚动，不遮住底部输入框。

安装使用覆盖升级，真实聊天仍为 52 条；本地模拟未覆盖真实历史。验收后移除临时测试 APK，重新打开 BBUI。

- APK：`android/app/build/outputs/apk/debug/app-debug.apk`
- APK SHA-256：`930c31385d5ab280efe18fddd7b7b10ceab1e3303cbf7c8627432ca3800ee281`
- Pi 载荷 SHA-256：`ab4f246e3445146cd54ec4b91ea0e5fdadab92a3d89e57c1b1c51c636e7137a5`
- 手机安装包哈希已与上述 APK 核对一致。

## 尚未验证的行为

以上证明参数校验、记录恢复、界面及实际 Pi 协议链路正常，不能证明真实模型已减少无必要提问。本轮没有发送真实 DeepSeek 请求；模型自主决策效果需在另获联网授权的真实任务中评估。历史中未生成的操作意图不会补造，新调用才执行必填契约。
