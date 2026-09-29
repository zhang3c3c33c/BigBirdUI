# 真机测试结果

日期：2026-09-22。设备：vivo V2366GA，Android 16，1080×2400。

目标：打开主微信，给文件传输助手发送一条测试消息。

结果：完成。22:43 的截图显示文件传输助手聊天中的新绿色消息气泡：`Phone Use MVP 测试消息`，输入框清空，未见失败标志。这里只验证了客户端显示状态，没有宣称取得服务端送达回执。

决策由当前对话 agent 逐步完成；每步调用本地工具，重新观察。没有预先写死微信点击流程。

关键证据（均为项目相对路径）：

- 中文搜索成功：`runs/REDACTED_DEVICE_SERIAL/31222f9b7649415b97c6317f89a66fbc/screen.png`
- 发送前标题与草稿核对：`runs/REDACTED_DEVICE_SERIAL/e1f358bdcddf4d2cbeced56493de94a9/screen.png`
- 发送后消息气泡和空输入框：`runs/REDACTED_DEVICE_SERIAL/65fa25189774455c9f19236e237608de/screen.png`
- 唯一发送动作：`runs/REDACTED_DEVICE_SERIAL/action-scrcpy-send-test-001.json`
- 全过程操作记录：`runs/REDACTED_DEVICE_SERIAL/events.jsonl`

设备适配发现：应用分身选择器需要选择主微信；微信本身 UI 树为空；UIAutomator2 的剪贴板接口身份校验报错。切换到 scrcpy 官方控制服务后，中文搜索、消息输入、点按均成功。

结束时输入法仍为 `com.tencent.wetype/.plugin.hld.WxHldService`，辅助输入法未安装，未发现仍存活的 scrcpy 控制服务。

自动检查：12 项测试通过，覆盖旧观察、目标移动、停止、重复发送、超时不重试、Unicode 字节长度、协议分包、剪贴板核对失败不粘贴和连接中断。MCP stdio 客户端握手与四个工具 schema 发现通过。
