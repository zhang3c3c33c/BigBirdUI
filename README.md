# BigBirdUI · 大鸟手机助手

**帮你操作手机的 AI 助手。** 用自然语言发起任务，让大鸟查看页面、点击、输入和滑动；执行过程中可以补充要求、随时停止或接管。

支持 Android 本机使用，以及 Windows 通过 USB 连接 Android / iPhone。你可以让它整理页面信息、查找内容，或在多个应用间完成操作。

> 当前为预览版。Android APK 使用调试签名，Windows 程序未做代码签名。任务表现取决于模型、设备与应用兼容性。

## 下载

前往 [Releases](https://github.com/zhang3c3c33c/BigBirdUI/releases) 下载：

| 使用方式 | 下载文件 |
| --- | --- |
| Android 本机使用（Android 11+、arm64） | `BigBirdUI-Android-arm64-debug.apk` |
| Windows 电脑连接手机（x64） | `BBUI-Windows-x64.zip` |

## 使用教程

### Android 本机使用

1. 安装大鸟手机助手 APK 和 [Shizuku](https://shizuku.rikka.app/download/)。
2. 开启手机开发者选项中的无线调试，按 [Shizuku 官方教程](https://shizuku.rikka.app/guide/setup/)完成配对并启动服务，无需 root 或电脑常驻。
3. 打开大鸟，在“手机控制”中点击“授权”，允许 Shizuku 访问。
4. 点击“添加模型”，按下方说明配置模型，再点击“开始使用”。
5. 输入任务，例如“打开计算器，计算 128 × 36”。展开手机画面查看过程，使用停止按钮结束任务，或点击“我来操作”接管。

手机重启后通常需要重新启动 Shizuku。Android 本地版在应用管理的虚拟屏内操作。

### 电脑连接 Android

1. 完整解压 Windows ZIP，运行 `BBUI.exe`；包内已包含运行依赖。
2. 在手机开发者选项中开启“USB 调试”，用可传输数据的 USB 线连接电脑。
3. 解锁手机，在调试授权提示中允许这台电脑。
4. 在大鸟中点击“连接手机”，选择 Android，点击“刷新设备”，选中手机并点击“连接”。
5. 配置模型后点击“开始使用”。可查看手机画面，或点击“我来操作”用鼠标和键盘接管。

手机无需安装大鸟 APK 或 Shizuku。找不到设备时，检查数据线、USB 驱动和调试授权；使用期间保持连接。

### 电脑连接 iPhone

1. 在 Windows 上安装 [爱思助手电脑端](https://www.i4.cn/)。
2. 用 USB 数据线连接 iPhone，解锁并“信任此电脑”，等待爱思助手安装 Apple 设备驱动并识别手机。
3. 在爱思助手进入“工具箱 → 实时屏幕”，按提示开启开发者模式并重启；重启后按系统提示再次确认开启。
4. 打开大鸟的 `BBUI.exe`，点击“连接手机”，选择 iPhone，再刷新、选中设备并连接。首次准备开发者支持文件可能需要联网。
5. 配置模型，点击“开始使用”，先发送“看看手机当前页面”，确认取得画面后再开始操作。

已安装驱动并开启开发者模式时可从第 4 步开始。爱思助手仅用于连接准备，iPhone 无需安装辅助 App。

iPhone 使用真实主屏，当前输入要求正向竖屏，不支持虚拟屏。已有验收机型为 iPhone SE（第三代）/ iOS 18.6.2，其他机型需实际验证。macOS 暂不支持。

### 配置模型

在模型设置中添加连接，填写 API 地址、API Key、协议和模型 ID，选择支持图片输入的模型并设为默认模型。

任务文字和必要截图会发送给你配置的模型服务，费用由服务商收取。请勿公开密钥或私人截图。断线、重开或重新连接后，旧任务不会自动重跑；停止不能撤回已经发出的操作。

## 开发

从源码运行与构建见 [构建说明](docs/BUILD.md)，修改执行器前阅读 [维护约定](docs/DEVELOPMENT.md)。

问题与建议请提交 [Issues](https://github.com/zhang3c3c33c/BigBirdUI/issues)，安全问题使用 [私密报告入口](https://github.com/zhang3c3c33c/BigBirdUI/security/advisories/new)。

## 许可证

BigBirdUI 自有代码采用 [MIT](LICENSE)。第三方组件保留各自许可证，见 [第三方说明](THIRD-PARTY-NOTICES.md)。

## 鸣谢

感谢以下开源项目及其贡献者：

| 项目 | 用途 |
| --- | --- |
| [Pi](https://github.com/earendil-works/pi) | Agent、模型与会话运行时 |
| [scrcpy](https://github.com/Genymobile/scrcpy) | Android 画面与输入控制 |
| [Shizuku](https://github.com/RikkaApps/Shizuku) | Android 本地授权与系统服务 |
| [pymobiledevice3](https://github.com/doronz88/pymobiledevice3) | iPhone USB 连接 |
| [UIAutomator2](https://github.com/openatx/uiautomator2) | Android UI 自动化 |
| [PyAV](https://github.com/PyAV-Org/PyAV) / [FFmpeg](https://ffmpeg.org/) | 视频解码 |
| [Electron](https://github.com/electron/electron) | Windows 应用宿主 |
| [React](https://github.com/facebook/react) / [assistant-ui](https://github.com/assistant-ui/assistant-ui) | 聊天界面 |
| [Node.js](https://github.com/nodejs/node) / [nodejs-mobile](https://github.com/fogtape/nodejs-mobile) | JavaScript 运行时 |
| [MCP](https://github.com/modelcontextprotocol) | 内部工具通信 |
| [pi-web-access](https://github.com/nicobailon/pi-web-access) | 搜索与网页读取 |
| [pi-memory](https://github.com/jayzeng/pi-memory) | 个人记忆 |
| [pi-ask-user-question](https://github.com/jyooi/pi-ask-user-question) | 结构化提问 |

以及 Python、Kotlin、AndroidX、TypeScript、Vite、esbuild、Gradle、Playwright 和其他依赖的维护者。
