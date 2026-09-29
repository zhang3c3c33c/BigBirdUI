# 第三方组件与来源

本文件记录源码与构建脚本中的来源，不替代上游许可证，也不是完整的依赖许可审计。项目自有代码采用 [MIT 许可证](LICENSE)，版权署名为 zhang3c3c33c；第三方文件继续遵循其各自许可证。

| 组件 / 资源 | 仓库内依据 | 发布处理 |
| --- | --- | --- |
| scrcpy 4.1 服务端与协议参考 | `vendor/scrcpy/`、`setup_scrcpy.py` | 保留 [上游许可证](vendor/scrcpy/LICENSE)（Apache-2.0）；安装脚本固定服务端 SHA-256 |
| scrcpy Windows 客户端及附带资源 | `scripts/install_scrcpy.py` | 从固定版本归档下载并校验 SHA-256；下载归档和解压目录被 Git 忽略 |
| Pi、MCP SDK、Electron 与 npm 依赖 | `package.json`、`package-lock.json` | 遵循各包的许可证；桌面构建收集依赖声明和许可证索引 |
| React / assistant-ui 聊天 UI | `android/chat-ui/package.json` 和独立 lockfile | 桌面构建复制 UI 依赖的许可证文件 |
| Python、UIAutomator2、PyAV、pymobiledevice3 等 | `pyproject.toml`、`scripts/desktop/requirements.lock` | 桌面包含 Python 许可证及已安装包元数据；逐项核对再分发要求 |
| Android 内嵌 Node | `scripts/android-runtime/prepare.mjs` | 固定 fogtape/nodejs-mobile 版本及二进制、许可证哈希，随运行时资产复制许可证 |
| Android / Kotlin / Shizuku 依赖 | `android/*/build.gradle.kts` | 以 Gradle 解析结果与各上游许可证为准，发布 APK 前补齐完整清单 |

scrcpy 的来源地址与版本写在下载脚本中。Java 文件和协议文档为开发参考；不要把上游资源误标为 BBUI 原创代码。

桌面构建生成 `.desktop/app/THIRD-PARTY-NOTICES.json`，记录 Node、Python、scrcpy、npm 及 UI 组件路径。它是构建产物的一部分，不应作为完整许可核查已完成的证明。发布 ZIP / APK 前，核对实际分发文件的许可证、NOTICE、归属和源代码提供要求，并确保项目级许可证一并进入发布材料。

## v0.1.0-preview.1 附件

首次预览版包含独立的 `THIRD-PARTY-LICENSES.zip`，汇总 npm、UI、Android 运行依赖的声明和随包许可，以及 Node、Python、Electron、scrcpy 与视频库许可。Android 清单来自实际解析的 71 个外部依赖。

Windows 包中的 `pymobiledevice3 9.34.0` 及部分 iPhone 依赖声明为 GPL-3.0-or-later；FFmpeg / 编解码库以及部分 Python 包另有 LGPL、GPL 或 MPL 条款。项目根目录的 MIT 只适用于 BigBirdUI 自有代码，不能代替这些组件的许可证。完整包再分发需保留相应许可和源码材料。

同版 Release 提供 `PYTHON-COMPONENT-SOURCES.zip`（17 个 Python 组件的官方源码发行包）及 `VIDEO-LIBRARY-SOURCES.zip`（14 个视频组件的官方源码归档、来源 / SHA-256 清单与上游构建资料）。源码归档按官方发布的哈希验证；BigBirdUI 的源码和构建脚本可从同版 Git 标签取得。第三方原作者的版权与许可证保持不变。
