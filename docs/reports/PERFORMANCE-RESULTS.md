# Android 第一轮性能优化

2026-09-27。针对真实启动 OOM 和实际重复工作进行优化，不改 Agent 决策、模型上下文或手机执行授权。

## 依据

- [Android 性能测量](https://developer.android.com/topic/performance/measuring-performance)：先定位再优化，区分启动、内存和渲染问题。
- [Android 启动优化](https://developer.android.com/topic/performance/issues/launch-time)：减少启动路径上的无用初始化与 I/O。本轮复用已安装 Pi 0.87.0 `SessionManager.listAll` 元数据，不再为了列表逐个打开完整会话；未重写 Pi JSONL。
- [Android 自定义 View 优化](https://developer.android.com/develop/ui/views/layout/custom-views/optimizing-view)：避免多余 invalidate 和布局工作。本轮控制权/动画状态不变时不重复触发边缘刷新。
- [assistant-ui ExternalStoreRuntime](https://www.assistant-ui.com/docs/runtimes/custom/external-store)：沿用既有外部状态适配及消息引用复用，不引入另一套运行时。隐藏页面只保存最新快照，重新可见时一次同步。

## 闪退证据与修复

手机 03:34、03:40 的多次崩溃均为 `EmbeddedPiRuntime` 的 `BufferedReader.readLine()` 发生 `OutOfMemoryError`，单次尝试分配 268,501,000 字节。启动恢复的真实会话文件为 103,484,220 字节，正文内容字段占绝大部分；此前将包括工具图片在内的原始历史整包发送到 Android，展示层过滤发生得太晚。

- 在 Node UI 出口投影历史和实时事件：正文、实际思考、工具调用 ID/意图、失败标识、执行/观察状态和任务记录保留；工具图片及原始诊断不进入 UI 管道。
- 历史查询在 JSON 序列化之前投影；实时 RPC 同样处理 message、agent_end、turn_end、tool result/update 载体。
- Pi 原始会话及发给模型的上下文保持原样。测试验证原始会话文件字节未变。
- Android 使用有界 JSONL 读取，单条超过 4Mi 字符明确终止传输并进入现有错误处理，不继续分配内存，不截断后冒充成功，不重放动作。
- 同时处理 scrcpy 日志线程在正常关闭时抛出的 IOException，避免清理过程因日志管道关闭而终止特权服务。

## 其他优化

- 会话目录按文件名、大小、修改/变更时间验证缓存；未变化时复用 Pi 索引。新建、重命名、删除主动失效；外部 Pi 写入同样触发重读。只缓存目录元数据，不保留完整 SessionManager 或 allMessagesText。
- 侧边栏状态、等待用户判断及运行代次校验改用轻量访问器，不再为查询一个状态构造消息全文快照。
- 聊天页隐藏、打开独占预览或退后台时暂停 WebView 渲染推送；保留最新快照，返回时恢复。Agent 接收、持久化、STOP 与队列继续正常工作。
- 彩边控制权和动画状态未变时不重复 invalidate；仍保持原来的可见范围和内发光效果。

## 可复现基准

命令：`node scripts/benchmark-android-catalog.mjs`。Windows 本地合成历史，约 96MiB，97 条消息；非真实模型端到端延迟。单次测量只说明此数据集的开销变化，不作为所有设备的固定提速承诺。

| 指标 | 修改前 | 修改后 |
|---|---:|---:|
| 列表首次读取 | 717.8ms | 371.3ms |
| 未变更列表再次读取 | 每次完整扫描并打开历史 | 0.5ms |
| UI 历史 JSON 大小 | 100,678,610 字节 | 12,914 字节 |
| 消息数量 | 97 | 97 |

基准结果：`runs/performance-benchmark.json`。

## 验收

- Android JVM：app 60、device 20、runtime 7 项通过。
- Android Pi Node 回归 22 项、项目 Pi 回归 11 项、Python 62 项通过；TypeScript 检查通过。
- vivo V2366GA / Android 16 真机 5 项通过，无跳过：真实大历史加载并保持响应；隐藏时 100 次更新不发送、恢复只发送第 199 版；WebView 重载；预览控制状态；真实虚拟屏接管触摸。
- 另 1 项本地模拟模型真机回归通过：A→B→A 串行执行、提交去重、取消排队、浏览不切换执行会话、Pi 退出后目录恢复、STOP 暂停等待任务；临时会话清理并恢复原会话。日志 `runs/performance-session-device.log`。
- 闪退修复版在原始大历史上单独通过一次冷启动恢复验收，优化版再次通过。未调用真实模型 API。
- 构建与验收日志：`runs/startup-memory-*.log`、`runs/performance-build.log`、`runs/performance-node.log`、`runs/performance-device.log`。
- 已覆盖安装并移除临时测试 APK。最后一次普通冷启动 Activity TotalTime 为 612ms（单次首屏测量，不是完整历史加载耗时）；随后仍为同一存活进程，无新增主进程崩溃，原有 5 条会话保留，无排队或运行任务。证据：`runs/performance-final-launch.log`、`runs/performance-final-health.json`。

## 本轮边界

未降低截图质量或分辨率来掩盖问题。原始图片仍由 Pi 保存和管理；展示投影并不减少模型所见图片。长历史的界面分页维持现有实现，超大纯文本单条 RPC 会报传输错误，不静默丢弃。真实网络首字延迟需另获真实模型测试授权后测量。
