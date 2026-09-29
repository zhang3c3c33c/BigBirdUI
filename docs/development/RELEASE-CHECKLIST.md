# 首次公开发布清单

当前阶段：源码已公开。仓库为 [zhang3c3c33c/BigBirdUI](https://github.com/zhang3c3c33c/BigBirdUI)；维护者已确认 MIT 许可证，版权署名为 zhang3c3c33c。预览安装包发布见 [v0.1.0-preview.1](https://github.com/zhang3c3c33c/BigBirdUI/releases/tag/v0.1.0-preview.1)；正式签名版本仍需后续验收。

## 仓库内容

- [x] 将使用指南、技术契约、开发说明和历史验收分目录保存，并建立索引。
- [x] 明确对外支持 Windows 桌面端与 Android 本地版；Pi 命令行、独立 MCP 和旧 CLI 归为内部开发资料，保留应用依赖的实现。
- [x] 将十份本机旧操作 JSON 原样归档到被忽略的 `runs/manual-actions/`。
- [x] 保留整理前文件的 ZIP 与 SHA-256 清单于 `runs/open-source-prep/`，不公开备份。
- [x] 新首页区分平台能力、源码启动、历史结果与本机构建产物。
- [x] 补齐贡献指南、问题 / PR 模板、第三方来源说明和无设备 CI 配置。
- [x] 维护者已授权继续公开发布；历史报告已脱敏，聊天测试夹具和品牌归档已复核。
- [x] 首次提交前复核候选文件、聊天测试夹具与品牌归档；未发现真实密钥、本机路径或设备序列号。模式扫描不能证明没有敏感数据。

## 许可证与分发

- [x] 确定 GitHub 仓库名称、所有者和正式 URL，补充相关项目元数据与链接。
- [x] 选定 MIT 许可证和版权署名，添加 `LICENSE`，同步包元数据及 Android / Windows 许可证打包步骤。
- [x] 在 GitHub 启用私密漏洞报告，更新 `SECURITY.md` 和 Issue 联系入口。
- [x] 核对本次预览包的依赖声明，随 Release 提供许可清单与相关官方源码材料；第三方许可不由项目 MIT 替代。

## 构建与发布验证

源码已推送到 `main`；提交 `75ee655` 的 [GitHub CI](https://github.com/zhang3c3c33c/BigBirdUI/actions/runs/36592792459)已在干净 Windows 环境通过。Python 154 项通过，Node 206 项通过、1 项因缺少未公开的本机会话夹具而跳过，两套 TypeScript 检查通过。首次运行发现测试依赖本机已有的 `runs/` 目录，已改为测试自行创建目录。

- [x] 在 GitHub 干净检出环境重新安装 Python、根目录 npm 和聊天 UI 依赖，执行无设备单元测试与类型检查；安装包构建另行验收。
- [x] 修复首次 CI 发现的测试目录依赖后，确认 GitHub Actions 实际运行通过。
- [ ] 独立验证 Android APK 与 Windows 便携包构建、启动、升级和退出。
- [ ] 记录发布版本对应的真机型号、系统版本、模型 / 搜索测试范围及已知限制。
- [x] 首次发布使用 `v0.1.0-preview.1` 预览标签，明确 APK 为调试签名、Windows 未代码签名，提供校验和与发布说明。
- [x] 发布附件限定为 APK、便携 ZIP、许可 / 对应源码材料、构建信息及校验和；不上传本机设置或测试记录。

## 提交前检查

在仓库根目录运行：

```powershell
git status --short
git ls-files --cached --others --exclude-standard
git status --short --ignored
```

暂存后检查 `git diff --cached --stat` 和 `git diff --cached`。`.gitignore` 只防止未跟踪文件被默认添加，不能移除已经提交的敏感信息。历史报告中的 `runs/` 路径仅指本机证据，不要求将证据上传。

本次整理的验证结果见 [开源准备记录](../reports/OPEN-SOURCE-PREP.md)。
