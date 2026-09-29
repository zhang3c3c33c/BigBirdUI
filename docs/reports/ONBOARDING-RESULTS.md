# 初次使用与日常权限恢复

2026-09-27。

开始页布局更新：已根据连接手机的实际画面简化为两张配置卡片，底部固定“开始使用”，统一使用现有配色。布局构建与现有引导真机检查（1 项）通过；新版覆盖安装，临时测试包已卸载，保留配置与会话。证据：runs/onboarding-layout-build.log、runs/onboarding-layout-device.log、runs/onboarding-layout-current.png。

- 思考档位显示 Pi 原始 ID，例如 off、low、high、max；default 对应空覆盖值，继续继承 Pi 设置，不翻译档位、不改变已有选择。
- 全新安装先打开使用引导：仅保留 Shizuku 启动/授权与默认模型两张配置卡片；悬浮控制条、通知等可选权限仅在设置中管理。已授权用完成标记展示，不重复显示禁用按钮；底部固定主入口，配置齐全后隐藏“稍后设置”。权限只在用户点击对应按钮时申请，不自动请求模型或操作手机。
- 可选择“稍后设置”；未完成时重启继续引导。识别老版本配置/会话文件，升级不重复引导。设置 → 帮助与诊断 → 使用引导可再次打开。
- 首次引导与权限可用性分开管理。复用 Shizuku Binder 收到、死亡与授权结果监听，在界面回到前台和发送/执行前重新检查。主界面在不可用时显示“Shizuku 未启动”或“Shizuku 未授权”及对应恢复入口；就绪后隐藏。
- 执行期间失去 Shizuku 服务或授权，保留任务结果并停止执行、暂停队列。授权恢复不会自动继续或重放。发送前已不可用时返回拒绝确认，保留输入草稿。

依据：[Shizuku SDK 的状态监听与权限检查](https://github.com/RikkaApps/Shizuku-API/blob/master/api/src/main/java/rikka/shizuku/Shizuku.java)、[Shizuku 服务撤销授权的处理](https://github.com/RikkaApps/Shizuku/blob/master/server/src/main/java/rikka/shizuku/server/ShizukuService.java)。撤销授权可能由 Shizuku 强制结束客户端，所以进程恢复仍沿用原有“保留中断任务、队列暂停、不自动重放”规则。

验证不撤销用户设备的实际授权，不调用真实模型 API。状态变化通过测试注入；队列暂停及恢复不重放通过真实服务验证。测试使用独立临时配置目录验证首次安装、未完成引导续接与已有安装跳过，保留用户密钥与会话。

结果：前端 9 项契约测试及完整浏览器回归通过，Android 构建与 app 55 项 JVM 测试通过；Vivo V2366GA / Android 16 上 OnboardingTest 4、SettingsActivityTest 3、ControlHandoffTest 1 共 8 项通过。主 APK 已覆盖安装，测试包已卸载；结束时无执行项、排队项或待续接任务。证据：runs/onboarding-browser.log、runs/onboarding-build.log、runs/onboarding-device.log、runs/onboarding.png。
