# 无 GUI 能力接入状态

更新：2026-09-28。日程之后已接入下面五组 Android 工具，接口见 `docs/contracts/NON-GUI-TOOLS-CONTRACT.md`。除标准时钟 Intent 外，均直接走当前用户系统 Provider，不创建虚拟屏。

现有应用管理、通知、剪贴板、共享文件已经走结构化系统操作。日程新增到同一体系。遵循此前范围，不重提屏幕、声音、网络设置、系统偏好、用户分身或诊断扩展。

| 方向 | 可提供的工具能力 | 当前证据与边界 |
| --- | --- | --- |
| 联系人 | 按系统姓名匹配搜索、详情、新建本地联系人、修改指定原始联系人 | `system_contacts`。临时联系人真机增改、去重、STOP及清理通过；新建不绑定同步账户，修改明确 rawContactId，仅替换提供的字段。不是 IM 好友查询。 |
| 短信查询 | 按地址/时间/文本检索，分页读取指定正文 | `system_sms`。真机用不存在的测试地址查询成功，未读取私人短信；正文分页与字符边界有单元覆盖，不提供发送或写入。 |
| 通话记录查询 | 按号码/时间/类型检索，详情 | `system_call_log`。真机用不存在的测试号码查询成功，未读取私人通话记录；不拨号、不改历史。 |
| 媒体检索 | 按日期、类型、名称查照片/视频/音频，返回尺寸、时长、大小及 URI | `system_media`。真机临时 PNG 的列表、详情、4×3 尺寸均验证；视频/音频分支未做正样本真机验收。只读取元数据，不理解图片或访问私有附件。 |
| 闹钟/倒计时 | 能力查询、请求标准时钟创建 | `system_clock`。固定 AlarmClock Intent 请求 skip UI；本机闹钟解析为系统选择器，倒计时解析为 vivo 时钟。仅验证能力发现和固定参数构造，未创建真实闹钟/倒计时，不保证无界面或最终创建成功。 |

所有新增能力通过结构化参数、当前 Android 用户、操作编号和 STOP 机制，工具仅报告事实，不开放通用 Shell。其它品牌和 Android 版本仍需实测；系统拒绝时报告原因，不启用权限回退。

## 官方资料

- [Contacts Provider](https://developer.android.com/identity/providers/contacts-provider)
- [Telephony / SMS Provider](https://developer.android.com/reference/android/provider/Telephony)
- [CallLog.Calls](https://developer.android.com/reference/android/provider/CallLog.Calls)
- [MediaStore](https://developer.android.com/reference/android/provider/MediaStore)
- [AlarmClock](https://developer.android.com/reference/android/provider/AlarmClock)
