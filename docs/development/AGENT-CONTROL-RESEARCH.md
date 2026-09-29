# 任务控制与人工交接：行业调研

2026-09-26。本文为下一步设计建议，未修改产品行为。

用户不应同时管理会话、队列和画面的运行开关。会话保存上下文，任务承载执行，队列安排先后，画面用于观察与接管；这些对象需要在代码中分开，但不需要在日常操作中分别启动和恢复。

## 官方资料

- OpenAI Browser / ChatGPT Work：https://learn.chatgpt.com/docs/browser 。任务需要网站时自动使用浏览器；手动登录时任务暂停，用户点 I'm done 交还控制权。任务进度在对话中呈现。
- OpenAI Queue / Steer：https://developers.openai.com/blog/mastering-codex-remote-for-engineering 。Queue 在当前回复结束后发送下一条，Steer 给进行中的工作补充方向。该资料描述会话内消息行为，不能直接当作共享一部手机的跨会话调度设计。
- Anthropic：https://support.claude.com/en/articles/16761823-claude-cowork-and-chat-are-one-claude 。逐步推出统一聊天与 Cowork 的体验，让用户描述目标，由模型选择工具，无需先选择工作模式。并非所有账户均已提供。
- Manus Cloud Browser：https://manus.im/docs/features/cloud-browser 。任务自动启用浏览器；人工处理验证后交还控制权，Manus 继续任务。
- Manus 本地 Browser Operator：https://manus.im/blog/manus-browser-operator 。其专用执行标签页关闭可停止任务；不能据此声称所有产品关闭画面都不影响执行。BBUI 的预览是独立观察窗口，建议关闭预览不停止任务。
- Pi RPC：https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/rpc.md 。本地安装版本为 0.87.0，已核对 node_modules/@earendil-works/pi-coding-agent/docs/rpc.md：支持 steer、follow_up、clear_queue、abort。steer 在工具执行结束、下一次模型调用前交付，不是立即中止已派发操作。

## 对 BBUI 的建议

1. 会话没有开始/停止。切换只是浏览，对正在执行的任务无影响。
2. 发送即执行；共用手机忙时显示等待顺序，正常运行不要求用户开启队列。队列管理是可选的批量操作。
3. 查看画面与关闭画面只影响观察。初次授权仍必要，连接、资源准备和恢复由运行时负责；实际故障才提供修复入口。
4. 执行期间显示停止与我来操作。停止中断当前执行且不悄悄启动下一项，保留输出及等待项。
5. 人工接管是任务内交接。暂时禁止 AI 输入并收妥已派发操作的结果；保存任务、会话和执行归属，然后开放手动操作。
6. 人工模式显示交给 AI 继续。一次操作完成退出人工模式、恢复同一任务和重新观察。不要求用户再发一条“继续”然后找队列开关。
7. 如果接管时没有未完成任务，只显示结束操作，不凭空启动模型。
8. 恢复当前任务与执行其他等待项是不同意图。不能因为恢复 A 就悄悄放行已被用户单独暂停的 B/C；需要在控制层记录暂停来源与作用范围。暂时交接、用户主动暂停、设备故障/重启不能共用一个布尔值推断恢复意图。
9. 停止后的恢复作用范围需要产品上明确：继续当前任务只授权该任务；批量继续等待项通过等待列表按需提供。日常接管交还不走全局人工暂停流程，避免频繁额外操作。
10. 同一会话的新输入可能是修正当前任务，也可能是另一个任务。借鉴 Queue / Steer，以默认等待及显式立即补充等操作区分；不简单把所有消息都当作独立排队任务，也不让模型静默改变发送时序。

## 当前代码差距

AppCoordinator.takeOver() 先调用 stop()；stop 清理运行并发出 run_stopped，ConversationStore 全局 paused=true。resume() 仅关闭手动输入，既未继续任务也未恢复调度。因此问题在运行控制语义，不是按钮名字或排列。

保留 Pi 的会话、事件和工具循环；由 Android 控制层协调任务归属、手机独占及人工交接。无需引入另一个 Agent 框架。恢复必须读取原会话与当前手机状态，重新观察后决定下一步，禁止直接重放原动作队列。模型负责业务决策；STOP、手动/自动互斥和凭证校验仍由执行器保证。
