# scrcpy 输入通道接入

本工程直接复用官方 scrcpy 4.1 Android 服务端，Python 实现最小控制协议，不复制维护整套 Android 隐藏 API 兼容代码。

```text
当前对话中的决策模型
  → CLI / MCP tools
  → 观察版本、前台应用、目标区域、动作去重检查
  → Python scrcpy control client
  → ADB transport → Android localabstract socket
  → scrcpy-server (app_process, shell 身份)
      ├─ 点击：MotionEvent DOWN / UP → Android InputManager
      └─ 中文：UTF-8 SET_CLIPBOARD → ACK → GET_CLIPBOARD 核对
              → KEYCODE_PASTE DOWN / UP → 当前焦点输入框
```

读取仍由 UIAutomator2 提供截图和无障碍节点。微信在本机返回空树，因此实际任务使用截图定位，输入前通过 dumpsys input_method 核对焦点应用与非密码输入类型。

## 为什么可以解决本机的问题

UIAutomator2 剪贴板读取在这台 Android 16 设备上报 `Given calling package android does not match caller's uid 2000`。scrcpy 的 FakeContext 使用 com.android.shell，提供匹配 shell UID 的 AttributionSource 和 ContentResolver 兼容处理。复用官方服务后，剪贴板写入、读回和中文粘贴均已真机验证。

`INJECT_TEXT` 仍经 KeyCharacterMap 转按键，不代表任意 Unicode 直接写入应用。这里使用 scrcpy 的剪贴板通道，用户侧表现为直接输入中文，不要求切换/安装输入法。

## 生命周期与限制

- 固定 4.1 服务端，启动前校验 SHA-256；服务 JAR 放在手机 `/data/local/tmp/bbui-scrcpy-server-v4.1.jar`。
- 当前 CLI 每次动作创建控制连接，关闭后服务退出。不开视频、音频或电脑监听端口，不启动可见投屏窗口。
- 剪贴板自动同步关闭；输入会把目标文字放入手机剪贴板。为避免读取并持久化用户原剪贴板，MVP 不恢复原内容；电脑剪贴板不受影响。
- ACK 只证明控制消息被处理，不能证明粘贴或点击成功；必须再观察截图。
- 可视点击只检查目标周围区域变化，不是完整页面语义验证。聊天标题和草稿由决策模型查看截图确认。
- `input_focused` 在当前光标处插入，不清空已有草稿；调用前必须检查输入框。
- 点按 DOWN/UP 及粘贴事件各派发一次。断开、超时属于不确定状态，不自动重发。
- 当前只将 tap/tap_point/input_focused 接到 scrcpy；back、swipe、节点 set_text 仍走 UIAutomator2。
- 控制服务是官方 shell 进程，不是 APK；本次没有安装辅助输入法，原微信输入法保持不变。

## 来源

- [scrcpy 4.1 官方发布](https://github.com/Genymobile/scrcpy/releases/tag/v4.1)
- [Controller.java](https://github.com/Genymobile/scrcpy/blob/v4.1/server/src/main/java/com/genymobile/scrcpy/control/Controller.java)
- [控制协议解析](https://github.com/Genymobile/scrcpy/blob/v4.1/server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java)
- [FakeContext.java](https://github.com/Genymobile/scrcpy/blob/v4.1/server/src/main/java/com/genymobile/scrcpy/FakeContext.java)

官方原始服务二进制 SHA-256：`deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae`。
上游 Apache-2.0 许可证保存在 `vendor/scrcpy/LICENSE`。协议源码参考保存在同目录，未修改。
