# 构建与测试

在 Windows PowerShell 中，从仓库根目录执行命令。

## 环境

- Node 24.15.0、Python 3.12.14。
- 构建 Android 或桌面辅助组件：JDK 17、Android SDK 36、NDK 28.2.13676358、CMake 3.22.1。
- 构建桌面图标：Google Chrome（Playwright 使用 `chrome` 通道）。

`JAVA_HOME` 指向 JDK 17；`ANDROID_HOME` 指向 Android SDK，默认使用 `%LOCALAPPDATA%/Android/Sdk`。只运行 Python / Node 单元测试不需要 Android 工具链或手机。

## 准备源码

```powershell
git clone https://github.com/zhang3c3c33c/BigBirdUI.git
cd BigBirdUI
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[ios]"
npm ci --ignore-scripts
npm --prefix android/chat-ui ci --ignore-scripts
```

## Android

```powershell
npm run android:build
```

脚本准备聊天界面、内嵌 Node / Agent 运行包，并执行 Android 单元测试。生成调试签名 APK：`android/app/build/outputs/apk/debug/app-debug.apk`。安装及 Shizuku 授权见 [首页教程](../README.md#android-本机使用)。

## Windows

```powershell
.\.venv\Scripts\python.exe setup_scrcpy.py
.\.venv\Scripts\python.exe scripts/install_scrcpy.py
node node_modules/electron/install.js
& ./scripts/build-android.ps1 -SkipRuntime -SkipChat -Tasks @(':desktop-agent:assembleDebug')
npm run desktop:build
npm run desktop
npm run desktop:package
```

便携包位于 `.desktop/releases/BBUI-Windows-x64.zip`；未配置 Windows 代码签名。`BBUI_PYTHON_HOST` 可指定用于构建的 Python，`BBUI_DESKTOP_RELEASE_DIR` 可指定打包输出目录。

构建使用锁定版本的 Node、Python、Electron 和 scrcpy；保留 lockfile 与 `scripts/desktop/requirements.lock`。发布时只上传安装包、校验文件及所需第三方材料。

## 测试

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
npm run typecheck
npm --prefix android/chat-ui run typecheck
npm run test:pi
npm run test:desktop
npm run test:task-state
npm run test:android-chat
```

按修改范围运行 `package.json` 中其余 `test:*` 命令；[CI](../.github/workflows/ci.yml)列出完整无设备检查。

`npm run android:test-payload` 使用本地模拟服务检查打包后的 Agent。桌面构建后，可运行 `node tests/desktop-window.mjs` 和 `node tests/desktop-exit.mjs` 验证启动与退出；传入打包后的 EXE 路径可验证便携包。

真机检查为显式操作：`tests/smoke_desktop_device.py` 使用专用 Android fixture APK；`tests/ios_device_smoke.py` 检查 iPhone。执行前阅读各脚本的前置条件，使用专用测试数据，记录设备、系统和结果，不将单元测试通过视为真机验收。
