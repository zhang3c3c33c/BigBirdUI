# 提问提交修复与无 GUI 工具验收

日期：2026-09-28。三位子代理分别实施设备后端、运行时/协议、提问交互；主代理固定契约、交叉审查、集成、安装并独占真机验收。

## 提问修复

根因是 MainActivity 遗漏 `answerQuestion` 和 `questionDraft` 转发。页面进入提交状态，但原生 QuestionBroker 没有收到答案，草稿也未保存。

- 补齐真实 Activity → Service → SessionController 的命令路由。
- 10 秒未收到确认时解除提交锁定，保留填写内容并显示错误；只有用户显式重试，不自动提交。
- 成功确认取消超时，重复或过期回答仍按原身份校验拒绝。
- 增加真实 WebView 填写、草稿持久化、页面 reload、错误身份、提交、取消、模型继续的真机测试。测试使用隔离 SessionController 和本地模拟模型，保留原控制器与 continuation。

原先卡住的任务已停止，未自动继续。用户在页面填写的四个答案完整保存为原会话输入框内的待发送草稿。旧问题按中断处理，不能继续提交旧请求。

## 新增工具

| 工具 | 已实现 |
| --- | --- |
| system_contacts | 系统姓名匹配查询、详情、新增本地不同步联系人、按 rawContactId 修改姓名/电话/邮箱。省略保留、空数组清空对应类别；不删除联系人。 |
| system_sms | 地址、时间、文本筛选及详情；正文分页，列表只返回短摘要；不发送、不写短信。 |
| system_call_log | 号码、时间、来电类型筛选及详情；只读，不拨号。 |
| system_media | 图片/视频/音频名称、类型、入库时间筛选；元数据、大小、尺寸/时长、URI；不回传二进制或访问应用私有目录。 |
| system_clock | 能力发现、标准闹钟/倒计时创建请求；固定参数，请求跳过 UI，明确派发不等于创建成功。 |

全部复用现有 `/system`、Shizuku shell 当前用户身份、STOP、串行执行、去重、受限参数和派发回执。桌面不宣告这些尚未实现的能力。系统 Provider 查询不创建虚拟屏；时钟 Intent 是否显示界面取决于系统处理应用。

联系人操作复用 Android Contacts Provider 原子批次，当前用户 Provider 连接抽取自已验证的 Calendar 实现。已审查并修正：rawContacts 分页可达性、组合页大小预算、明确拒绝与未知派发的区别、成功写入后读取/ID 解析失败仍保留派发事实、按每个修改字段完整核验。

## 验证

本地通过：

- TypeScript 类型检查；Agent 工具 20 项；新工具与日程真实 Pi 三协议本地 HTTP 模型回归各 1 项。
- 前端契约 14 项；真实浏览器 2 项，含提交确认丢失恢复和不自动重试。
- Android app 74、device 50、runtime 11，共 135 项单元测试。
- Pi 11 项、桌面 9 项。
- 实际 APK 运行时资源包验证、前端生产构建、主 APK 及测试 APK 构建。

真机：vivo V2366GA，Android 16 / API 36，Shizuku UID 2000。

- `MainActivityQuestionTest`：1 项完整页面端到端测试通过。草稿、reload 恢复、拒绝过期回答、提交确认、本地模型收到答案后继续、取消及恢复原用户状态均通过。
- `NonGuiIntegrationTest`：1 项通过。唯一临时联系人创建、查询、修改、省略字段保留、重复动作去重、STOP；测试联系人已清理。唯一 PNG 名称检索及详情返回正确 4×3 尺寸，图片与索引已清理。
- 短信与通话记录使用不存在的测试地址/号码查询成功，未读取用户实际短信或通话内容。
- clock capabilities 成功。本机 alarmHandler 为 `com.android.intentresolver/.ResolverActivity`，timerHandler 为 `com.android.BBKClock/.alarmclock.HandleApiCalls`。
- Calendar 1 项、系统连接与 GUI 2 项回归通过；新 Provider 查询均未创建虚拟屏。

首次真机联系人写入被 Provider 拒绝，原因是手工创建的 ContentProviderClient 默认 authority 为 unknown。已修为通过 resolver 显式传递 ContactsContract.AUTHORITY，复验通过；被拒绝的调用未创建联系人。提问验收脚本的目录刷新清理竞态和 WebView reload 后回调时序也已修正，以真实原生确认作为验收依据，命令只发一次。

## 数据与安装

- 主应用已覆盖安装，未清空配置或历史。
- 安装前后 12 个原有配置、记忆和 Pi 会话文件 SHA-256 一致。
- 原任务 continuation、选中会话和 145 字符回答草稿保留；阅读位置随页面重排略有变化。
- 队列保持暂停，无运行任务，没有自动提交原答案或重做手机操作。
- 验收临时联系人、日程、图片已清理；隔离模型测试会话已删除。

主 APK：`android/app/build/outputs/apk/debug/app-debug.apk`，129573016 字节。

SHA-256：`fd1b977ee37a428628e42f8ea7ee781da4030dbae8bf4599a0d77b7692195fdf`

证据：忽略目录 `runs/nongui-acceptance/`，最终日志为 `question-route-verified.txt`、`device-providers-final.txt`、`calendar-and-gui.txt` 和 `summary.json`。

## 实测边界

- 未调用真实联网模型或搜索 API；模型行为仅使用本地固定样本验证，不代表真实模型决策质量。
- 未创建真实闹钟/倒计时或等待响铃；参数构造及能力发现已验证。本机闹钟走系统选择器，不能宣称完全无界面。
- 短信/通话只验证有权限的空结果查询，未验证用户实际历史详情；文本分页有单元覆盖。
- 媒体真机正样本为 PNG；音频/视频分支及其他 OEM 仍待设备验证。
