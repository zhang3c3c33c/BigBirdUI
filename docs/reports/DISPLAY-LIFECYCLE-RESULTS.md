# 虚拟屏生命周期

2026-09-27，已覆盖安装至 vivo V2366GA / Android 16（API 36）。

## 行为

- 执行任务、人工接管或显式连接诊断时按需创建，健康连接继续复用。浏览会话、打开预览与设置不会创建屏幕。
- 屏幕资源状态（absent/creating/ready/releasing/invalid）、输入控制权（AI/用户/禁用）、预览可见性分别维护。收起预览或退后台只解绑预览；STOP、锁屏和人工接管保留现场。
- 后台连续空闲 10 分钟回收。前台界面（含设置）、可见预览、正在执行、人工操作、待续任务、可执行队列或当前环境所属会话明确 waiting_user 时取消计时。已暂停队列本身不阻止回收。使用单次 Handler 回调和单调时钟，不轮询、不按聊天刷新延后期限。
- 等待用户取自现有 task_state 展示投影，只读取最近使用该环境的会话，不从普通回复猜测，也不被其他历史会话永久占用。
- 执行画面菜单提供“关闭执行环境”，确认后停止任务、暂停队列、保留聊天和待续记录。确认绑定 controlId 与 environmentId，旧确认不能关闭新环境。
- 资源释放沿用 scrcpy 与 Shizuku 清理路径，不关闭会话目录服务。释放和创建串行，释放期间拦住接管入口，结束后可显式重建。
- 确定的 Binder/视频失联清理环境，不自动恢复任务。输入派发结果不确定沿用故障隔离，不新增自动销毁或输入重试。
- 环境身份复用每次 Shizuku 连接的随机 ticket，纳入观察身份及工具返回；重建后旧截图拒绝派发。Pi 启动时收到当前环境事实，要求先观察，不重放历史动作。
- 保留 vd_destroy_content=true，关闭时不将虚拟屏应用迁移到主屏。非 ready 状态隐藏旧帧，预览标题显示实际资源状态。

## 审查修正

- 区分输入不确定与环境确定失效，避免将前者扩大为整屏清理。
- 释放与启动/接管互斥，过期 Binder 清理绑定旧 ticket。
- 应用级前台判断包含设置页，排除虚拟屏上的 WorkspaceActivity。
- 服务销毁后不再重新注册空闲回调；清理异常收敛至 invalid。
- 失效画面不继续显示旧 Surface 帧。

## 验证

- TypeScript 检查通过；Pi 11 项、Android 桥接 16 项、Python 62 项通过。
- Android app 58 项、device 20 项、runtime 4 项 JVM 测试通过。
- 实际 APK Pi 资源包测试通过，仅使用本地模拟模型。
- 真机 6 项通过、无跳过：EnvironmentUiTest、EnvironmentServiceTest、DisplayLifecycleTest；ChatWebViewTest 两项（预览控制/失效隐藏、旋转与前后台），ControlHandoffTest 一项。
- 服务层测试真实退后台，并推进测试中的空闲起点模拟 10 分钟到期；没有实际等待十分钟，也未改变生产超时。
- 真机直接验证健康复用、STOP 与预览解绑保留、显式关闭、旧确认拒绝、重建身份变化、旧截图点击未派发；只操作测试页面，不调用真实模型 API。
- 结束时无运行项、排队项、待续任务或 scrcpy 虚拟屏；测试 APK 已移除，用户配置/会话保留。

证据日志位于 runs/display-lifecycle-{build,types,pi,bridge,python,payload-test,device,regression-device}.log。
