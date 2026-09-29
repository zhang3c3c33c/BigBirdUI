/** Actual Pi extension -> MCP -> phone. Default is read-only schema discovery. */
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { PhoneBridge, ROOT } from '../pi/bridge.ts';

const bridge = new PhoneBridge();
try {
  const schema = await bridge.listTools();
  assert.equal(schema.tools.length, 1);
  assert.equal(schema.tools[0].name, 'phone_action');
  console.log('Pi bridge: MCP discovery OK');
  if (process.argv.includes('--device')) {
    const created = await Promise.all([
      bridge.call('创建屏幕', { 屏幕会话: 'calc-test', 包名: 'com.android.bbkcalculator', 宽度: 720, 高度: 1280, 密度: 240, 执行后等待毫秒: 3000 }),
      bridge.call('创建屏幕', { 屏幕会话: 'settings-test', 包名: 'com.android.settings', 宽度: 720, 高度: 1280, 密度: 240, 执行后等待毫秒: 3000 }),
    ]);
    for (const result of created) {
      assert.ok(result.content.some(x => x.type === 'image' && x.data.length > 1000));
      assert.equal(result.details.状态, '屏幕已创建');
    }
    assert.notEqual(created[0].details.显示屏编号, created[1].details.显示屏编号);
    const start = performance.now();
    const observed = await Promise.all(created.map(result => bridge.call('查看', {
      屏幕会话: result.details.屏幕会话, 执行后等待毫秒: 3000,
    })));
    const elapsed = performance.now() - start;
    assert.ok(elapsed < 5900, `Two 3-second waits serialized: ${elapsed}ms`);
    assert.equal(observed[0].details.屏幕.package, 'com.android.bbkcalculator');
    assert.equal(observed[1].details.屏幕.package, 'com.android.settings');
    const actionStart = performance.now();
    const [clicked, searchBox] = await Promise.all([
      bridge.call('点击', {
        屏幕会话: 'calc-test', 位置: [100, 875], 截图编号: observed[0].details.截图编号, 执行后等待毫秒: 3000,
      }),
      bridge.call('点击', {
        屏幕会话: 'settings-test', 位置: [300, 105], 截图编号: observed[1].details.截图编号, 执行后等待毫秒: 3000,
      }),
    ]);
    const actionElapsed = performance.now() - actionStart;
    assert.equal(clicked.details.状态, '已执行');
    assert.equal(searchBox.details.状态, '已执行');
    assert.ok(actionElapsed < 5900, `Parallel actions serialized: ${actionElapsed}ms`);
    const typed = await bridge.call('输入内容', {
      屏幕会话: 'settings-test', 内容: '蓝牙', 截图编号: searchBox.details.截图编号, 执行后等待毫秒: 2000,
    });
    assert.equal(typed.details.状态, '已执行');
    // Wrong-screen observation must never be accepted for input.
    await assert.rejects(bridge.call('点击', {
      屏幕会话: 'calc-test', 位置: [100, 875], 截图编号: observed[1].details.截图编号, 执行后等待毫秒: 0,
    }), /截图编号/);
    const main = await bridge.call('查看', { 屏幕会话: 'main', 执行后等待毫秒: 0 });
    const record = { parallelWaitElapsedMs: Math.round(elapsed), parallelActionElapsedMs: Math.round(actionElapsed), screens: observed.map(x => x.details), calculatorClick: clicked.details, chineseInput: typed.details, main: main.details };
    await mkdir(join(ROOT, 'runs'), { recursive: true });
    await writeFile(join(ROOT, 'runs', 'multiscreen-smoke.json'), JSON.stringify(record, null, 2));
    console.log(JSON.stringify(record, null, 2));
    for (const name of ['calc-test', 'settings-test']) await bridge.call('关闭屏幕', { 屏幕会话: name, 执行后等待毫秒: 0 });
  }
} finally { await bridge.close(); }
