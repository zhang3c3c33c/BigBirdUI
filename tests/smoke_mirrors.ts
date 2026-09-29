/** Opt-in real device/window integration. Opens viewers, never injects phone input. */
import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { PhoneBridge, ROOT } from '../pi/bridge.ts';

if (!process.argv.includes('--device')) throw new Error('Pass --device to open real phone mirrors.');
const bridge = new PhoneBridge();
try {
  const connected = await bridge.call('连接手机', { 执行后等待毫秒: 0 });
  assert.equal(connected.details.镜像.状态, '观看中', JSON.stringify(connected.details));
  const again = await bridge.call('连接手机', { 执行后等待毫秒: 0 });
  assert.equal(again.details.镜像.进程号, connected.details.镜像.进程号);
  const created = await bridge.call('创建屏幕', { 屏幕会话: 'mirror-check', 包名: 'com.android.bbkcalculator',
    宽度: 720, 高度: 1280, 密度: 240, 执行后等待毫秒: 3000 });
  assert.equal(created.details.镜像.状态, '观看中', JSON.stringify(created.details));
  assert.notEqual(created.details.镜像.进程号, connected.details.镜像.进程号);
  assert.equal(created.details.镜像.显示屏编号, created.details.显示屏编号);
  assert.ok(created.content.some(item => item.type === 'image'));
  const screens = await bridge.call('列出屏幕', { 执行后等待毫秒: 0 });
  const record = { connected: connected.details, created: created.details, screens: screens.details };
  await writeFile(join(ROOT, 'runs', 'mirrors-smoke.json'), JSON.stringify(record, null, 2));
  console.log(JSON.stringify(record));
  // Let the user see the windows and permit a read-only OS window inspection.
  await new Promise(resolve => setTimeout(resolve, 15000));
  await bridge.call('关闭屏幕', { 屏幕会话: 'mirror-check', 执行后等待毫秒: 0 });
  console.log('Virtual mirror closed; closing MCP should also close the main viewer.');
} finally { await bridge.close(); }
