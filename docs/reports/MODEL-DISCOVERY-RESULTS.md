# 供应商模型列表

2026-09-27。

连接编辑页新增“从供应商获取模型”，使用当前尚未保存的 API 协议、地址和 Key 发起只读 GET 请求。结果在独立原生列表页搜索、多选，已添加项明确标记且不重复导入；选择后回到连接表单，由“保存连接”统一保存。保留“手动添加模型”。

OpenAI Chat Completions 和 Responses 共用该连接的 `/models` 端点与 Bearer 认证；Anthropic 使用 `/v1/models`、`x-api-key`、`anthropic-version` 及 `after_id` 分页。保留代理地址前缀；不自行切到其他供应商，不跟随带认证的重定向。接口不支持、鉴权失败、超时或无效分页均显示简短事实提示，原始错误体不进入 UI。

列表只导入 ID 和显示名称，不能据此证明聊天、图片、工具或思考支持；已有 Pi 元数据与模型测试流程继续负责能力判断。该请求使用 Android 原生 HTTP 能力，不占用 Pi/手机执行实例，不发送聊天历史、截图或生成请求。离开页面取消请求，旧请求不能回填新的连接；页面重建保留列表及选择。

依据：[OpenAI List models](https://developers.openai.com/api/reference/resources/models/methods/list)、[Anthropic List Models](https://platform.claude.com/docs/en/api/models/list)、[DeepSeek Lists Models](https://api-docs.deepseek.com/api/list-models/)。

测试使用本地模拟端点，不使用真实供应商密钥。新增 JVM 回归覆盖两种 OpenAI 协议共用列表接口、Anthropic 分页和认证、去重、代理路径、错误脱敏及不跟随重定向。真机测试覆盖列表获取、Activity 重建不重复请求、搜索、多选和已有模型去重。

结果：构建通过，app JVM 55 项通过（新增 6 项），Vivo V2366GA / Android 16 上新增发现测试与既有设置回归共 4 项通过。主 APK 已覆盖安装，配置与会话保留，临时测试包已卸载。日志：runs/model-discovery-build.log、runs/model-discovery-device.log。
