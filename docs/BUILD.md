# 构建与测试

命令从仓库根目录执行；未注明时使用 Windows PowerShell。

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

Windows 构建会下载并校验固定版本的 iPhone DDI，随包内置。已有文件可放入 `vendor/ios-ddi/`：`Image.dmg`、`BuildManifest.plist`、`Image.trustcache`；版本与 SHA-256 见 `bbui/ios_ddi.py`。

## Linux

Linux 使用主机上的 Python、Node、adb 与 scrcpy，不打包独立运行时：

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -e .
npm ci --ignore-scripts
npm --prefix android/chat-ui ci --ignore-scripts
npm run desktop:build
npm run desktop
```

- 需要 Python 3.11+（含 `uiautomator2`、`av` 等依赖）、Node 24 和 `adb`；只读镜像窗口还需要系统 `scrcpy`，也可用 `BBUI_SCRCPY` 指定其他路径。构建时可用 `BBUI_PYTHON`、`BBUI_ADB` 覆盖清单中的可执行文件。
- iPhone 支持与随包运行时只在 Windows 构建中提供，Linux 不要安装 `.[ios]` 附加依赖。
- 会话未运行密钥环（gnome-keyring/kwallet）时，凭据仅由用户数据目录的文件权限保护，保存设置时会提示。
- `desktop-agent` APK 需先用 Android 工具链构建；缺失时 Android 系统级操作不可用，其他功能不受影响。
- 冒烟验证：`node tests/desktop-exit.mjs`（需先执行 `node node_modules/electron/install.js` 下载 Electron）。

## 测试

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
npm run typecheck
npm --prefix android/chat-ui run typecheck
npm run test:pi
npm run test:desktop
```

完整检查见 [CI](../.github/workflows/ci.yml)，其他测试命令见 [package.json](../package.json)。单元测试无需手机或模型密钥；真机脚本执行前需阅读其前置条件。
