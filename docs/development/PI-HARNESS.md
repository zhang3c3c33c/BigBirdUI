# BBUI + Pi Harness

> 内部开发资料：此入口用于调试或历史兼容，不属于对外支持的使用方式。用户使用 Windows 桌面应用或 Android 本地应用，见 [项目首页](../../README.md)。

保留 Pi 原生的供应商、模型列表、认证、终端交互、会话与上下文压缩。新增手机扩展，通过 MCP stdio 连接已有 Python 工具；不修改 Pi 源码，不实现第二套模型 API。

当前工具职责、结果状态及安卓/桌面能力差异见 [TOOL-CONTRACT.md](../contracts/TOOL-CONTRACT.md)。

传输结果不确定时可先继续查看；用户命令 `/phone-recover` 在确认旧 MCP 退出并释放设备租约后重建只读连接，保留 STOP，不重放动作。`/phone-resume` 仅恢复用户暂停，不清除传输隔离。

## 启动

先按 [贡献指南](../../CONTRIBUTING.md) 安装 Node.js 24 和 Python / npm 依赖。内部真机调试还需运行 `setup_scrcpy.py` 和 `scripts/install_scrcpy.py`，将根目录 `config.example.json` 复制为 `config.local.json` 并填写设备序列号；已有配置不要覆盖。以下命令从仓库根目录执行。

```powershell
# 在仓库根目录执行
npm run phone
```

进入 Pi 后先输入 `/phone-connect`，连接 `config.local.json` 指定的手机，并弹出主屏 scrcpy 镜像。该命令直接调用工具，不消耗模型 API。重复连接会复用窗口；手动关闭主屏镜像后，再执行该命令可重新打开。

模型创建虚拟屏幕时，会自动弹出对应的 scrcpy 镜像，标题包含会话名和 display ID。**主屏与虚拟屏镜像均为只读**，通过官方 `--no-control` 禁用鼠标、键盘及拖放控制，同时关闭剪贴板同步、音频与唤醒。模型的控制连接独立保留。可以移动、缩放或关闭观看窗口；关闭观看窗口不会关闭模型的虚拟屏幕。

关闭虚拟屏幕会同步关闭对应镜像；退出 Pi 会清理全部镜像。Windows 镜像进程归入带 `KILL_ON_JOB_CLOSE` 的 Job，MCP 进程被强制终止时也会清理观看进程。镜像启动失败会明确返回 `镜像.状态=启动失败`，不会把已创建的虚拟屏幕误报成创建失败，也不会自动重放手机动作。`/phone-screens` 可查看镜像状态。

安装官方 scrcpy 4.1 Windows 客户端：

```powershell
.\.venv\Scripts\python.exe scripts/install_scrcpy.py
```

安装脚本下载固定官方版本并验证 SHA256。客户端位于 `vendor/scrcpy/scrcpy-win64-v4.1/`；镜像日志位于 `runs/<设备序列号>/mirrors/`。每个观看窗口增加一条手机视频编码流，因此实际可同时运行的屏幕数量还受手机编码器资源限制。

在 Pi 中使用 `/login` 配置自己的认证，通过 `/model` 选择支持图片输入的模型。认证通常保存在用户目录的 `.pi/agent/auth.json`，不要复制到仓库。项目 `.pi/settings.json` 作为本机偏好被 Git 忽略，不要求使用特定供应商。历史真机结果见 [验收记录索引](../README.md)。

可以直接输入：

> 列出手机上的应用包名，并告诉我哪些应用允许操作。

模型可调用 `phone_action` 的 `列出应用`，查询当前 Android 用户的全部已安装包、系统标记、允许操作标记和桌面启动入口；可选包名关键词筛选和排除系统应用。该查询只返回文本，不打开应用或镜像、不需要截图编号。应用中文名称暂不提供。

> 创建 calc 和 settings 两个虚拟屏幕，分别打开计算器和设置。并行观察两屏，在计算器输入 7，同时在设置中搜索蓝牙。不要改变设置，最后汇报截图证据。

所有 Pi 原生参数仍可传入：

```powershell
npm run phone -- --model "供应商/模型名"
npm run phone -- --continue
```

