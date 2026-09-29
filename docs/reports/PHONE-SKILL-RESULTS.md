# 手机操作 Skill 实施与验收

日期：2026-09-28。Pi 固定版本：0.87.0。

## 实现

- 系统提示改为 BBUI 身份与简短通用规则，使用 Pi 结构化 customPrompt/sections；环境能力和用户记忆独立，删除记忆会移除旧 section。
- 内置 `pi/skills/phone-operation/SKILL.md` 和按需案例。手机任务先理解目标、必要用户决策与完成证据；页面事实自行调查。简单操作无需先提交任务记录，复杂任务复用 task_state，schema 未变。
- Android、desktop、CLI 显式加载同一 Skill，关闭隐式技能发现。受限 `read` 复用 Pi 分页/取消，只接受两份已打包 Markdown 的 canonical 路径，不开放 Shell 或任意文件读取。
- 提问复用上游结构和答案格式，新增 `options: []` 的纯文字题；必须显式提交非空答案。原选择题、草稿、去重和中断机制保持。
- 技能读取卡片仅显示“读取手机操作指南”和真实状态；成功、失败、增量与历史的 UI 副本均移除技能正文和路径，不改 Pi 原始消息。
- 运行时读取失败按 Pi 原生约定抛出经过精简的异常，避免仅返回 isError 字段却被 Pi 记录为成功。

## 子代理与审查

三个子代理分别负责运行时、Skill 内容、交互；主代理补充三协议真实 Pi 循环和设备测试并集成。交叉审查修正了测试的生产空闲检查、旧 Skill 历史误判，以及跨会话快照断言。所有真实设备安装和验收由主代理独占执行。

## 自动化验证

| 验证 | 结果 |
| --- | --- |
| TypeScript | 通过 |
| Pi / task_state | 11 / 8 通过 |
| Android bridge / screenshot context | 17 / 5 通过 |
| 现有三协议 / runtime 配置等 | 3 / 13 通过 |
| Agent 工具（含记忆、纯文字问题） | 16 通过 |
| Skill 专项 | 4 通过，含真实符号链接拒绝、取消、分页、原生摘要、三协议单 Agent 连续工具调用 |
| 展示投影 | 5 通过 |
| 前端契约 / 浏览器 | 14 / 2 通过 |
| 桌面回归 | 9 通过 |
| Python | 68 通过 |
| Android app / device / runtime 单元 | 72 / 36 / 11 通过 |
| 精确 APK runtime ZIP | 隔离依赖解包及本地模型 gate 通过；读取正文仅进入模型，失败状态正确，恢复不重放 |

统一专项命令：`npm run test:phone-skill`。该命令先重建 Agent 工具 bundle，再执行 Skill 与三协议测试。

三协议测试运行实际 Pi Agent 循环、协议适配器与真实工具执行器；服务端只位于 loopback，动作返回合成观察。覆盖 read → task_state → 纯文字提问 → phone_action → 越界读取失败 → 继续响应。

## 交付与验证边界

主 APK：`android/app/build/outputs/apk/debug/app-debug.apk`。
SHA-256：`72C8203649BD14A8E3DDF17329D4D5708DDF7107A0F849081E304611EDB32499`。

设备：vivo V2366GA，Android 16 / API 36。覆盖安装保留应用数据。

真机最终通过5项：原有选择题跨会话/STOP；新增打包Skill与纯文字问题跨会话/STOP/模拟Shizuku失效；真实内嵌Pi两轮截图工具循环；系统查询不创建虚拟屏；系统查询后按需GUI观察。
新增问题用例首次失败来自检查B会话的消息而非运行中的A会话，修正测试读取目标后通过。没有为测试放宽产品逻辑。

原有13个配置、长期记忆和Pi会话文件的安装前后SHA-256全部一致；生产任务为空、队列为空，原暂停状态保留。详细设备日志与数据保护哈希位于忽略目录 `runs/phone-skill-acceptance/`。

`tests/fixtures/phone-skill-behavior.json` 提供15个行为评测案例，区分必要澄清、过度追问、可避免的中途停顿、假设扩大授权及无证据完成。此次没有调用真实模型或搜索 API，不能把确定性模拟顺序当作模型自主决策质量通过。真实模型行为待另获联网授权评测。桌面源码与回归已验证，本轮未重新发布桌面安装包。
