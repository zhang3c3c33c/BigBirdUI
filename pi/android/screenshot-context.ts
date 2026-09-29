import type { ExtensionAPI } from '@earendil-works/pi-coding-agent';
import { Type } from 'typebox';

export const SCREENSHOT_BYTES = 12 * 1024 * 1024;
export const RECENT_SCREENSHOTS = 3;
type Block = { type: string; data?: string; mimeType?: string; text?: string };
type Message = { role: string; toolName?: string; toolCallId?: string; content?: unknown; details?: unknown };
const isScreen = (m: Message) => m.role === 'toolResult' && ['phone_action', 'phone_history'].includes(m.toolName ?? '');

/** A request-only projection. Never modify Pi's canonical transcript or receipts. */
export function projectScreenshots<T extends Message>(messages: T[], maxImages = RECENT_SCREENSHOTS, maxBytes = SCREENSHOT_BYTES): T[] {
  const images: { message: T; block: Block; index: number }[] = [];
  for (const message of messages) {
    if (!isScreen(message) || !Array.isArray(message.content)) continue;
    message.content.forEach((block: Block, index: number) => {
      if (block.type === 'image') images.push({ message, block, index });
    });
  }
  // Historical recall must not displace the latest actual observation.
  const newestFirst = [...images].reverse();
  const screens = new Set<string>();
  const latest = new Set(newestFirst.filter(image => {
    if (image.message.toolName !== 'phone_action') return false;
    let details = image.message.details as Record<string, unknown> | undefined;
    if (!details && Array.isArray(image.message.content)) {
      try { details = JSON.parse(image.message.content.find((b: Block) => b.type === 'text')?.text || '{}'); } catch {}
    }
    const screen = String(details?.['屏幕会话'] ?? details?.['显示屏编号'] ?? 'default');
    if (screens.has(screen)) return false;
    screens.add(screen); return true;
  }));
  const keep = new Set<Block>();
  let bytes = 0;
  for (const image of [...latest, ...newestFirst]) {
    if (!image || keep.has(image.block)) continue;
    const size = Buffer.byteLength(image.block.data ?? '', 'utf8');
    if (latest.has(image) || (keep.size < Math.max(maxImages, latest.size) && bytes + size <= maxBytes)) {
      keep.add(image.block); bytes += size;
    }
  }
  return messages.map(message => {
    if (!isScreen(message) || !Array.isArray(message.content)) return message;
    return { ...message, content: message.content.map((block: Block, index: number) => {
      if (block.type !== 'image' || keep.has(block)) return block;
      return { type: 'text', text: message.toolName === 'phone_action'
        ? `[历史截图已归档：phone_history 可读取 toolCallId=${message.toolCallId}、imageIndex=${index}。动作结果文字仍保留。旧画面不能用于当前操作，操作前先查看。]`
        : '[历史截图回读已归档；原始 phone_action 截图仍可按原编号读取。]' };
    }) };
  });
}

export function registerScreenshotContext(pi: ExtensionAPI) {
  pi.on('context', event => ({ messages: projectScreenshots(event.messages) }));
  pi.registerTool({
    name: 'phone_history', label: '回看历史画面',
    description: '只读回看当前会话分支的历史截图。按归档提示的编号读取，不接触手机，不产生新的可操作截图编号。需要当前画面时使用 phone_action 查看。',
    parameters: Type.Object({ toolCallId: Type.String({ minLength: 1 }), imageIndex: Type.Integer({ minimum: 0 }) }),
    executionMode: 'sequential',
    async execute(_id, params, signal, _update, ctx) {
      if (signal?.aborted) throw new Error('历史图片读取已取消');
      if (!ctx.model?.input.includes('image')) throw new Error('当前模型不支持图片');
      const entry = [...ctx.sessionManager.getBranch()].reverse().find(entry => entry.type === 'message'
        && entry.message.role === 'toolResult' && entry.message.toolName === 'phone_action'
        && entry.message.toolCallId === params.toolCallId);
      if (!entry || entry.type !== 'message' || entry.message.role !== 'toolResult') throw new Error('当前分支中没有这张历史截图');
      const block = entry.message.content[params.imageIndex];
      if (block?.type !== 'image') throw new Error('指定位置不是截图');
      return { content: [{ type: 'text' as const, text: '这是历史画面，不代表当前手机状态。操作前必须重新查看；禁止重放历史动作。' }, { ...block }],
        details: { historical: true, sourceToolCallId: params.toolCallId, imageIndex: params.imageIndex } };
    },
  });
}
