# BBUI 流式聊天改造验收

日期：2026-09-26。设备：vivo V2366GA，Android 16 / API 36，arm64，Shizuku shell UID 2000。

## 后续实机反馈修复：等待提示、折叠与操作意图

查看用户当前手机画面及会话类型后，确认已完成的工具消息仍出现“正在准备回复”。根因是 assistant-ui 在工具结尾也调用 Empty 渲染器，原实现无条件显示等待；现 Empty 不渲染内容，只有整条消息处于生成中且无有效正文、思考或工具时才显示一次等待。空正文、已完成的空思考也不生成空卡片。

真实 reasoning 保持默认折叠；工具调用同条消息的普通叙述新增“执行说明”折叠区，最终纯正文回复保持展开，不改写模型的 reasoning_content。仅工具消息不显示空复制按钮。

phone_action 新增可选顶层 `意图`（最长 80 字符），提示模型描述下一步目标，例如“点击打开月度账单”“下滑列表”“搜索天气”。卡片标题优先采用意图；旧历史没有此字段时保留操作名，不补造历史意图。该字段不传入设备参数，不影响动作编号或真实成功/失败状态。新增标题清理、长度限制、历史恢复和桥接隔离测试。

本轮已通过：前端 6 项数据测试和 Chrome 真实组件回归、Android app 22 项 JVM 测试、Android bridge 9 项、Pi SDK 3 项、typecheck、lintDebug（零错误）、仓库外生产 ZIP 三轮本地模拟/恢复。实机本地 Pi 工具循环通过，未发起真实云模型请求。

最终界面真机 6 项均通过（生命周期项单独复跑 5.985 秒），包含用户现有会话恢复后无等待占位、默认折叠，以及合成工具意图标题、滚动与复制。后台返回脚本已修正为独立 shell 模拟用户返回，并获取可能重建的当前 Activity；之前失败来自测试检查旧 Activity/依赖后台进程自唤回。生产后台策略未修改。
合成效果截图：`runs/android-chat/chat-ui-intent.png`。

本轮 APK SHA-256：`78a350b22b3d34197e9db8bef4e51fec014da230dfd149dfb6405e5fb115ad77`。
本轮生产 Pi payload：`368590ccddcab60ec1ee4368cdc06f1bf4cc012a62cabd9f3d9b0c77a314d078`。
下面保留首轮改造的验收基线与产物校验值。

## 已实现

- APK 内置 React 19 + assistant-ui ExternalStoreRuntime；独立 npm lockfile，约 697 KB JS/CSS，无 CDN 或第二套 Agent。
- 连续 Markdown、复制、默认折叠思考及工具步骤；工具参数不进入正文，失败工具不会显示成功。
- 原生顶栏提供设置、画面及停止。虚拟屏预览可收起、全屏、接管；WebView 故障不影响原生停止。
- ChatStore 按运行代次、消息、内容块及工具编号归并；50 ms 合并界面刷新，终止立即刷新，message_end 校正内容。
- 完整显示历史保存在 noBackup AtomicFile；最后 40 条起步分页，向上阅读不自动拉回。Pi get_messages 完成后才提交新指令。
- 按 Pi 原始 role/timestamp 合并历史，保留未落盘的中断回复及被上下文压缩移除的旧显示记录；缓存不回注模型或重放动作。
- DeepSeek 已知型号使用 Pi 自带能力元数据，保留已有思考设置与工具循环所需 reasoning_content。
- WebView 限定本地来源及主框架，固定命令白名单，阻止远程资源、目录穿越与模型内容脚本执行。密钥、截图、原始工具参数不进入聊天状态。

## 自动验证