恢复会话不会恢复已经销毁的 Android 虚拟屏幕。必须重新列出屏幕、创建或观察，旧截图编号不能继续使用。

扩展提供 `/phone-connect`、`/phone-screens`、`/phone-stop`、`/phone-resume`、`/phone-recover`。取消正在执行的手机调用会写入设备 STOP 标记；恢复由用户执行 `/phone-resume`。STOP 无法撤回已注入的动作。

独立安装：

```powershell
npm ci --ignore-scripts
.\.venv\Scripts\python.exe -m pip install -e .
```

设备序列号与禁止的包名在 `config.local.json`。示例默认允许所有应用，可按需要设置 `blocked_packages`。自定义 Python 路径可使用 `BBUI_PYTHON`。

## 执行结构

```text
Pi 原生 CLI / AgentSession（模型、会话、压缩、并行工具循环）
  └─ pi/phone-extension.ts
       └─ pi/bridge.ts（一个常驻 MCP 客户端，无输入自动重试）
            └─ bbui.unified_mcp（异步处理请求）
                 └─ ScreenRegistry
                      ├─ main：主屏截图 + scrcpy控制
                      ├─ calc：虚拟屏幕 + 独立控制/视频连接
                      └─ settings：虚拟屏幕 + 独立控制/视频连接
```

每个屏幕有独立队列锁、观察编号、动作记录、截图目录及连接。不同屏幕可同时执行，加载等待不会占住设备输入锁。剪贴板粘贴独占短暂的输入阶段，避免其他工具动作在粘贴期间争抢焦点；普通跨屏手势共享进入。手机之外的人手操作仍可能改变状态，工具在派发前重新检查。

同一设备只能由一个 BBUI 运行时管理，避免多个 MCP 进程争用手机。并行调用必须复用同一个 MCP 进程；不是启动两个 `npm run phone` 控制同一手机。旧版 `bbui.cli act/resume` 在此运行时占用手机期间会被设备锁拒绝；在 Pi 中使用恢复命令。

启动器允许 `phone_action`、`task_state` 和 `read`，并只加载项目手机操作技能；桌面扩展设置跨屏并行执行。基于画面的动作引用目标屏幕有效截图，提前生成的一批同屏动作会因旧截图被拒绝。工具不设固定 100 次调用后强制结束的任务政策，是否继续由模型结合用户任务与进展判断。

## 工具示例

下面展示执行器参数；通过 Pi 调用时还需在顶层提供非空 `意图`，例如 `"意图":"查看当前屏幕"`。

```json
{"操作":"创建屏幕","参数":{"屏幕会话":"calc","包名":"com.android.bbkcalculator","宽度":720,"高度":1280,"密度":240,"执行后等待毫秒":3000}}
{"操作":"查看","参数":{"屏幕会话":"calc","执行后等待毫秒":1000}}
{"操作":"点击","参数":{"屏幕会话":"calc","位置":[100,875],"截图编号":"上一步返回值","执行后等待毫秒":2000}}
{"操作":"列出屏幕","参数":{"执行后等待毫秒":0}}
{"操作":"关闭屏幕","参数":{"屏幕会话":"calc","执行后等待毫秒":0}}
```

不要照抄示例坐标到别的画面。`main` 是保留的主屏会话；创建虚拟屏幕必须传唯一英文名称，包名可省略。创建后尝试返回截图，无画面时明确报告观察失败；列出／关闭是管理操作，无需等待字段。

截图以 MCP image 内容块返回，桥接到 Pi 的 image 内容块，真正进入下一轮视觉输入；不会只把文件路径交给模型。虚拟屏幕采用持续视频解码，动作后等待指定时间再取当前画面。静止界面可能复用最后视频帧，因此返回视频帧编号和接收时间；它不是页面加载完成证明。流断开会报错，不能静默用旧帧继续操作。

## 本版边界

