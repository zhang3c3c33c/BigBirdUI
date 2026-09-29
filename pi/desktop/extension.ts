import { applyPhonePrompt, registerPhoneSkill } from '../phone-skill.js';
import type { ExtensionAPI } from '@earendil-works/pi-coding-agent';
import { createHash } from 'node:crypto';
import { desktopCapabilities, desktopPhoneSchema, PHONE_INSTRUCTIONS, PHONE_INTENT_INSTRUCTIONS, validPhoneIntent, toolFailure } from '../phone-contract.js';
import { registerTaskState, TASK_INSTRUCTIONS } from '../task-state.js';
import { registerScreenshotContext } from '../android/screenshot-context.js';

export default function desktopPhone(pi: ExtensionAPI) {
  const capabilities = desktopCapabilities(), ios = capabilities.devicePlatform === 'ios';
  const instructions = ios ? `当前手机是 iPhone，仅支持 main 真实主屏，输入仅支持已验收的竖屏方向。没有虚拟屏、无障碍节点、通用返回键或全局应用禁止策略。只使用当前工具列出的操作，Bundle ID 从应用列表取得。system_apps.launch 同样是GUI输入：先查看 main，再提供 screen="main" 和 observationId=最新截图编号。文件工具仅访问 AFC 暴露的手机媒体目录。GUI输入必须引用本屏最新截图编号和原始坐标，逐步执行。会话恢复后先重新观察，不重放旧输入；执行、观察、通道状态分别报告，用户STOP和接管优先。${PHONE_INTENT_INSTRUCTIONS}` : PHONE_INSTRUCTIONS;
  registerTaskState(pi);
  registerPhoneSkill(pi);
  registerScreenshotContext(pi);
  pi.on('before_agent_start', event => { applyPhonePrompt(event.systemPromptOptions, instructions, TASK_INSTRUCTIONS, '当前手机执行环境由桌面宿主持有；会话切换后GUI输入必须重新观察。'); });
  pi.registerTool({ name: 'phone_action', label: '手机操作', parameters: desktopPhoneSchema(capabilities),
    description: ios ? '通过 USB 操作 iPhone main 主屏。执行与观察分别报告。' : '通过桌面宿主操作Android手机，支持main主屏及多个虚拟屏。不同屏幕可以并行，执行与观察分别报告。',
    executionMode: 'parallel',
    async execute(callId, { 操作, 参数, 意图 }, signal, _update, ctx) {
      if (!validPhoneIntent(意图)) return toolFailure(操作, '请提供1～80字操作意图');
      if (!['列出应用', '列出屏幕', '关闭屏幕'].includes(操作) && !ctx.model?.input.includes('image')) return toolFailure(操作, '请选择支持图片的模型');
      signal?.throwIfAborted();
      const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${process.env.BBUI_BRIDGE_TOKEN}` };
      const stop = () => { void fetch(`${process.env.BBUI_BRIDGE_URL}/cancel`, { method: 'POST', headers, body: '{}' }).catch(() => {}); };
      signal?.addEventListener('abort', stop, { once: true });
      try {
        const response = await fetch(`${process.env.BBUI_BRIDGE_URL}/phone`, { method: 'POST', headers,
          body: JSON.stringify({ operation: 操作, params: { ...参数, 动作编号: createHash('sha256').update(callId).digest('hex') } }), signal: AbortSignal.timeout(125000) });
        if (!response.ok) throw new Error(`宿主拒绝请求 (${response.status})`);
        return await response.json();
      } catch {
        void fetch(`${process.env.BBUI_BRIDGE_URL}/stop`, { method: 'POST', headers, body: '{}' }).catch(() => {});
        return toolFailure(操作, '未取得宿主结果；请检查当前状态，禁止重放操作。', '未知');
      } finally { signal?.removeEventListener('abort', stop); }
    },
  });
}
