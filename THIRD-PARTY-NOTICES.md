# 第三方组件

BigBirdUI 自有代码采用 [MIT](LICENSE)，第三方组件保留原版权及许可证。

| 组件 | 来源与许可材料 |
| --- | --- |
| scrcpy | 固定 4.1；[上游](https://github.com/Genymobile/scrcpy)、仓库内 `vendor/scrcpy/LICENSE`，下载脚本校验二进制哈希 |
| Pi、聊天 UI 与 npm 依赖 | 根目录及 `android/chat-ui` 的 lockfile；打包时保留许可证和组件索引 |
| Python、UIAutomator2、PyAV、iPhone 依赖 | `scripts/desktop/requirements.lock`；包内 Python 许可及各组件元数据 |
| Node / nodejs-mobile | 固定版本及对应上游许可证；Android 下载同时校验哈希 |
| Android、Kotlin、Shizuku | Gradle 依赖声明及实际解析的组件许可 |

Windows 中的 pymobiledevice3 9.34.0 及部分 iPhone 依赖声明为 GPL-3.0-or-later；部分视频库和 Python 包另有 LGPL、GPL 或 MPL 条款。项目 MIT 不替代这些条款。

[Release](https://github.com/zhang3c3c33c/BigBirdUI/releases) 附带许可、相关第三方源码及校验材料。再分发时应保留适用的版权声明、许可证与源码材料。主要项目鸣谢见 [首页](README.md#鸣谢)。
