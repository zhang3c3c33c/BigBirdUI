import { Type } from 'typebox';

const operations = ['查看', '等待', '点击', '双击', '长按', '滑动', '拖拽', '放大', '缩小',
  '输入内容', '删除内容', '全选', '按键', '打开应用', '列出应用', '系统面板', '创建屏幕', '列出屏幕', '关闭屏幕'];
const virtualKeys = ['返回', '主页', '最近任务', '回车', '删除', '向前删除', '上', '下', '左', '右'];

export interface DeviceCapabilities {
  devicePlatform?: 'android' | 'ios';
  phoneOperations?: string[];
  phoneKeys?: string[];
  systemOperations?: Record<string, string[]>;
}

export function desktopCapabilities(): DeviceCapabilities {
  return process.env.BBUI_DEVICE_CAPABILITIES ? JSON.parse(process.env.BBUI_DEVICE_CAPABILITIES) : {};
}

function phoneSchema(android: boolean, capabilities: DeviceCapabilities = {}) {
  const ios = capabilities.devicePlatform === 'ios';
  return Type.Object({
  意图: Type.String({ minLength: 1, maxLength: 80, pattern: '\\S',
    description: '简明说明这一步准备做什么，例如“点击打开”“下滑列表”“搜索天气”；不含密码、验证码或令牌，不表示操作已成功。' }),
  操作: Type.Union(operations.filter(x => (!android || !['系统面板', '创建屏幕', '关闭屏幕'].includes(x)) && (!capabilities.phoneOperations || capabilities.phoneOperations.includes(x))).map(x => Type.Literal(x))),
  参数: Type.Object({
    执行后等待毫秒: Type.Optional(Type.Integer({ minimum: 0, maximum: 30000, default: 0, description: '动作后等待再截图，省略为0。等待操作使用时间作为总等待时长，不与本字段累加。' })),
    屏幕会话: ios ? Type.Literal('main', { description: 'iPhone 仅支持 main 真实主屏。' }) : Type.Optional(android ? Type.Literal('virtual') : Type.String({ description: 'main=主屏，其他为创建屏幕时指定的英文名称。同屏顺序执行，跨屏幕可并行。' })),
    截图编号: Type.Optional(Type.String({ description: '基于画面的输入需引用本屏幕最新返回的截图编号。查询无需截图编号。' })),
    位置: Type.Optional(Type.Array(Type.Integer(), { minItems: 2, maxItems: 2 })),
    起点: Type.Optional(Type.Array(Type.Integer(), { minItems: 2, maxItems: 2 })),
    终点: Type.Optional(Type.Array(Type.Integer(), { minItems: 2, maxItems: 2 })),
    中心: Type.Optional(Type.Array(Type.Integer(), { minItems: 2, maxItems: 2 })),
    时间: Type.Optional(Type.Integer({ minimum: 0, maximum: 30000, description: '等待操作的总等待时长0～30000毫秒；手势时长最多5000毫秒。等待时优先使用本字段，否则使用执行后等待毫秒。' })),
    按住时间: Type.Optional(Type.Integer({ minimum: 0, maximum: 5000, description: '拖拽起点按住时间，毫秒。' })),
    初始指距: Type.Optional(Type.Number()), 结束指距: Type.Optional(Type.Number()),
    内容: Type.Optional(Type.String({ maxLength: 2000 })),
    次数: Type.Optional(Type.Integer({ minimum: 1, maximum: 100 })),
    键名: Type.Optional(Type.Union((capabilities.phoneKeys || (android ? virtualKeys : [...virtualKeys, '音量加', '音量减', '唤醒'])).map(x => Type.Literal(x)),
      { description: ios ? 'iPhone 主屏支持的语义按键；没有通用返回键。' : android ? '路由到virtual显示屏的按键；主页/最近任务可能打开系统界面，依据返回画面判断效果。' : '音量加/音量减/唤醒/最近任务仅main支持，其他按键按屏幕会话派发。' })),
    ...(android || ios ? {} : { 面板: Type.Optional(Type.Union(['通知', '快捷设置', '收起'].map(x => Type.Literal(x)), { description: '仅main支持，会影响主屏。' })) }),
    包名: Type.Optional(Type.String({ description: ios ? '应用 Bundle ID' : 'Android 包名' })),
    ...(ios ? {} : {
    启动组件: Type.Optional(Type.String({ description: '打开应用时可选，使用列出应用返回的完整启动组件；必须属于包名指定的应用。' })), ...(android ? {} : { 读取节点: Type.Optional(Type.Boolean()) }) }),
    关键词: Type.Optional(Type.String({ maxLength: 200, description: android ? '按包名或已取得的应用名称筛选；省略返回全部。' : '按包名子串筛选；省略返回全部。' })),
    包含系统应用: Type.Optional(Type.Boolean({ description: '列出应用：默认true；false只返回非系统应用。' })),
    ...(android || ios ? {} : { 宽度: Type.Optional(Type.Integer()), 高度: Type.Optional(Type.Integer()), 密度: Type.Optional(Type.Integer()) }),
  }, { additionalProperties: false }),
}); }

export const PhoneSchema = phoneSchema(false);
export const AndroidPhoneSchema = phoneSchema(true);
export const desktopPhoneSchema = (capabilities: DeviceCapabilities) => phoneSchema(false, capabilities);

export function validPhoneIntent(intent: unknown): intent is string {
  return typeof intent === 'string' && /\S/u.test(intent) && [...intent].length <= 80;
}

export function isReadOnlyOperation(operation: string): boolean {
  return ['查看', '等待', '列出应用', '列出屏幕'].includes(operation);
}

export function toolFailure(operation: string, message: string, dispatched: '未派发' | '未知' = '未派发', observationFailed = false) {
  const details = { 错误: message, 执行: { 状态: isReadOnlyOperation(operation) ? '无需派发' : dispatched },
    观察: { 状态: observationFailed ? '失败' : '未请求' } };
  return { content: [{ type: 'text' as const, text: JSON.stringify(details) }], details };
}

export const PHONE_INTENT_INSTRUCTIONS = '每次调用phone_action都填写顶层意图，用不超过80字的简短自然语言描述这一步的目标，例如“点击打开”“下滑列表”“搜索天气”。意图只说明准备做什么，不能宣称操作成功；禁止在意图中包含密码、验证码、API Key或令牌。';

export const PHONE_INSTRUCTIONS = `当前后端通过phone_action操作手机；main是主屏，其他名称表示创建的虚拟屏。虚拟屏暂不支持无障碍节点，main可读取节点。应用列表和允许操作标记以最新工具结果为准，保留用户配置的禁止列表。
GUI输入引用目标屏幕最新截图编号和原始图像坐标；同屏顺序执行。会话恢复后旧屏幕、截图与动作编号不能作为新输入依据。执行、观察和通道状态分别报告；结果不确定时先观察，不盲目重放输入。用户STOP和接管优先。
${PHONE_INTENT_INSTRUCTIONS}`;
