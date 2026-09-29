# 参与开发

项目仓库为 [zhang3c3c33c/BigBirdUI](https://github.com/zhang3c3c33c/BigBirdUI)，自有代码采用 [MIT 许可证](LICENSE)。所有命令从仓库根目录的 PowerShell 执行。

## 开发环境

Python 运行层要求 3.11+；Windows 桌面打包固定 Python 3.12.14 和 Node 24.15.0。创建虚拟环境，再安装完整测试依赖（包含 iOS 适配器）；已有 `.venv` 时跳过创建步骤：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[ios]"
npm ci --ignore-scripts
npm --prefix android/chat-ui ci --ignore-scripts
```

只运行单元测试不需要连接手机、模型密钥、Android SDK 或启动 Electron。Android 原生构建另需 JDK / SDK / NDK / CMake；详见 [构建说明](docs/development/ANDROID-IMPLEMENTATION.md)。

## 支持范围

对外支持 Windows 桌面应用和 Android 本地应用。Pi 命令行、独立 MCP 客户端接入和旧 CLI 仅保留为内部开发、调试与历史兼容实现，不作为面向用户的使用方式。应用内的 Pi 运行时和通信协议仍是实现的一部分；相关测试继续保留。内部资料见 [开发文档索引](docs/README.md#内部开发与历史接口)。

## 验证

跨 Python / TypeScript 的修改至少运行：

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
npm run typecheck
npm run test:pi
```

按改动范围补充以下检查；全部命令仍在仓库根目录执行：

| 范围 | 命令 |
| --- | --- |
| Windows 宿主、控制权与队列 | `npm run test:desktop` |
| 会话与自动标题 | `npm run test:session-title` |
| 任务状态 | `npm run test:task-state` |
| Android RPC 桥接 | `npm run test:android-bridge` |
| 模型配置、预算和探测 | `npm run test:android-runtime-config` |
| 历史截图与协议 | `npm run test:screenshot-context`、`npm run test:protocols` |
| 系统工具、记忆、提问 | `npm run test:agent-tools`、`npm run test:phone-skill`、`npm run test:calendar`、`npm run test:nongui` |
| 共享聊天 UI | `npm --prefix android/chat-ui run typecheck`、`npm run test:android-chat` |

GitHub Actions 在 Windows 上运行上述无设备检查；它不构建 APK / 发布包、不验证真实模型或手机。浏览器、Electron 和真机脚本为独立集成测试，按对应指南准备依赖后明确运行，不把硬件缺失报告为通过。

`npm run smoke:pi -- --device` 以及 `tests/*device*`、iOS USB 脚本可能连接或操作手机。真机报告应说明设备、系统版本、前置授权、具体操作和结果；使用专用测试数据。不要自动重放结果不确定的输入。

## 代码约定

Python 使用四空格、snake_case；TypeScript 使用两空格、单引号和分号，遵循 strict NodeNext。保留公开工具参数中的中文键；避免无关格式化。新增行为回归测试放入 `tests/test_*.py`、`tests/*.test.ts` 或对应模块现有测试位置。

修改执行器时保留最新观察校验、动作去重、设备所有权、STOP 与人工接管。分别报告派发、观察和连接状态，不在执行器中代替模型决定任务成功或固定操作预算。详见 [工具契约](docs/contracts/TOOL-CONTRACT.md) 和 [设计审查](docs/contracts/TOOL-DESIGN-REVIEW.md)。

## 文档与提交

使用指南放在 `docs/guides/`，接口契约放在 `docs/contracts/`，开发说明放在 `docs/development/`，验收记录放在 `docs/reports/`。文档中的命令和代码路径默认相对仓库根目录；Markdown 链接相对当前文件。改文件位置时同步索引、相对链接和打包脚本。

提交信息使用简洁祈使句，可使用 `fix:`、`feat:`、`docs:`。PR 说明问题、最终行为、验证结果和未覆盖项；手机截图先脱敏。不要提交 `config.local.json`、`.env`、认证文件、会话、截图、签名密钥和本机 SDK 路径。临时操作记录放在被忽略的 `runs/`。

发现安全问题时参见 [SECURITY.md](SECURITY.md)，不要在公开 Issue 中粘贴密钥或私人界面数据。
