# 开源准备记录

以下记录初次整理时的状态；当前源码公开进度以 [发布清单](../development/RELEASE-CHECKLIST.md)和 GitHub 仓库为准。

日期：2026-09-29。范围：整理 Windows 桌面端（连接 Android / iOS）与 Android 本地版的文档、目录和发布准备材料。Pi / MCP / 旧 CLI 实现保留为内部组件与开发工具，不作为独立使用方式。

## 完成的整理

- 52 份原根目录文档移入 `docs/guides`、`docs/contracts`、`docs/development`、`docs/reports`，同步 Markdown 链接、仓库说明和桌面打包脚本的三个文档输入。
- 重写 README，添加完整文档索引、旧 CLI 指南、贡献指南、安全问题说明、第三方来源说明、首次发布清单、Issue / PR 模板和 Windows 无设备 CI。
- 两份 MCP 示例使用可替换绝对路径和显式 `BBUI_CONFIG_FILE`；Pi 指南移除本机已安装和已认证的假设，桌面指南补齐 Python、scrcpy 和 Chrome 前置要求。
- 十份旧手机操作 JSON 原样保留在 `runs/manual-actions/`。本机 Pi 偏好、认证、环境变量文件、签名材料和额外测试输出加入忽略规则。
- 四份文档中的当前设备序列号替换为 `REDACTED_DEVICE_SERIAL`。对候选文本执行常见密钥 / 私钥模式及本机路径检查，未发现匹配；扫描不等同于完整安全审计，素材与报告仍需维护者复核。

## 支持范围修正

根据维护者确认，对外只支持 Windows 桌面端与 Android 本地版。首页移除 Pi / MCP 的独立启动流程；三份接口文档及两份 MCP 配置示例归入 `docs/development/`，同步贡献指南、索引、仓库约定和问题模板。本次仅修改文档与示例位置，检查本地链接，不重复运行此前的代码测试。修正前文档副本保存在 `runs/open-source-prep/before-support-scope.zip`。

## 本地验证（上一轮整理）

Windows；Node 24.15.0、Python 3.12.14；使用已有本地依赖。

| 检查 | 结果 |
| --- | --- |
| Python `unittest discover -s tests -v` | 154 项通过 |
| 根目录 `npm run typecheck` | 通过 |
| 聊天 UI `npm --prefix android/chat-ui run typecheck` | 通过 |
| Pi / 桌面宿主 | 15 / 90 项通过 |
| 会话 / 任务状态 | 9 / 9 项通过 |
| Android 桥接 / 运行配置 | 17 / 13 项通过 |
| 截图上下文 / 协议 | 5 / 3 项通过 |
| Agent 工具 / 手机技能 | 22 / 4 项通过 |
| 日历 / 非 GUI 协议 | 1 / 1 项通过 |
| 聊天 UI 契约 | 18 项通过 |

Node 测试合计 207 项，与 Python 合计 361 项。测试日志在被忽略的 `runs/open-source-prep/`。

另检查示例 JSON、整理文档的本地链接、打包引用的文档存在性、打包脚本 JavaScript 语法及敏感文件的 Git 忽略行为。

没有执行真机、真实模型 API、浏览器 / Electron 集成、APK 或 Windows 发布包构建。GitHub Actions 尚未推送运行，干净环境重新安装依赖尚未验证；以上通过结果仅指本地无设备测试。

## 恢复与后续

整理前 477 个候选文件的原始字节保存在 `runs/open-source-prep/before-organization.zip`；对应哈希清单为 `before-organization.sha256.json`，文档映射为 `moves.json`。ZIP 已逐文件核验。备份含未脱敏原文，只作本机恢复用途，不进入公开仓库。

仓库仍无首个提交；未创建 GitHub 仓库、推送或发布。项目许可证、版权署名、具体仓库地址及安全联系渠道待维护者确定，完整后续见 [发布清单](../development/RELEASE-CHECKLIST.md)。
