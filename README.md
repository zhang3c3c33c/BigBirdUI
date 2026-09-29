# BigBirdUI · 大鸟手机助手

**帮你操作手机的 AI 助手。**

BigBirdUI（大鸟手机助手，简称 BBUI）是一款帮你操作手机的 AI 助手。告诉它你想做什么，它会查看手机画面、理解当前页面，结合应用操作与系统工具逐步执行任务。查找信息、整理内容、处理多步操作，都可以从一段自然语言开始。

你可以直接在 Android 手机上使用，也可以在 Windows 电脑上连接手机。执行过程中能查看进展、补充要求，并随时停止或接管。

> 当前为开发预览阶段，源码以 MIT 许可证开放，安装包仍处于发布准备阶段。任务表现取决于模型能力、设备与应用兼容性；已验证范围见 [验收记录](docs/README.md#历史验收记录)。

## 大鸟可以帮你做什么

- **看懂页面，接着操作。** 根据实际画面识别内容，点击、输入、滑动，并查看操作后的结果。
- **串起多步任务。** 围绕一个目标打开应用、查找信息、切换页面或应用，减少手动来回操作。
- **调用手机工具。** 在支持的平台上查询或处理应用、文件、剪贴板等信息；Android 还提供日程、联系人等系统能力。具体范围随平台而异。
- **安排任务，保留进展。** 用会话管理不同任务，将新需求加入队列，执行中也可以补充要求。
- **记住偏好，选择模型。** 保存跨会话的个人偏好，按需要配置自己的模型服务。

例如，你可以这样描述任务：

> “看看当前页面，帮我整理出几个要点。”
>
> “打开哔哩哔哩，搜索 Python 入门教程，整理前几个结果。”
>
> “在两个购物应用里查找同款耳机，对比价格和规格，不要下单。”

以上是任务表达示例，不是逐项通过验收的承诺。你可以展开手机画面查看实际操作；需要调整时，补充要求或点击接管。

## 在手机上用，或从电脑连接

| 版本 | 如何使用 | 文档 |
| --- | --- | --- |
| Windows 桌面端 | 通过 USB 连接 Android 或 iPhone，在电脑上发起任务、查看画面和接管操作 | [桌面使用与构建](docs/guides/DESKTOP.md) |
| Android 本地版 | 安装 APK 并通过 Shizuku 授权，直接在手机上配置模型并运行任务，无需电脑常驻 | [Android 构建与架构](docs/development/ANDROID-IMPLEMENTATION.md) |

Windows 桌面端可查看 Android 主屏并使用任务虚拟屏；Android 本地版在应用管理的虚拟屏内执行界面操作。iPhone 当前使用真实主屏，详见 [iPhone 能力边界](docs/guides/IOS-DESKTOP-CAPABILITIES.md)。macOS 尚未实现和验收。

## 使用教程

按设备选择一种方式：[Android 本机使用](#android-本机使用) · [电脑连接-Android](#电脑连接-android) · [电脑连接-iPhone](#电脑连接-iphone)。电脑端当前支持 Windows x64。

当前尚未提供正式发布下载地址。以下步骤适用于已取得或自行构建的 APK / Windows 便携包；构建方法见下方“从源码构建”。现有程序与安装包仍使用简称 `BBUI`，Windows 启动文件为 `BBUI.exe`。

### Android 本机使用

适用：Android 11 及以上的 arm64 手机。最低系统版本来自当前构建配置，具体机型兼容性仍以实际连接与验收结果为准。

1. **安装应用。** 将大鸟手机助手 APK 安装到手机，并安装 [Shizuku](https://shizuku.rikka.app/download/)。
2. **启动 Shizuku。** 在手机开发者选项中启用无线调试，按 Shizuku 的提示用配对码完成配对，再回到 Shizuku 启动服务。无需电脑常驻，也无需 root；手机重启后通常需要重新启动服务。具体步骤见 [Shizuku 官方教程](https://shizuku.rikka.app/guide/setup/)。
3. **授予手机控制权限。** 打开大鸟手机助手，在引导页的“手机控制”卡片点击“授权”，允许 Shizuku 访问。确认显示“Shizuku 已授权”。
4. **配置模型。** 点击“添加模型”，按下方[模型配置](#模型配置)填写服务信息，并选择默认模型。
5. **开始任务。** 两项准备就绪后点击“开始使用”，输入任务并发送。例如：“打开计算器，计算 128 × 36。”可展开手机画面查看过程，点击“我来操作”接管，或使用停止按钮结束执行。

若提示 Shizuku 未启动或未授权，先恢复服务与权限，再回到应用。恢复后旧任务不会自动重跑，需要你明确继续。

### 电脑连接 Android

适用：Windows x64 电脑、Android 手机和可传输数据的 USB 线。手机不需要安装大鸟手机助手 APK 或启动 Shizuku。

1. **打开电脑端。** 将 `BBUI-Windows-x64.zip` 完整解压，运行其中的 `BBUI.exe`。便携包包含运行依赖，无需另装 Python、Node 或 scrcpy。
2. **开启 USB 调试。** 在手机设置中启用开发者选项，再打开“USB 调试”；菜单位置随品牌而异，参见 [Android 官方说明](https://developer.android.com/studio/debug/dev-options)。
3. **连接并授权。** 用 USB 线连接电脑，解锁手机，在手机上的调试授权提示中允许这台电脑。
4. **选择设备。** 在大鸟手机助手欢迎页点击“连接手机”，选择 Android，点击“刷新设备”，选中手机后点击“连接”。等待界面显示已连接；仅选择设备不会建立连接。
5. **配置模型并开始。** 按下方[模型配置](#模型配置)设置默认模型，返回欢迎页点击“开始使用”。输入任务后，可在右侧查看手机画面；点击“我来操作”可用鼠标和键盘接管。

找不到手机时，先检查数据线、手机是否已解锁、USB 调试授权，以及电脑是否识别设备。使用期间保持 USB 连接；断线恢复后核对当前画面，再明确继续任务。

### 电脑连接 iPhone

适用：Windows x64 电脑、iPhone 和可传输数据的 USB 线。当前验收设备为 iPhone SE（第三代）/ iOS 18.6.2，其他系统版本需实际验证。iPhone 不需要安装大鸟手机助手或辅助 App。

1. **安装爱思助手。** 在 Windows 电脑上从 [爱思助手官网](https://www.i4.cn/)下载并安装电脑端。
2. **连接手机并安装驱动。** 用 USB 数据线连接 iPhone，保持手机解锁，按提示点击“信任此电脑”。等待爱思助手安装所需的 Apple 设备驱动，直到成功识别手机；如提示安装驱动，按提示完成。
3. **开启开发者模式。** 在爱思助手顶部点击“工具箱”，选择“实时屏幕”，按弹窗指引开启手机的“开发者模式”并重启。重启后解锁手机，按系统提示再次确认开启；准备完成后回到大鸟手机助手。
4. **连接大鸟手机助手。** 在 Windows 端运行 `BBUI.exe`，点击“连接手机”，选择 iPhone，再点击“刷新设备”、选择手机并“连接”。首次准备开发者支持文件可能需要联网，请保持手机解锁并等待完成。
5. **配置模型并开始。** 按下方[模型配置](#模型配置)设置默认模型，点击“开始使用”，先发送“看看手机当前页面，告诉我有哪些内容”，确认能取得画面后再发起操作任务。

如果电脑已经能识别 iPhone，且手机已开启开发者模式，可直接从第 4 步开始。爱思助手用于首次连接准备，大鸟手机助手通过自己的 USB 连接提供画面和操作。

iPhone 使用真实主屏，当前输入操作要求正向竖屏，不支持 Android 式虚拟屏。操作时保持手机可用，预览默认只读；需要手动操作时点击“我来操作”。更详细的支持范围见 [iPhone 能力清单](docs/guides/IOS-DESKTOP-CAPABILITIES.md)。

### 模型配置

三个场景都使用你自己的模型服务，界面操作需要支持图片输入的模型。

1. 在应用模型设置中添加连接，填写连接名称、API 地址和 API Key，选择与服务商匹配的 API 协议。
2. 从服务商获取模型列表，或手动填写准确的模型 ID；确认所选模型支持图片输入。
3. 保存连接并设为默认模型，再返回开始页。API 地址、密钥和模型是否可用以服务商配置为准；主动发送测试请求可能产生服务费用。

不要把 API Key 写入公开文件或截图。执行任务时，必要的任务文字和截图会发送给所选模型服务。

## 操作过程由你掌握

任务执行时可以查看进展和手机画面，需要时停止或切换为人工操作。断线、程序重开或重新连接后，旧任务不会自动重跑；停止也无法撤回已经发出的操作。

会话和设置保存在各端自己的数据目录。执行任务时，相关截图和内容会发送给你配置的模型服务；搜索使用所配置的搜索服务。模型需要支持图片输入，服务费用由对应提供方收取。

开发调试记录位于被 Git 忽略的 `runs/`。工具派发与观察校验的详细约定见 [工具契约](docs/contracts/TOOL-CONTRACT.md)，Android 数据管理见 [存储生命周期](docs/development/STORAGE-LIFECYCLE.md)。

## 从源码构建

先取得源码，后续命令均在仓库根目录执行：

```powershell
git clone https://github.com/zhang3c3c33c/BigBirdUI.git
cd BigBirdUI
```

Windows 桌面端需要 Node 24.15.0、Python 3.12.14、Android 构建工具链及 scrcpy 资源。完成[桌面构建步骤](docs/guides/DESKTOP.md)后，`npm run desktop:package` 生成 `.desktop/releases/` 下的便携包。此路径是本机构建产物，不是下载地址。

Android 需要 JDK 17、Android SDK 36、NDK 28.2.13676358、CMake 3.22.1 及根目录 npm 依赖。执行 `npm run android:build`，调试 APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。安装后启动 Shizuku、授权并配置模型。详见 [Android 实施说明](docs/development/ANDROID-IMPLEMENTATION.md)。

## 仓库结构

```text
bbui/          Python 执行器、屏幕管理及 Android / iOS 连接
pi/            应用内 Agent 运行时、模型桥接和共享模块
android/       Android 应用、原生运行时与共享聊天 UI
desktop/       Windows Electron 宿主
scripts/       安装、构建、打包脚本
tests/         单元测试、集成测试和显式真机测试
docs/          使用指南、技术契约、开发说明和历史验收记录
design/        品牌源文件及设计资源
vendor/        随源码保留的 scrcpy 资源和上游许可证
```

## 开发与参与

源码仓库：[zhang3c3c33c/BigBirdUI](https://github.com/zhang3c3c33c/BigBirdUI)；问题与建议请通过 [GitHub Issues](https://github.com/zhang3c3c33c/BigBirdUI/issues)提交。

环境准备、测试命令和提交约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。[文档索引](docs/README.md)列出所有专题；[发布清单](docs/development/RELEASE-CHECKLIST.md)记录源码公开结果与安装包发布待办。

## 许可证与第三方组件

BigBirdUI 自有代码采用 [MIT 许可证](LICENSE)，Copyright © 2026 zhang3c3c33c。第三方组件保留各自的许可证，来源和打包说明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

## 鸣谢

BigBirdUI · 大鸟手机助手建立在开源社区的工作之上。感谢以下项目及其贡献者：

| 开源项目 | 在本项目中的用途 |
| --- | --- |
| [Pi](https://github.com/earendil-works/pi) | Agent 运行时、模型接入、会话和工具循环 |
| [scrcpy](https://github.com/Genymobile/scrcpy) | Android 画面传输、虚拟屏及输入控制 |
| [Shizuku](https://github.com/RikkaApps/Shizuku) / [Shizuku API](https://github.com/RikkaApps/Shizuku-API) | Android 本地版的授权与系统服务访问 |
| [pymobiledevice3](https://github.com/doronz88/pymobiledevice3) | iPhone USB 连接、设备服务和操作能力 |
| [UIAutomator2](https://github.com/openatx/uiautomator2) | Android UI 自动化和节点观察 |
| [PyAV](https://github.com/PyAV-Org/PyAV) / [FFmpeg](https://ffmpeg.org/) | 视频解码与图像处理基础 |
| [Electron](https://github.com/electron/electron) | Windows 桌面应用宿主 |
| [React](https://github.com/facebook/react) / [assistant-ui](https://github.com/assistant-ui/assistant-ui) | 两端共享的聊天界面与交互组件 |
| [Node.js](https://github.com/nodejs/node) / [nodejs-mobile](https://github.com/fogtape/nodejs-mobile) | JavaScript 运行时及 Android 内嵌运行支持 |
| [MCP TypeScript SDK](https://github.com/modelcontextprotocol/typescript-sdk) / [Python SDK](https://github.com/modelcontextprotocol/python-sdk) | 内部工具通信与协议接入 |
| [pi-web-access](https://github.com/nicobailon/pi-web-access) | 联网搜索与网页读取能力的接入基础 |
| [pi-memory](https://github.com/jayzeng/pi-memory) | 个人记忆能力的接入基础 |
| [pi-ask-user-question](https://github.com/jyooi/pi-ask-user-question) | 结构化提问与用户回答能力的接入基础 |

也感谢 Python、Kotlin、AndroidX、TypeScript、Vite、esbuild、Gradle、Playwright，以及其他直接和间接依赖的维护者。以上是主要项目鸣谢；许可证与打包清单见 [第三方组件说明](THIRD-PARTY-NOTICES.md)，各组件权利归其原作者所有。
