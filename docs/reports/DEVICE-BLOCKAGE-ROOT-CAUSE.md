# 2026-09-28 任务受阻根因

后续修复与真机验收已完成，见 [scrcpy 接入复核](../development/SCRCPY-INTEGRATION-REVIEW.md)。以下保留原始现场诊断。

只读现场诊断；未唤醒、重建屏幕或重放任务动作。

## 1. 目标屏信息在解析前被静默截断

`DeviceService.inspectDisplay()` 调用 `dumpsys activity activities`，随后从全文寻找 `Display #99`。同文件 `runCommand()` 仅在 StringBuilder 小于 300000 字符时继续追加整行，没有报告截断。

现场系统输出约 380336 字符，主屏 Display #0 在前，虚拟屏 Display #99 从约 320509 字符处开始。对保存的输出离线复现相同截断逻辑：保留 300023 字符，完整文本能匹配目标屏，截断文本不能。这是读取层的假阴性，不是目标显示屏已被删除。

任务时间线：00:14:31 打开 B 站已派发；00:14:38 开始报告“无法确认虚拟屏”；00:14:43 的点击在派发前被拒绝。后续反复查看仍调用同一截断路径，不能恢复。

根因代码：`android/device/src/main/java/io/bbui/device/DeviceService.kt` 的 `inspectDisplay()` 与 `runCommand()`。应按目标屏提取有界数据，并明确区分输出不完整与显示屏不存在；不应仅扩大统一字符上限。

## 2. 未启用虚拟屏保活，独立电源组约 60 秒后熄屏

现场 Display #99 属于电源组 1；组 0（主屏）仍可交互，组 1 不可交互、screen policy=0，显示设备 state=OFF，B 站 Activity 为 isSleeping=true。组 1 的 WakeLockSummary 为 0。

系统 screen timeout=60000ms。交叉读取的组 1 最后活动年龄为 600707ms，当前 OFF policy 持续 540828ms；两者之差 59879ms（dump 字段采集有约 0.1 秒时差），与 60 秒熄屏超时一致。换算 OFF 起点约为 00:15:14。

实际运行的 scrcpy 4.1 参数不含 `keep_active=true`。随项目保存的 Options 默认 keepActive=false；Controller 仅在该选项为真时启动每 4000ms 一次的目标显示屏保活。上游 `Device.keepActive(displayId)` 调用电源管理器的 `userActivity(displayId)`，不靠点击应用保活。

- [scrcpy 官方设备说明](https://github.com/Genymobile/scrcpy/blob/master/doc/device.md)
- [scrcpy 4.1 Device 实现](https://github.com/Genymobile/scrcpy/blob/v4.1/server/src/main/java/com/genymobile/scrcpy/device/Device.java)
- 本地源码：`vendor/scrcpy/Controller.java`、`Options.java`、`NewDisplayCapture.java`。

`power_on=false` 不是本次定时熄屏的配置：上游该逻辑只控制启动时是否唤醒主屏。虚拟屏使用独立 display group，主屏亮着或主应用前台服务存活不等于虚拟屏保持交互状态。

## 两个问题的先后及诊断边界

元数据读取先失败，输入因此被拒绝；约 36 秒后虚拟屏又因空闲超时进入 OFF。两个缺陷叠加。最后视频帧产生于约 00:14:34，早于 OFF：静止页面可以不再产生编码帧，不能把帧年龄单独当作视频故障证据。

现场文件在忽略目录 `runs/`：`current-task-activities.txt`、`current-task-displays.txt`、`current-task-power.txt`、`current-task-power-evidence.json`、`current-task-timeline.json`。正文及截图没有写入本报告。保活修复仍需结合 STOP、人工接管、锁屏和环境释放的生命周期，并在本地测试页验证，不自动续跑原任务。
