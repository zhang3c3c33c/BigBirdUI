# BigBirdUI · 大鸟手机助手 Windows 桌面端

本次目标平台为 Windows x64。macOS 没有实现、打包或验收，不能把接口预留视为平台支持。

## 使用

首次使用可先看项目首页的 [电脑连接 Android](../../README.md#电脑连接-android) 或 [电脑连接 iPhone](../../README.md#电脑连接-iphone) 教程。

解压 `BBUI-Windows-x64.zip`，双击 `BBUI.exe`。运行目录内已包含 Electron、Node、Python、ADB、scrcpy 和依赖，不需要另装这些工具。

打开后显示与手机版同风格的欢迎页。“连接手机”打开设备设置：先选 Android 或 iPhone，点击“刷新设备”，在下拉列表选择手机名称后点击“连接”；成功后按钮变为“断开”，设备选择自动保存。已连接时先断开再更换目标。欢迎页同步显示实际连接状态，不会因选择候选设备而显示已连接。

在模型设置中填写连接并选择默认模型；两项就绪后点击“开始使用”进入聊天。缺少模型配置或手机断线时会返回欢迎页，保留会话草稿；恢复配置或连接不会自动续跑任务。设备页不再显示应用禁止列表或悬浮停止开关，旧配置仍兼容，已有 Android 应用限制继续生效。

Android 手机需要开启 USB 调试并允许这台电脑；不需要安装 BBUI APK 或启动 Shizuku。连接后预览默认只读；点击“我来操作”才开放人工输入，无任务时通过“结束操作”退出，不会自动启动队列。只有实际中断的任务才显示“继续任务”或“交给 AI 继续”。

### iPhone USB 连接

iPhone 需要 Apple USB 驱动、信任电脑和开发者模式。首次准备可以使用爱思助手完成：

1. 在 Windows 上从 [爱思助手官网](https://www.i4.cn/)下载并安装电脑端。
2. 用 USB 数据线连接 iPhone，解锁手机并按提示“信任此电脑”，等待所需驱动安装完成、手机被识别；遇到驱动安装提示按提示完成。
3. 在爱思助手顶部进入“工具箱 → 实时屏幕”，按弹窗指引开启开发者模式并重启手机。
4. 重启后解锁手机，按系统提示再次确认开启开发者模式，再回到大鸟手机助手选择 iPhone 并连接。

已具备驱动和开发者模式时可跳过上述准备。也可以按 [Apple 官方安装指南](https://support.apple.com/guide/devices-windows/welcome/windows)和 [开发者模式说明](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device)自行准备。BBUI 不自动安装或修改设备驱动；爱思助手用于连接准备，运行时不依赖它的实时屏幕功能。

手机不需要安装 BBUI、WDA 或辅助 App。首次准备开发者支持文件可能需要联网，沿用 pymobiledevice3 在用户目录 `.pymobiledevice3` 下的缓存，不依赖其他项目。

控制与预览都通过 USB，不使用 AirPlay、Wi-Fi 或蓝牙。预览通过保持连接的连续截图实现，默认目标 15 FPS，实际速度取决于手机和电脑性能；这不是 60 FPS 视频流。收起画面、最小化窗口或隐藏页面时暂停预览，模型主动观察仍可使用。原始图片尺寸与模型坐标保持一致。

iPhone 目前只有真实主屏，提供触摸、文字、主屏/最近应用，以及应用列表/启动/终止、文本剪贴板和 AFC 可访问文件区域。AFC 不等于任意应用私有文件访问。Android 专属虚拟屏、通用返回键、全局应用禁止策略和没有 iOS 实现的系统工具不会显示为可用能力。是否支持特定 iOS 版本以实际连接结果和验收报告为准。

任务运行或队列非空时不能切换设备；任务绑定提交时的设备身份，不会在换手机后自动执行。旧配置未记录设备类型时按 Android 处理。

任务按设备先进先出。没有旧队列、中断任务、待回答问题或人工接管时，新消息保存成功后可解除遗留的自动暂停；用户主动暂停、未知暂停原因和旧工作仍需明确继续。旧版本只记录暂停布尔值，无法判断来源，升级时保留其暂停，需手动继续一次。当前会话执行时，空输入框的主按钮为停止；输入文字后变为发送箭头，默认加入队列。已排队消息可选择“立即补充”交给当前轮，且不会再作为下一轮重复执行。接收不确定时保留原文与状态，不自动重发；新的输入草稿保持不变。

左栏管理会话，中栏复用手机版聊天界面，右栏显示主屏或虚拟屏。拖动栏间边界调整宽度。消息在聊天栏内部滚动，输入框保持可见；位于底部时跟随新内容，向上阅读时保持位置，可点击“回到底部”重新跟随。人工接管先暂停模型新输入，等待旧调用结束，再开放手机输入；STOP 同时约束人工输入和模型输入。切回模型时先重新观察。

新会话的首条消息保存成功后，使用该次提交的模型在后台生成简短标题，电脑和手机共用命名逻辑。每个新会话最多尝试一次额外文本请求，不发送截图或工具历史、不影响正文任务。手动改名始终优先；生成失败保留首条消息摘要，已有会话不批量改名。

Android 人工点击、滑动和按键复用现有 scrcpy 控制连接，通过实时画面反馈，不生成模型观察或逐次保存截图。Android 输入仍核对目标屏幕、控制权、焦点、禁止应用和画面尺寸；人工操作会使模型旧观察失效。iPhone 人工鼠标操作映射为触摸输入，共用控制权与画面校验。

进入人工操作后，点击手机画面即可使用键盘、中文输入法与粘贴。全选和删除通过快捷键完成；返回、主页、最近任务使用图标。画面栏不再放置文字输入表单和重复的停止按钮，任务停止沿用聊天区入口。

聊天顶部的“手机画面”切换右栏显示，绿色选中样式表示已展开。收起保留当前屏幕并暂停预览，尚未派发的手机输入文字会取消并提示。多屏时通过“主屏 / 任务屏”标签切换，单屏不显示标签栏。界面不提供手动创建、关闭虚拟屏或额外放大入口；任务所需的屏幕仍由执行器工具管理。

手机上已有、由其他端创建的虚拟屏会自动显示为“外部屏幕”标签，仅供查看。它们不属于电脑版任务，电脑版不会接管输入、停止其任务或销毁屏幕。只读预览复用 scrcpy 的指定显示屏功能；内部用于采集画面的私有显示屏不会列出。切换目标或退出人工控制时，尚未派发的键盘文字取消并提示，不会被发送到另一个屏幕。

最小化不结束任务。关闭主窗口会停止任务、保存状态、等待执行器退出并释放设备。重新打开不会自动重跑；中断记录需点击“明确继续”。悬浮停止按钮可在设备设置中开启。

数据位于 Windows 用户的 Electron `userData/BBUI` 目录，可在“数据与诊断”查看实际位置。设备锁统一放在 `%LOCALAPPDATA%/BBUI/devices/<设备身份哈希>`，GUI 与原有 `npm run phone` 共用。不要同时用其他工具控制正在运行的 BBUI 设备。

“导入旧设备配置”和“导入 Pi 会话”通过文件选择器复制原文件，不改写来源。同一 Pi 会话 ID 不重复导入。升级时替换解压目录，用户数据与安装目录分离。密钥由 Electron safeStorage 使用 Windows 系统加密；加密后的设置文件不能直接跨平台迁移。

## 开发与构建

锁定 Node 24.15.0、Python 3.12.14、Electron 44.4.5、Pi 0.87.0、scrcpy 4.1。Node 的 npm 依赖使用根目录 lockfile；Python 依赖使用 `scripts/desktop/requirements.lock`。构建机还需要 Android 构建工具链（JDK 17、SDK 36、NDK 28.2.13676358、CMake 3.22.1）以及 Google Chrome；品牌资源生成器使用 Playwright 的 `chrome` 通道。以下命令从仓库根目录执行，`python` 须指向 Python 3.12.14；已有正确版本的 `.venv` 时跳过创建步骤。

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[ios]"
.\.venv\Scripts\python.exe setup_scrcpy.py
.\.venv\Scripts\python.exe scripts/install_scrcpy.py
npm ci --ignore-scripts
node node_modules/electron/install.js
npm --prefix android/chat-ui ci --ignore-scripts
& ./scripts/build-android.ps1 -SkipRuntime -SkipChat -Tasks @(':desktop-agent:assembleDebug')
npm run desktop:build
npm run desktop
npm run desktop:package
```

`desktop:build` 使用构建机的固定版本 CPython 标准库及 Node 可执行文件，重新安装锁定的 Python 运行依赖，不复制构建机的其他 Python 包。可通过 `BBUI_PYTHON_HOST` 指定构建用 Python。

产物位于 `.desktop/releases`；包含 ZIP、ZIP SHA-256、运行时版本与哈希清单、Node/Python/scrcpy 许可证及 npm 第三方许可证索引。未配置代码签名证书，本地 ZIP 不代表已签名的正式发布。

## 共享架构

- `android/chat-ui` 是两端共同使用的 React/assistant-ui 包。`mobile.tsx` 和 `desktop.tsx` 只负责宿主入口；聊天、Markdown、提问、会话、模型选择、阅读位置与草稿组件共用。
- `pi/android` 中的启动、协议适配、请求预算、搜索、记忆、任务状态与截图历史模块由两端共用。历史目录名保留，避免增加迁移维护。桌面仅替换设备传输扩展。
- `desktop/host.mjs` 持有一个 Python 执行器、一条设备 FIFO 队列和当前 Pi RPC 子进程。浏览会话不会重建设备、屏幕或执行器。任务提交绑定不可变的加密配置版本。
- `android/desktop-agent` 将现有 `DeviceService`、`SystemTools` 编入辅助 JAR，通过 ADB `app_process` 启动；系统查询不会建立虚拟屏。模型不获得通用 Shell。
- MCP 只发布 `phone_action`、`system_action`。接管、解除 STOP、预览与人工输入使用独立的宿主认证通道，认证能力不传给 Pi 或渲染层。
- Electron 沙箱、上下文隔离和受限 preload IPC 保持开启；页面 CSP 禁止联网。API 凭据只在宿主和对应 Pi 子进程内使用，配置通过环境传递，不写入启动文件或聊天快照。
- 模型截图使用保持原始像素尺寸的 JPEG，原始 PNG 留在本机；预览刷新不会创建模型观察编号。历史投影保留每个屏幕的最新观察，不更改 Pi 原始历史。

## macOS 后续接入

| 边界 | Windows 实现 | 后续工作 |
| --- | --- | --- |
| 资源定位 | `runtime-manifest.json` 和 `desktop/platform.mjs` | 增加独立 darwin arm64/x64 清单、运行时与 ADB/scrcpy 资源 |
| 进程生命周期 | `bbui/job_watch.py`、Windows Job、退出确认 | 增加进程组适配器和退出/崩溃清理测试 |
| 凭据 | platform 的 `seal/unseal`，Electron safeStorage | 继续使用同一接口，验证 macOS Keychain；迁移时重新输入并加密密钥 |
| 数据及锁 | Windows 用户数据目录；统一设备身份、文件锁 | 接入 macOS 平台目录，沿用数据版本 1 与 STOP/恢复语义 |
| 窗口及键盘 | Windows 关闭行为、Control 修饰键 | 实现菜单、Command 映射、最后窗口生命周期；语义按键模块继续共用 |
| 发布 | Electron packager Windows x64 ZIP | 单独构建、签名、公证及 Gatekeeper 验收流水线 |

无需为上述预留建立未实现的 UI 入口；当前平台能力仅声明 Windows 已实现的部分。

## 验证

```powershell
npm run typecheck
npm run test:desktop
npm run test:session-title
node --import tsx --test tests/desktop-integration.mjs
node tests/desktop-window.mjs
node tests/desktop-exit.mjs
node --import tsx --test tests/desktop-preview.mjs tests/desktop-onboarding.mjs tests/desktop-scroll.mjs
npm run test:pi
npm run test:protocols
npm run test:agent-tools
npm run test:screenshot-context
npm --prefix android/chat-ui run test:browser
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

`desktop-window` 检查实际 Electron 界面和系统加密，并以进程中断覆盖 Job 清理；`desktop-exit` 无调试器运行并通过实际窗口关闭路径验收保存和退出。设置 `BBUI_SMOKE_DEVICE` 后，后者还会连接指定手机并加载主屏预览。

`tests/smoke_desktop_device.py` 为显式真机测试，要求一个已授权设备及两个专用 fixture APK。脚本验证主屏、双虚拟屏、跨屏中文、全选/删除、四组系统工具、预览观察编号及接管/STOP；只修改专用测试包与临时共享文件，恢复文本剪贴板并卸载测试包。vivo 安装确认需要手机上的人工操作。

Android 和 iPhone 使用相同的断线交互：预览失败只在画面区域提示并重试；控制连接失效时立即暂停会话与等待队列，最多自动重连原设备 3 次。重连期间留在原页面，成功后保持暂停，失败则回到欢迎页，复用“连接手机”入口。重连只重建连接和读取新画面，不重放输入，也不自动继续任务；用户停止或关闭窗口会取消后续重试。旧进程与设备租约未确认释放时，不启动另一个执行器。

设置 `BBUI_SMOKE_DEVICE`（iPhone 另设 `BBUI_SMOKE_PLATFORM=ios`）后，`node --import tsx tests/desktop-device-recovery.mjs` 验证真实执行器断开后的自动恢复；`node tests/desktop-reconnect-device.mjs` 从实际 Electron 页面验证原会话、草稿及暂停队列保留，可追加发布包 EXE 路径。这些测试仅中断自己的测试执行器并读取主屏，不启动手机应用或重放输入。

设置 `BBUI_SMOKE_DEVICE` 后，`node tests/desktop-onboarding-device.mjs` 验证真机连接与模型配置缺失时停留欢迎页、明确进入、草稿保留及断开回退；可追加打包后的 EXE 路径。使用独立配置，不调用模型 API 或向手机发送输入。

人工输入耗时可用 `python tests/smoke_desktop_input_latency.py` 测量派发前与完整调用时间，用 `node tests/desktop-input-device.mjs` 测量 Electron IPC 往返。两者需显式设置 `BBUI_SMOKE_DEVICE`，使用独立配置，仅点击状态栏角落，不操作应用内容；后者可追加打包 EXE 路径，并用 `BBUI_INPUT_MAX_MS` 设置本机回归上限。这些数值不是触摸到屏幕显示的端到端延迟。

已有外部虚拟屏的真机只读验收使用 `python tests/smoke_desktop_external.py` 或 `node tests/desktop-external-device.mjs`，同样要求 `BBUI_SMOKE_DEVICE`。前者可用 `BBUI_EXTERNAL_DISPLAY` 指定现存编号；后者可追加打包 EXE 路径。检查画面、输入拒绝及退出后原屏幕仍存在，不创建或操作用户的虚拟屏。

设置 `BBUI_SMOKE_DEVICE` 后，`node tests/desktop-preview-device.mjs` 可测量实际 Electron 预览的图片显示频率；也可在命令末尾指定打包后的 `BBUI.exe`。它使用独立数据目录，短暂展开、收起通知栏制造动画，最后收起通知栏并关闭测试应用。结果写入 `runs/desktop-preview-device-*/result.json`。`BBUI_PREVIEW_MIN_FPS` 可设置本机回归阈值；静态页面的低帧率不代表卡顿，因为 scrcpy 按画面变化产帧。

模型和搜索自动化使用本地模拟服务；“连接测试”是用户明确触发的真实 API 请求。本次自动验收不使用真实云 API，也不宣称 macOS 已通过验收。
