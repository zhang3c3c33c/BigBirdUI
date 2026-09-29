# 三协议配置与截图请求预算

实施日期：2026-09-26。复用项目锁定的 Pi 0.87.0，不引入第二套模型 SDK 或 Agent 循环。

## 配置

设置 → API 协议提供以下选项，显式选择优先于模型名称：

| 显示名称 | Pi api |
| --- | --- |
| OpenAI Chat Completions | openai-completions |
| OpenAI Responses | openai-responses |
| Anthropic Messages | anthropic-messages |

Base URL 按 Pi 所用 SDK 的基地址填写：OpenAI 通常为 `https://api.openai.com/v1`，Anthropic 通常为 `https://api.anthropic.com`；不要填写完整的 chat/completions 或 messages 路径。第三方服务填写其文档中的对应基地址。

旧配置维持原路由；已知 DeepSeek、OpenAI、Anthropic 模型在协议匹配时继承 Pi 的元数据。显式切换协议时，创建所选协议的模型定义，不复制另一协议的 compat 参数。未知协议直接报告配置错误。

密钥继续由 Android 加密存储，Pi 配置仅引用运行时环境变量。界面仍为现有的单个活动模型配置，本次不扩展模型列表管理。Files API、供应商协议自动回退不在范围内。

## 截图与历史

- `context` 钩子仅整理发送副本；完整 Pi 会话、工具调用配对、文字执行结果和思考内容不在本地被删除。
- 默认保留最多 3 张 phone_action / phone_history 图片，以 Base64 字节计的目标预算为 12 MiB。最新真实观察优先，不会被历史回看挤掉；单张最新图过大时也不静默删除它，由最终传输预算报告问题。
- 旧图片用带原工具调用编号、内容块位置的归档提示代替，文字事实继续保留。
- `phone_history` 只读当前 Pi 分支的原始图片，压缩后仍可通过祖先条目回读；不访问手机，不创建新的动作凭证。回看历史后必须先查看当前手机状态才能操作。
- Pi 原有自动文本压缩及 task_state 恢复继续使用。Pi 的压缩器将对话序列化为文字，不将历史图片 Base64 拼入摘要请求。
- 对 Anthropic 思考签名的处理沿用 Pi 适配器及模型元数据。例如 Pi 的 managed-effort 模型使用官方 `thinking.block_binding.prefix_mismatch_behavior=drop_block`，由服务端处理失效的思考块。该行为可能减少模型继续使用的旧思考，客户端仍保存原始块。

## 最终传输边界

在 bootstrap 装入 Pi 前包装 fetch，对配置模型端点同源的请求检查最终 UTF-8 请求体大小，默认 24 MiB。这是 BBUI 的应用预算，不声称所有兼容服务都有相同服务端限额。

超过预算会返回有明确 BBUI 本地说明的 413，不访问网络；该状态不触发 SDK 的连接错误重试。预算覆盖通过此 fetch 的普通调用、摘要调用和 SDK 重试。其他源的本地 STOP/手机桥接不受模型预算约束。

典型的历史截图累积在 context 阶段自动减量。单张超大图片、超大文本或工具定义仍可能触发最终边界；不会静默截断正文或发出已知超限请求。目标服务若有更小限额，仍可能返回其自身错误。本次不添加自动重放手机动作或后台恢复业务任务。

## 验证

- TypeScript typecheck 通过。
- runtime-config / RPC timing / request-budget：8 项通过。
- screenshot-context：4 项通过，含实际 413 会话离线回放；50 张图片变为 3 张，投影消息 JSON 小于 12 MiB，源会话仍为 50 张。
- protocols：2 项通过。使用 Pi 的三个真实适配器调用本地 HTTP/SSE 模拟服务，验证路由、认证头、图片、流式工具调用、带图结果续接及超限请求零外发；验证已知模型的显式协议覆盖。
- task-state：8 项通过；android-bridge：15 项通过；Pi：11 项通过；Python：62 项通过。
- Android debug 构建及 app/device/runtime 单元测试通过，分别 35 / 20 / 4 项。
- 从 APK 的实际资源 ZIP 解包到隔离目录运行 Pi，验证任务记忆、重启、思考内容、图片和工具循环、无手机动作重放：通过。
- vivo V2366GA / Android 16 / API 36 真机 `RuntimeGateTest`：通过；仅使用本地模型模拟器，两轮图片工具循环正常。
- 覆盖安装前后 model.enc 与原业务 Pi 会话 SHA-256 一致。没有发送真实模型请求或重试原业务动作。
- 真机设置下拉框实际展开，确认三种协议选项全部存在；取消退出，没有改写当前模型配置。测试 APK 已卸载。