| 检查 | 结果 |
|---|---|
| Python unittest | 42 通过 |
| Pi SDK / 扩展测试 | 3 通过 |
| Android HTTP bridge | 7 通过 |
| 模型配置 / RPC 时间戳 | 6 通过 |
| 根 TypeScript typecheck | 通过 |
| 前端数据回归 | 6 通过 |
| Chrome 真实组件集成 | 1 通过；Markdown、复制、折叠、滚动、分页、发送及停止 |
| Android app JVM | 20 通过：ChatStore 13 + EventPresentation 7 |
| Android device JVM | 16 通过 |
| Android runtime JVM | 4 通过，含超时与历史恢复竞争 |
| Android lintDebug | 0 错误，21 项非阻断警告 |
| 精确生产 ZIP 隔离验证 | 仓库外临时目录，三轮本地模拟及历史恢复通过 |

回放样本 `pi/android/fixtures/chat-events.json` 含 199 条实际 Pi 模拟运行事件；覆盖中文、Emoji、思考、多个工具及总结。
另测 UTF-16 任意边界、超过 24,000 字符的 Markdown、105 条历史、停止/迟到事件、错误终止、工具失败及历史去重。

## 真机验证

ChatWebView 5 项已通过：来源/资源隔离、重载无重复与脚本注入防护、键盘和预览、旋转/后台恢复同一服务、真实触摸滚动和系统剪贴板复制。
截图 `runs/android-chat/chat-ui.png` 来自手机 WebView，只含合成测试内容，不是模型实际执行记录。

最终 APK 已安装并保留应用数据：`android/app/build/outputs/apk/debug/app-debug.apk`。
SHA-256：`8dc64793f8d93ac9b499c707676ebb99b79d07e07d6d0701b3962bee8ced9b55`。
生产 Pi payload SHA-256：`d1c50e2cafcf459b739e32e48b251be7ede43f2fff0addb7934988f05282a68f`。

最终包组合真机验收 **10/10 通过，146.287 秒**：ChatWebView 5 项、Pi 模拟循环 1 项、Pi 进程重启 1 项、截图/输入隔离 1 项、扩展生命周期 2 项。
扩展项包含中文剪贴板输入、预览脱离 60 秒后仍能截图、动作去重、旧接管代次拒绝、当前接管可用，以及重连后输入。
另经真实原生界面验证：设置中连接设备、展开预览、接管/继续、收起预览、显示悬浮控制条并点击停止，全部通过；没有发送模型请求。
手机最终保留新 APK，悬浮条已关闭，执行已停止。测试 APK 在验收后卸载，未清除主应用数据。

## 发现并修复的问题

1. 同 revision 的历史分页被前端误认为重复快照；现可扩容且保持阅读位置。
2. Pi 当前上下文覆盖显示历史导致丢失中断回复；现按原始消息身份合并。无结果的历史工具标为停止。
3. 错误状态先于 message_end 到达导致新建重复消息；现保留当前消息供终止事件校正。
4. 运行时配置模块误依赖仓库顶层开发包，APK 内找不到；改用 Pi 自身依赖解析路径，测试移出仓库以避免假通过。
5. vivo 会冻结无前台 Activity 的仪器测试；运行时和设备测试现在显式持有前台测试宿主，不修改系统后台策略。

## 时间与剩余边界

Pi RPC 每条输出携带 `piEmittedAtMs`，原生记录 `runtimeReceivedAtMs`、`nativeReceivedAtMs`、`projectionAtMs`，浏览器帧回调确认后记录 `renderedAtMs`。日志标签为 `BBUI.ChatTiming`，不记录消息或密钥。
这些字段区分 Pi 到原生、50 ms 合并及 WebView 呈现开销；Pi 写出时刻不等于模型 HTTP 数据抵达时刻。
本次真机本地模拟记录 49 条事件，连续重启记录 96 条事件：Pi 写出至原生接收中位数 0 ms、最大 1 ms；原生接收到主线程回调最大分别 6 ms、7 ms（毫秒精度）。这不是云端首字速度，也不是完整 UI 延迟；真实任务的界面帧确认日志待联网验收时一起采集。

本轮所有模型验证使用本地模拟服务，未向 DeepSeek 发送请求。真实首字延迟、云端流式节奏及具体 GUI 任务效果仍待单独授权联网验证；不能把未测网络延迟列为已修复。
保留已有加密配置、Pi 历史和设备授权；未实现多会话列表、附件、语音或自动重新生成。