- 默认最多3个虚拟屏幕，可配置 `max_virtual_screens`。同屏可切换应用；实际被另一受管屏幕占用的应用会报告归属冲突。虚拟屏幕不等于应用数据或账号隔离。
- 虚拟屏幕目前只返回截图，不返回无障碍树。存在虚拟屏幕时主屏也拒绝读取节点，避免全局 UIAutomator 树串屏；只有单主屏时支持节点。
- 虚拟屏幕不接受通知栏、音量等设备全局操作，需显式指定 main。不同 ROM/App 的多屏兼容性需分别验证。
- 屏幕关闭或 Pi 退出时释放 scrcpy 连接并销毁虚拟屏幕，其内容默认由 Android 清理。视频尺寸变化时清除上一尺寸的缓存帧。
- 中文输入仍经过手机剪贴板，会改变手机剪贴板内容。输入前核对目标屏幕与焦点应用；输入框类型作为事实返回，由模型结合用户授权判断。
- 结果不确定不会自动重放输入。用户 STOP 与传输故障隔离分开；保留可用的观察通道，技术恢复须先确认旧执行进程已退出。
- Pi 会保留自己的会话记录，BBUI 的截图与动作轨迹保存在 `runs/`。业务成功仍需根据返回画面验证。

## 验证

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -X utf8 tests/smoke_unified_mcp.py
npm run typecheck
npm run test:pi
node tests/smoke_pi_cli.mjs
npm run smoke:pi
# 显式真机测试，会打开计算器/设置并执行测试动作：
npm run smoke:pi -- --device
# 只观察，不注入点击/文字；会弹出真实镜像窗口：
npm run smoke:connect -- --device
npm run smoke:mirrors -- --device
```

2026-09-23 镜像实测：Pi 原生 `/phone-connect` 在不调用模型 API 的情况下成功连接并打开主屏窗口；主屏与计算器虚拟屏镜像同时显示，均有独立 Windows 窗口及正确的 display ID。重复连接复用主屏进程。关闭测试屏幕并退出后，scrcpy 观看进程全部退出，手机仅保留 Display 0。记录位于 `runs/mirrors-smoke.json`；31 项 Python 测试、2 项 Pi 扩展测试及 TypeScript 检查通过。

2026-09-22，vivo V2366GA / Android16 实测：

- 两个虚拟屏幕分别显示计算器和设置；主屏仍为 B 站。
- 两屏各等待3000毫秒，整体 **3221毫秒** 返回。
- 两屏同时点击、各等待3000毫秒，整体 **4027毫秒** 返回。
- 计算器收到数字7；设置搜索框成功输入中文“蓝牙”，截图显示对应搜索结果，未改变设置。
- 使用另一屏幕的截图编号点击被拒绝。
- 测试结束后临时虚拟屏幕已销毁，设备只剩主屏。
- Pi 原生 CLI 动态加载扩展成功。Pi SDK 离线模拟模型完成“并行调用两个手机工具→接收两张图片→继续回答”闭环；这与真实模型端到端测试分开记录。

证据索引：`runs/multiscreen-smoke.json`，其中包含本次各屏幕截图的完整路径和耗时。

参考：[Pi](https://github.com/earendil-works/pi)、[scrcpy 虚拟屏幕](https://github.com/Genymobile/scrcpy/blob/v4.1/doc/virtual-display.md)、[scrcpy 只读控制](https://github.com/Genymobile/scrcpy/blob/v4.1/doc/control.md#read-only)。协议按本地固定的 scrcpy 4.1 源码实现，官方 Apache-2.0 许可与协议参考位于 `vendor/scrcpy/`。


## 应用禁止列表

默认允许操作全部应用。`config.local.json` 中的 `blocked_packages` 只列出需要禁止的完整包名，按精确匹配判断，不支持通配符。例如：

```json
"blocked_packages": ["com.example.privateapp"]
```

示例使用 `"blocked_packages": []`，代表未设置应用禁止项。旧 `allowed_packages` 字段已停用，不再限制应用范围。修改配置后重启 `npm run phone`，运行中的 MCP 不会自动重载。无需改动模型供应商或模型配置。

应用查询的 `允许操作`、主屏打开与输入、虚拟屏幕创建以及旧版操作接口使用同一策略。禁止的应用仍可列出或观察，但不能打开、点击或输入；列表查询不会修改策略。未知或格式错误的包名、格式错误的禁止列表会报错。
