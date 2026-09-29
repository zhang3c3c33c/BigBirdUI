import { applyPhonePrompt, registerPhoneSkill } from './phone-skill.ts';
import { registerTaskState, TASK_INSTRUCTIONS } from './task-state.ts';
import type { ExtensionAPI } from '@earendil-works/pi-coding-agent';
import { PhoneSchema, PHONE_INSTRUCTIONS, toolFailure, validPhoneIntent } from './phone-contract.ts';
export { PhoneSchema, PHONE_INSTRUCTIONS } from './phone-contract.ts';
import { createHash } from 'node:crypto';
import { PhoneBridge } from './bridge.ts';

export default function phoneExtension(pi: ExtensionAPI) {
  registerTaskState(pi);
  registerPhoneSkill(pi);
  const bridge = new PhoneBridge();
  pi.on('before_agent_start', event => {
    applyPhonePrompt(event.systemPromptOptions, PHONE_INSTRUCTIONS, TASK_INSTRUCTIONS);
  });
  pi.on('session_shutdown', async () => { await bridge.close(); });
  pi.registerCommand('phone-connect', {
    description: '连接已配置的手机，并打开只读主屏镜像',
    handler: async (_args, ctx) => {
      try {
        const { details } = await bridge.call('连接手机', { 执行后等待毫秒: 0 });
        if (details.镜像?.状态 === '观看中') {
          ctx.ui.notify(`手机 ${details.设备序列号} 已连接，主屏只读镜像已打开。新建虚拟屏幕会自动打开只读镜像。`, 'info');
        } else {
          ctx.ui.notify(`手机已连接，但镜像未打开：${details.镜像?.错误 ?? '未知错误'}`, 'error');
        }
      } catch (error) { ctx.ui.notify(`连接失败：${String(error)}`, 'error'); }
    },
  });
  pi.registerTool({
    name: 'phone_action', label: '手机操作',
    description: '按屏幕会话操作Android手机并返回截图。支持列出应用（已安装包、系统标记、操作权限、启动入口，无截图）、原子动作与虚拟屏幕创建/列出/关闭。main主屏；虚拟屏幕内可依次打开不同应用；跨屏幕可并行。',
    parameters: PhoneSchema,
    executionMode: 'parallel',
    async execute(callId, { 操作, 参数, 意图 }, signal, _onUpdate, ctx) {
      if (!validPhoneIntent(意图)) return toolFailure(操作, '意图为必填字段：请填写1～80字且非空白的操作目标。本次未派发。');
      if (!['列出应用', '列出屏幕', '关闭屏幕'].includes(操作) && !ctx.model?.input.includes('image')) {
        return toolFailure(操作, '当前Pi模型未声明支持图像输入。请用/model选择视觉模型，BBUI不修改模型供应商。');
      }
      return bridge.call(操作, { 执行后等待毫秒: 0, ...参数, 动作编号: createHash('sha256').update(callId).digest('hex') }, signal);
    },
  });
  pi.registerCommand('phone-stop', {
    description: '停止手机新输入（包括其他屏幕），保留观察能力',
    handler: async (_args, ctx) => { await bridge.setStopped(true); ctx.ui.notify('手机新输入已停止', 'info'); },
  });
  pi.registerCommand('phone-resume', {
    description: '用户明确恢复手机输入',
    handler: async (_args, ctx) => { await bridge.setStopped(false); ctx.ui.notify('用户STOP已解除；若仍存在输入故障隔离，请使用 /phone-recover。继续前先重新观察。', 'info'); },
  });
  pi.registerCommand('phone-recover', {
    description: '显式恢复故障连接：确认旧进程退出，只读查询，保留用户STOP且不重放输入',
    handler: async (_args, ctx) => {
      try {
        const result = await bridge.recover();
        ctx.ui.notify(JSON.stringify(result, (key, value) => key === 'content' ? undefined : value), 'info');
      } catch (error) { ctx.ui.notify(`连接未恢复：${String(error)}`, 'error'); }
    },
  });
  pi.registerCommand('phone-screens', {
    description: '列出本Pi会话管理的手机屏幕',
    handler: async (_args, ctx) => {
      const result = await bridge.call('列出屏幕', { 执行后等待毫秒: 0 });
      ctx.ui.notify(JSON.stringify(result.details), 'info');
    },
  });
}
