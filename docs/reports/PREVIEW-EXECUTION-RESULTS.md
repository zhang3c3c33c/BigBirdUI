# 执行画面：工具意图与边缘状态

2026-09-27。

## 视觉依据

- [Apple 2024 Siri 设计](https://www.apple.com/newsroom/2024/06/introducing-apple-intelligence-for-iphone-ipad-and-mac/)用屏幕边缘光效表示助手处于活动状态，让中央内容仍然可见。
- [Chrome auto browse](https://support.google.com/gemini/answer/16821166?hl=en)在实际执行任务的标签页显示状态标识，同时提供接管。借鉴的是状态必须关联实际操作对象，不把查看中的聊天当执行者。
- BBUI 采用渐变边缘与真实工具意图，不加入虚构进度。按用户最终要求，边缘表示控制权未交给用户，执行状态另由顶部文字表达。使用 Android Canvas / SweepGradient / ValueAnimator，无新增动画依赖。尊重 [ValueAnimator.areAnimatorsEnabled](https://developer.android.com/reference/android/animation/ValueAnimator#areAnimatorsEnabled())，系统禁用动画时使用静态边缘。

## 落地

- 顶部移除返回、停止按钮，保留环境菜单；返回聊天使用系统返回手势或返回键。
- 顶部显示实际开始执行的工具意图，复用 ChatStore 已清理的标题；未执行的流式参数、JSON、历史工具不进入标题。工具结束后回到“AI 正在处理”，不将工具返回等同业务成功。
- execution 投影取自 runningSessionId 对应 ChatStore，并校验运行代次；切到其他会话不会改变执行意图。
- ready 环境且控制模式不是 manual 时保留边缘，包括闲置、停止、异常及交接中；交接真正完成后隐藏。闲置顶部显示“闲置”，执行中显示工具意图；只有执行中旋转渐变，其他状态使用静态边缘。失联或环境释放时隐藏；页面隐藏、退后台与销毁时停止动画。
- 边缘 View 不接收触摸，不进入无障碍焦点；标题提供文字状态。效果仅在本机预览视图绘制，未修改虚拟屏、scrcpy 视频或模型截图管线。
- 彩色边缘贴合实际视频图像矩形，排除 letterbox 黑边、顶部意图栏、底部操作按钮和系统栏。视频渲染与装饰复用 PreviewViewport 的等比缩放计算（包含 EGL 坐标取整）；视频尺寸变化会更新投影。内发光延伸 24dp，全部裁切在图像内，不再画虚假的圆角框。
- 修复 error 且无 continuation 时“我来操作”有标签但没有动作的问题。该状态允许接管；接管成功清除旧错误，仍遵守已有控制凭证及交接校验。

## 验证

### 最新控制权与真实视频验收

- app 60 项、device 20 项 JVM 测试通过。新增视口测试覆盖横竖屏、黑边、奇数像素取整及无尺寸状态。
- vivo V2366GA / Android 16 真机 2 项通过，无跳过：工具意图与控制权状态；无待续任务的异常状态接管、真实预览触摸计数恰好增加一次、结束人工操作回到闲置且边缘保留。
- 已查看真实视频截图 `runs/preview-control-live.png`：彩边贴住图像，左右黑边保持黑色，顶部“闲置”，内发光向图像内延伸。使用 BBUI 本地调试计数页，未调用真实模型 API。
- 日志：`runs/preview-control-build.log`、`runs/preview-control-test-build.log`、`runs/preview-control-device.log`。主 APK 覆盖安装，保留原有配置与会话；测试结束清理测试虚拟屏。

### 此前验收记录（视觉语义以上文最新结果为准）

- app 59 项 JVM 测试通过，新增投影回归覆盖不同查看/执行会话、未派发工具、工具失败结束、下一步骤及旧运行迟到事件。
- vivo V2366GA / Android 16 真机 3 项通过：意图与光效状态、旋转/前后台与返回、环境菜单取消不触发操作。未调用真实模型 API，光效效果通过合成运行状态呈现。
- 已查看真机效果截图 runs/preview-ai-edge.png（中央黑色区域是无视频源的合成预览）。新版覆盖安装；临时测试 APK 已移除，保留配置与会话。
- 构建日志 runs/preview-intent-build.log；真机日志 runs/preview-intent-device.log。
- 用户明确边框只围画面后，已将光效移到 SurfaceView 同级的局部容器；新增真机边界断言验证与画面矩形一致、不与接管按钮相交，复验 1 项通过。日志 runs/preview-edge-scope-device.log，截图已更新，修正版已覆盖安装。
