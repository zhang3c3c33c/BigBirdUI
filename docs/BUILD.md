# 构建与测试

使用 Windows PowerShell，命令从仓库根目录执行。

## 环境

- Node 24.15.0、Python 3.12.14。
- Android 工具链：JDK 17、SDK 36、NDK 28.2.13676358、CMake 3.22.1。
- Windows 图标生成需要 Google Chrome。

设置 `JAVA_HOME` 和 `ANDROID_HOME`；SDK 默认路径为 `%LOCALAPPDATA%/Android/Sdk`。

## 准备

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

生成调试 APK：`android/app/build/outputs/apk/debug/app-debug.apk`。

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

生成便携包：`.desktop/releases/BigBirdUI-Windows-x64.zip`。

## 测试

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
npm run typecheck
npm --prefix android/chat-ui run typecheck
npm run test:pi
npm run test:desktop
```

完整检查见 [CI](../.github/workflows/ci.yml)，其他测试命令见 [package.json](../package.json)。单元测试无需手机或模型密钥；真机脚本执行前需阅读其前置条件。
