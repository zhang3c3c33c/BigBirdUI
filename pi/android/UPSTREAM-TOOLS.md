# 上游工具

以下项目采用 MIT，版本固定在根目录 lockfile，构建时复制原许可证：

- `pi-web-access`：复用搜索与 HTTP 网页读取模块，适配应用工具接口。
- `pi-memory`：复用长期记忆的读写与恢复模块，适配应用存储。
- `@jyooi/pi-ask-user-question`：复用参数与结果格式，使用原生提问界面。

Pi 会话读取补丁处理 Unicode 行分隔符，不修改原始 JSONL。升级依赖后运行 `npm run test:agent-tools` 和 `npm run android:test-payload`。
