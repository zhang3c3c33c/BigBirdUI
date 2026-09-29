# Android 本地版验收记录

测试日期：2026-09-26。设备：vivo V2366GA，Android 16 / API 36，arm64。
使用 ADB 模式 Shizuku（shell UID 2000），未使用 root 或 Termux。

## 交付与使用

调试 APK：`android/app/build/outputs/apk/debug/app-debug.apk`，包名 `io.bbui.assistant`。
运行依赖 BBUI 与 Shizuku；instrumentation APK 仅用于开发验收。

最终 APK SHA-256：`7b48ae075910047daa9f048a6791b6c2fc49ad646d8af66a21c0e0f14b1c7522`。
已安装最终 APK，卸载本次仪器测试包；主应用正常连接到虚拟屏 49，显示
“BBUI 执行空间”，保持暂停等待任务。界面证据：`runs/android-gates/app-final.png`。

1. 启动 Shizuku，打开 BBUI，点击“连接设备”并授予 Shizuku 权限。
2. “悬浮控制条”需要单独允许显示在其他应用上层。
3. 填写支持图片和工具调用的 OpenAI-compatible Base URL、Model、API Key，保存配置。
4. 输入任务并运行；“全屏”展开虚拟屏预览，“接管”启用人工触摸，“停止”立即禁止新输入。
5. “继续”退出人工接管；输入新指令后重新观察，不重放先前动作。

API Key 使用 Android Keystore 加密后存于应用私有目录。手机本地运行的是
Pi 和设备执行器；当前模型推理通过用户配置的 API 进行，并非离线模型。

## 已验证

| 项目 | 结果 |
| --- | --- |
| Python 现有回归 | 42/42 通过 |
| TypeScript 严格类型检查 | 通过 |
| 现有 Pi 测试 | 3/3 通过 |
| Android HTTP 桥接测试 | 7/7 通过，包含中文 UTF-8、取消、工具错误与禁止重放 |
| Android 单元测试 | app 7/7、device 16/16 通过 |
| APK 构建与 Android Lint | 构建通过；0 错误、18 警告（含硬编码文案等未本地化项） |
| APK 内原版 Pi RPC | 真机通过；本地模拟 API 完成两轮图片与工具调用 |
| Node 原生运行时 | 真机启动，验证 crypto、WASM、worker 与 get_state |
| Pi 关闭后重启 | 同一协调器再次运行通过 |
| Pi 会话恢复 | 构建后实际 payload ZIP 主机测试通过；真实与模拟会话隔离 |
| 截图与输入隔离 | 真机连续 20 次虚拟屏点击，目标计数 20，主屏悬浮控件计数 0；模型 PNG 无悬浮层 |
| 过期截图及 STOP | 真机拒绝过期观察和停止后的输入 |
| 中文输入 | 虚拟屏测试输入框完整收到中文，经剪贴板 ACK 与读回校验 |
| 人工预览操作 | 真机接管后可点击；停止后同坐标不再增加目标计数 |
| 预览与生命周期 | 预览销毁 60 秒后仍持续解码、截图、点击；关闭重连后点击成功 |
| 防重复与接管代次 | 重复动作不再执行；旧接管代次被拒绝，新代次输入成功 |
| B站、京东、淘宝启动 | 三个应用均在虚拟屏内启动成功 |

完整仪器测试运行 164.854 秒，6 项中 4 项通过，2 项未通过：微信分身选择器、
Android 16 输入法焦点解析。前者保留为兼容限制；后者修复后定向复测结果另列。
最终输入法修复后，`DeviceExtendedGateTest` 连续运行 82.101 秒，2/2 通过：
显示屏 46 关闭后重建为 47，继续输入成功，随后独立虚拟屏中文输入完整通过。
日志：`runs/android-gates/instrumentation-ime-final.txt`。因此仍未通过的已知项目是
微信分身自动启动；不将整套仪器测试报告写成全绿。
先前的连续关闭挂起已修复，完整复测正常结束；关闭先 shutdown 通道，
厂商 MediaCodec 清理与 Binder 调用设有期限。修复前的卡住不算通过。

微信被 vivo `com.vivo.doubleinstance/.DoubleAppResolverActivity` 拦截，
先要求选择实例。程序拒绝把选择器误报为微信已打开；在实际使用中需“接管”后
选择目标实例，再退出接管并给出新指令。未验证选择后的完整微信业务任务。

## 依赖与限制

- 复用 Pi 0.87.0、scrcpy server 4.1、Shizuku API、NanoHTTPD、Grafika GL 工具。
- 嵌入 Node 来自社区 `fogtape/nodejs-mobile` v24.21.0-0 预发布版本，固定下载校验值；不是官方稳定 Android Node 发行版。
- 首版仅打包 arm64；其他设备、Android 版本及厂商限制尚未验证。
- 物理屏显示的是虚拟屏预览，不是替换 Android 默认显示屏。悬浮层在物理屏，模型图与注入目标在虚拟屏。
- 当前未提供真实视觉 API 配置，尚未完成云模型端到端任务。微信发消息、B站搜索、商品比较也未作为安卓 APK 端到端通过项。
- 四应用检查只启动应用；不发送消息、不加入购物车、不提交订单。
- Android 16 输入校验读取当前用户的实时 IME 状态，并核对当前客户端、焦点客户端、显示屏、应用与密码类型；不从历史记录推断当前输入目标。
- 尚未做拔线运行、真实 Shizuku 进程死亡、锁屏及旋转的完整真机故障矩阵。
- 本次交付为开发调试版本；保留调试测试页面，未配置正式发行签名。

## 复现

```powershell
npm run android:build
./scripts/build-android.ps1 -SkipRuntime -Tasks ':app:assembleDebugAndroidTest'
$env:ANDROID_SERIAL = '<目标设备序列号>'
python scripts/install-android.py
& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s $env:ANDROID_SERIAL shell am instrument -w -r io.bbui.assistant.test/androidx.test.runner.AndroidJUnitRunner
```

测试结果与本机私有截图保存在 `runs/android-gates/`（Git 忽略），
手机端证据位于本应用 `files/gate-evidence/`。不要将真实业务截图或私有会话提交到仓库。
# 流式聊天更新

2026-09-26 聊天改造的最新 APK、10 项组合真机验收和完整测试清单见 [流式聊天验收](ANDROID-CHAT-RESULTS.md)。下方保留此前设备基础验收记录。
