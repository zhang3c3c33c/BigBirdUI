export function semanticKey(key: string, primary: boolean) {
  if (primary && key.toLowerCase() === 'a') return { operation: '全选', params: {} };
  const names: Record<string, string> = { Backspace: '删除', Delete: '向前删除', Enter: '回车', Escape: '返回', Home: '主页', ArrowLeft: '左', ArrowRight: '右', ArrowUp: '上', ArrowDown: '下' };
  return names[key] ? { operation: '按键', params: { 键名: names[key] } } : null;
}