### 安装结果

三协议及请求预算修复已安装，设备 base.apk SHA-256：`34ef07cdc8d5ab94bbb8b8fb86182dc25df4515149cf1898829364460a16a050`。该包通过上面的真机 RuntimeGateTest。

随后补充 `phone_history` 的聊天卡片中文标题的单独安装曾被手机系统拒绝。2026-09-26 用户要求启用图片压缩后，新包已成功覆盖安装，也包含该标题修复。当前手机与本地 APK SHA-256 一致：`c236ec9fd8789d328c65ae8ff7091631a8e7dd217dafc926c7307315fd10f464`。APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。

## 本地图片压缩：已启用

Android `ScreenshotEncoder` 复用 `Bitmap.compress`，以质量 90 生成 JPEG，并与 PNG 取较小者；JPEG 编码失败则沿用 PNG。新截图从原生桥接进入 Pi 时携带实际 MIME。保持 1080×1920、DPI 480、完整画面及点击坐标；观察校验继续使用原始 Bitmap。原始无损 PNG 仍保存在应用私有 `files/device-runs/<截图编号>.png`。

本次只改变之后生成的截图。已有 Pi 历史不重写，仍通过上一轮实现的图片窗口和请求预算管理。`phone_history` 回读的是当时存入 Pi 的图片块，不会把压缩后的图片冒充本地无损原图。三个协议均复用 Pi 的图片序列化，不增加供应商专用分支。

真机 vivo V2366GA / Android 16 / API 36 验证：

- Android 构建及 app/device/runtime 单元测试通过（35 / 20 / 4）。
- `ScreenshotCompressionTest` 两项通过：JPEG 解码尺寸、源 Bitmap 未修改、PNG 归档像素相同、纯色图保留较小 PNG。
- 合成中文小字图：1,821,616 → 421,072 字节；一次 PNG＋JPEG 总编码 830 ms。
- 本机已有的一张真实截图：1,876,570 → 395,148 字节，减少 78.9%；一次 PNG＋JPEG 总编码 512 ms。耗时是单次测量，包含原本已有的 PNG 编码，不代表 JPEG 新增耗时。
- 对照检查合成图的 18/24/32/48 px 中文与真实页面：文字可读，布局及坐标相同。这不是模型识别准确率测试。
- `DeviceGateTest` 通过：真实截图链路尺寸及本地原图、20 次虚拟屏点击、悬浮窗截图/触摸隔离、旧观察校验、STOP。
- 三个真实 Pi 协议适配器经本地模拟服务验证：PNG 输入与 JPEG 工具结果、MIME 和图片字节续接正常；两项协议测试通过。
- 覆盖安装后模型密文配置和原业务会话 SHA-256 均未改变。没有发出真实模型请求。临时测试 APK 已卸载，手机已回到 BBUI。

先前 Pillow 离线实验对 50 张 PNG 采用相同质量与尺寸策略，字节总量减少 72.0%；该结果仅作样本体积参考，实际编码由 Android 执行。

相关行业实现：

- [OpenAI Computer Use](https://developers.openai.com/api/docs/guides/tools-computer-use)
- [Anthropic 截图编码与历史管理](https://platform.claude.com/docs/en/agents-and-tools/tool-use/computer-use-tool)
- [Browser-use 发送前图片缩放](https://github.com/browser-use/browser-use/blob/main/browser_use/agent/prompts.py)
- [DeepSeek 图片请求限制](https://api-docs.deepseek.com/guides/vision/)

私有离线证据位于忽略目录 `runs/413-diagnosis`、`runs/protocol-fix` 与 `runs/screenshot-compression`；不得提交其中的会话、截图或凭据。
