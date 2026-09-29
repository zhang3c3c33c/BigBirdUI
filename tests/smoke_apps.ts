/** Read-only live Pi bridge -> MCP -> Android package inventory. */
import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { PhoneBridge, ROOT } from '../pi/bridge.ts';

if (!process.argv.includes('--device')) throw new Error('Pass --device to query installed packages.');
const bridge = new PhoneBridge();
try {
  const full = await bridge.call('列出应用', { 执行后等待毫秒: 0 });
  assert.equal(full.details.状态, '应用已列出');
  assert.equal(full.details.返回数量, full.details.已安装总数);
  assert.ok(full.details.已安装总数 > 0);
  assert.ok(full.content.every(item => item.type === 'text'));
  assert.ok(full.details.应用列表.some((app: any) => app.系统应用));
  assert.ok(full.details.应用列表.every((app: any) => app.允许操作 === !full.details.应用策略.禁止包名.includes(app.包名)));
  const filtered = await bridge.call('列出应用', { 执行后等待毫秒: 0, 关键词: 'tv.danmaku.bili', 包含系统应用: false });
  assert.equal(filtered.details.返回数量, 1);
  const bili = filtered.details.应用列表[0];
  assert.equal(bili.包名, 'tv.danmaku.bili');
  assert.equal(bili.允许操作, true);
  assert.equal(bili.可启动, true);
  assert.ok(bili.启动入口.every((entry: string) => entry.startsWith('tv.danmaku.bili/')));
  const screens = await bridge.call('列出屏幕', { 执行后等待毫秒: 0 });
  const meituan = await bridge.call('列出应用', { 执行后等待毫秒: 0, 关键词: 'com.sankuai.meituan' });
  for (const packageName of ['com.sankuai.meituan', 'com.sankuai.meituan.takeoutnew']) {
    const app = meituan.details.应用列表.find((item: any) => item.包名 === packageName);
    assert.ok(app, `Missing installed app ${packageName}`);
    assert.equal(app.允许操作, !meituan.details.应用策略.禁止包名.includes(packageName));
  }
  assert.deepEqual(screens.details.屏幕会话列表, []);
  await writeFile(join(ROOT, 'runs', 'apps-smoke.json'), JSON.stringify({ full: full.details, filtered: filtered.details }, null, 2));
  console.log(JSON.stringify({ total: full.details.已安装总数, user: full.details.用户编号,
    elapsedMs: full.details.查询耗时毫秒, filtered: bili, meituan: meituan.details.应用列表, screenCount: 0 }));
} finally { await bridge.close(); }
