import { _electron as electron } from '../android/chat-ui/node_modules/playwright/index.mjs';
import path from 'node:path';
import { mkdtemp, readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const data = process.env.BBUI_TEST_DATA || await mkdtemp(path.resolve('runs/desktop-window-'));
const env = { ...process.env, BBUI_DESKTOP_DATA: data }; delete env.ELECTRON_RUN_AS_NODE;
const application = await electron.launch({ executablePath: process.argv[2] || path.resolve('node_modules/electron/dist/electron.exe'), args: process.argv[2] ? [] : [path.resolve('.desktop/app')], env, timeout: 30000 });
const mainPid = await application.evaluate(() => process.pid);
application.process().stderr.on('data', data => console.error(String(data)));
application.on('console', message => console.log('Electron:', message.text()));
console.log('Launched', data);
try {
  const page = await application.firstWindow();
  page.setDefaultTimeout(35000);
  page.on('pageerror', error => console.error(error.message));
  await page.getByRole('heading', { name: '开始使用 BBUI' }).waitFor();
  assert.equal(await page.locator('.chat-shell,.phone-pane').count(), 0);
  assert.equal(await page.getByRole('button', { name: '开始使用', exact: true }).isDisabled(), true);
  await page.screenshot({ path: path.join(data, 'window.png') });
  await page.getByRole('button', { name: '添加模型', exact: true }).click();
  await page.getByRole('dialog', { name: '设置' }).waitFor();
  await page.getByRole('button', { name: '模型', exact: true }).click();
  await page.getByRole('button', { name: '添加连接', exact: true }).click();
  await page.getByLabel('名称', { exact: true }).fill('本地模拟');
  await page.getByLabel('API 密钥', { exact: true }).fill('DESKTOP_TEST_KEY');
  await page.getByLabel('模型 ID', { exact: true }).fill('mock');
  await page.getByRole('button', { name: '设为默认' }).click();
  await page.getByRole('button', { name: '保存设置' }).click();
  await page.getByRole('status').filter({ hasText: '已完成' }).waitFor();
  assert.ok(!(await readFile(path.join(data, 'settings.json'), 'utf8')).includes('DESKTOP_TEST_KEY'));
  await page.screenshot({ path: path.join(data, 'settings.png') });
  console.log('Electron sandboxed welcome, settings and safeStorage: PASS');
} finally {
  // Crash path; normal close is verified without an attached inspector separately.
  process.kill(mainPid, 'SIGKILL');
  await new Promise(resolve => application.once('close', resolve));
}
