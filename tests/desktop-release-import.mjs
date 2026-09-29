// Release acceptance using isolated fixtures. No user configuration is imported.
import { _electron as electron } from '../android/chat-ui/node_modules/playwright/index.mjs';
import { SessionManager } from '@earendil-works/pi-coding-agent';
import { SessionCatalog } from '../pi/android/sessions.mjs';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';

const executable = process.argv[2];
const previousExecutable = process.argv[3];
const data = process.env.BBUI_TEST_DATA;
assert.ok(executable && data, 'Specify release executable and isolated BBUI_TEST_DATA');
const fixtures = path.join(data, '导入源 文件');
await mkdir(fixtures, { recursive: true });
const configFile = path.join(fixtures, '旧配置.json');
await writeFile(configFile, JSON.stringify({ serial: 'legacy-fixture', blocked_packages: ['example.blocked'] }));
const catalog = new SessionCatalog(path.join(fixtures, 'sessions'), fixtures);
const { sessionId } = await catalog.execute({ action: 'create' });
const sessionFile = await catalog.resolve(sessionId);
SessionManager.open(sessionFile).appendMessage({ role: 'user', content: '旧会话保留中文与历史', timestamp: 1 });
await catalog.execute({ action: 'rename', sessionId, title: '导入验收' });
const originals = await Promise.all([configFile, sessionFile].map(file => readFile(file)));
const env = { ...process.env, BBUI_DESKTOP_DATA: data };
delete env.ELECTRON_RUN_AS_NODE;
let previousState;
for (const phase of ['import', 'reopen', ...(previousExecutable ? ['reopen-again'] : [])]) {
  const oldVersion = phase === 'import' && !!previousExecutable;
  const application = await electron.launch({ executablePath: oldVersion ? previousExecutable : executable, args: [], env, timeout: 30000 });
  const mainPid = application.process().pid;
  try {
    const page = await application.firstWindow();
    await page.getByRole('heading', { name: oldVersion ? '开始使用 BBUI' : 'BigBirdUI · 大鸟手机助手' }).waitFor();
    if (!oldVersion) {
      const identity = await application.evaluate(({ app }) => ({ name: app.getName(), data: app.getPath('userData'), cache: app.getPath('sessionData') }));
      assert.deepEqual(identity, { name: '大鸟手机助手', data, cache: data });
      assert.equal(await page.title(), '大鸟手机助手');
    }
    if (phase === 'import') {
      await application.evaluate(({ dialog }, files) => {
        dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [files.shift()] });
      }, [configFile, sessionFile]);
      await page.evaluate(async () => {
        const legacy = await window.Desktop.invoke('importConfig');
        if (legacy.devicePlatform !== 'android') throw new Error('Legacy defaults must remain Android');
        const settings = await window.Desktop.invoke('settings');
        await window.Desktop.invoke('saveSettings', { ...settings, ...legacy,
          connections: [{ id: 'fixture', name: '迁移模拟', provider: 'fixture', api: 'openai-completions',
            baseUrl: 'http://127.0.0.1:9/v1', apiKey: 'RELEASE_FIXTURE_SECRET', models: [{ id: 'fixture', input: ['text'] }] }] });
        await window.Desktop.invoke('importSessions');
      });
    } else {
      const settings = await page.evaluate(() => window.Desktop.invoke('settings'));
      assert.equal(settings.serial, 'legacy-fixture');
      assert.equal(settings.devicePlatform, 'android');
      assert.equal(settings.connections[0].apiKey, ''); // Renderer never receives stored credentials.
      const sealed = JSON.parse(await readFile(path.join(data, 'settings.json'), 'utf8'));
      assert.equal(await application.evaluate(({ safeStorage }, encrypted) => {
        const settings = JSON.parse(safeStorage.decryptString(Buffer.from(encrypted, 'base64')));
        return settings.connections[0].apiKey === 'RELEASE_FIXTURE_SECRET';
      }, sealed.value), true);
      assert.deepEqual(settings.blocked_packages, ['example.blocked']);
      const state = JSON.parse(await readFile(path.join(data, 'state.json')));
      assert.equal(state.selected, previousState.selected);
      assert.equal(state.paused, true);
      assert.equal(state.queue.length, 0);
    }
    const files = await readdir(path.join(data, 'agent/sessions'));
    const imported = files.filter(file => file.startsWith('import-'));
    assert.equal(imported.length, 1);
    assert.deepEqual(await readFile(path.join(data, 'agent/sessions', imported[0])), originals[1]);
    assert.ok(!(await readFile(path.join(data, 'settings.json'), 'utf8')).includes('RELEASE_FIXTURE_SECRET'));
  } finally {
    await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().forEach(window => window.close()));
    await application.close();
  }
  assert.throws(() => process.kill(mainPid, 0), 'Release main process must have exited');
  previousState = JSON.parse(await readFile(path.join(data, 'state.json')));
}
assert.deepEqual(await readFile(configFile), originals[0]);
assert.deepEqual(await readFile(sessionFile), originals[1]);
console.log('External release: legacy import without source changes, encrypted settings, reopen and no replay: PASS');
