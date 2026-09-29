# 输入框发送、停止与队列补充

2026-09-29。手机和 Windows 使用同一 React 输入框。

## 约定

- 当前会话正在执行且草稿为空：右下角显示停止图标。
- 输入非空文字后：同一位置显示发送箭头，默认加入队列；成功保存并清空对应草稿后恢复停止。
- 提交失败保留草稿；停止不清草稿。有草稿时仍可使用上方全局停止入口。
- 浏览其他会话时，输入框保持发送语义；停止正在运行的会话使用上方全局控制。
- 从已排队消息选择“立即补充”。仅当前运行会话可用，绑定会话、控制权与运行代次；等待回答、接管或已结束时拒绝过期操作。
- 队列消息在派发前持久化移出 FIFO，避免同时成为补充和下一轮任务。保存失败且未派发可恢复队列；派发结果不确定时保留原文与实际接收状态，不自动重发。
- 复用现有 Pi steering 和宿主 STOP，不新增模型工具、设置项或平台专属界面。

## 调研依据

- [assistant-ui Composer](https://www.assistant-ui.com/elements/composer)：发送与停止共用主操作位置。
- [Codex Queue 与 Steer](https://developers.openai.com/blog/mastering-codex-remote-for-engineering)：排队等待本轮结束，Steer 补充进行中的工作，默认行为可设置。
- [LibreChat Steering and Queued Messages](https://www.librechat.ai/docs/features/agents#steering-and-queued-messages)：队列消息可以转为当前执行的补充。

## 验证

| 检查 | 结果 |
| --- | --- |
| 共享界面 | TypeScript、构建、18 项单测、2 套浏览器回归通过 |
| 浏览器交互 | 空草稿停止、有草稿排队、确认后清空、失败保留、跨会话、待回答与原消息代次绑定通过；393×780 截图目视检查通过 |
| Python / Pi | Python 90 项、Pi 11 项通过，根目录 TypeScript 检查通过 |
| 桌面宿主与实际 Pi | 67 项宿主测试通过；最终实际 Pi + 本地 HTTP 集成通过，覆盖队列转补充只消费一次、没有额外普通任务、配置绑定与新草稿保留 |
| 桌面滚动回归 | 自动跟随流式增长、视口约束和阅读位置保留通过 |
| Android | APK / 测试 APK 构建通过；194 项 JVM 单测通过 |
| vivo V2366GA / Android 16 | `QueuedSteeringDeviceTest` 通过（18.173 秒）：真实手机 Node / Pi 运行时消费一次补充，不另起 FIFO 任务，不清新草稿 |
| Windows 产物 | 免安装包启动、欢迎页、设置加密、异常退出和正常关闭通过；主进程、预加载与共享 UI 文件校验和均匹配构建产物 |

模型测试使用本地模拟服务，不使用用户云 API。

手机测试结束后，14 个原有会话、加密配置、派生模型配置和记忆等受保护文件的 SHA-256 全部保持一致，没有临时 Pi 会话残留。手机任务未自动重跑。

手机版已覆盖更新，Windows 新版已重新打开。原有桌面加密设置保持不变，之前停止的任务没有自动重跑。

## 审查修正

- 明确拒绝与传输未知分别记录，不能把已知接收事实变成未知。
- 请求超时后仍在原运行时生命周期内核对迟到回执，不重发，也不操作新任务。
- 区分“可以新发补充”和“原任务仍存在”：随后出现提问不会把已接收补充误报为任务结束。
- 领取保存失败的回滚不能撤销 STOP，也不能复活用户删除的内容。
- 回滚依据仍存在的前后队列项恢复顺序，避免等待期间取消前一项后插错 FIFO 位置。

产物：`.desktop/releases/BBUI-Windows-x64.zip`、`android/app/build/outputs/apk/debug/app-debug.apk`。

Android APK SHA-256：`ee7ae00f28a706c22618ea70bad0f8840e46d9f41ff282c202d9190adfbfb3ce`。

Windows ZIP SHA-256：`93f2a63e47c4d1efe648693062c480d95e66e9a1f6ed293f0b0e37d4e1f6db94`。
