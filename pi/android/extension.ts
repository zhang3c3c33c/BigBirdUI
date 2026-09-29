import { applyPhonePrompt, registerPhoneSkill } from '../phone-skill.js';
import { registerTaskState, TASK_INSTRUCTIONS } from '../task-state.js';
import type { ExtensionAPI } from '@earendil-works/pi-coding-agent';
import { createHash } from 'node:crypto';
import { AndroidPhoneSchema, PHONE_INTENT_INSTRUCTIONS, toolFailure, validPhoneIntent } from '../phone-contract.js';
import { AndroidPhoneBridge } from './bridge.js';
import { registerScreenshotContext } from './screenshot-context.js';

export const ANDROID_PHONE_INSTRUCTIONS = `当前后端为Android本机，仅支持单个virtual虚拟屏，不操作main主屏；无无障碍节点。虚拟屏创建、关闭与故障恢复由宿主管理。应用枚举是当前Android用户可取得的启动入口，不保证完整枚举分身。
${PHONE_INTENT_INSTRUCTIONS}`;

/** Host facts describe this execution environment, never authorize replay of prior input. */
export function phoneEnvironmentInstructions(env: NodeJS.ProcessEnv): string {
  const id = env.BBUI_ENVIRONMENT_ID ?? '';
  if (!/^[A-Za-z0-9_-]{1,160}$/.test(id)) return '';
  const recreated = env.BBUI_ENVIRONMENT_REBUILT === 'true';
  const previous = env.BBUI_PREVIOUS_ENVIRONMENT_ID ?? '';
  return `当前手机执行环境编号：${id}。${recreated
    ? (previous ? '虚拟屏已重建，之前的应用页面不保证保留。' : '虚拟屏为本次新建。')
    : '沿用现有虚拟屏，画面可能已被用户或其他会话改变。'}需要GUI操作时先调用查看取得当前画面，历史截图和坐标不能作为本轮输入依据，禁止重放历史动作。`;
}

export default function androidPhoneExtension(pi: ExtensionAPI) {
  registerTaskState(pi);
  registerPhoneSkill(pi);
  registerScreenshotContext(pi);
  const bridge = new AndroidPhoneBridge(process.env.BBUI_BRIDGE_URL ?? '', process.env.BBUI_BRIDGE_TOKEN ?? '');
  pi.on('before_agent_start', event => {
    applyPhonePrompt(event.systemPromptOptions, ANDROID_PHONE_INSTRUCTIONS, TASK_INSTRUCTIONS, phoneEnvironmentInstructions(process.env));
  });
  pi.on('session_shutdown', async () => { await bridge.setStopped(true).catch(() => {}); });
  pi.registerTool({
    name: 'phone_action', label: '手机操作',
    description: '在Android本机单个虚拟屏幕查看和操作应用，按操作返回执行事实、画面或查询信息。屏幕会话默认为virtual。',
    parameters: AndroidPhoneSchema,
    executionMode: 'sequential',
    async execute(callId, { 操作, 参数, 意图 }, signal, _onUpdate, ctx) {
      if (!validPhoneIntent(意图)) return toolFailure(操作, '意图为必填字段：请填写1～80字且非空白的操作目标。本次未派发。');
      if (!['列出应用', '列出屏幕'].includes(操作) && !ctx.model?.input.includes('image')) {
        return toolFailure(操作, '当前模型不支持图片输入，请选择视觉模型');
      }
      if (参数.屏幕会话 && 参数.屏幕会话 !== 'virtual') return toolFailure(操作, 'Android仅支持virtual屏幕');
      return bridge.call(操作, { 执行后等待毫秒: 0, ...参数, 屏幕会话: 'virtual',
        动作编号: createHash('sha256').update(callId).digest('hex') }, signal);
    },
  });
}
