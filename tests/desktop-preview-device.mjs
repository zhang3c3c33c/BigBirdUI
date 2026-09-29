// Opt-in device benchmark: animates the notification shade, then collapses it.
// Uses isolated desktop settings and never submits a model task.
import { _electron as electron } from '../android/chat-ui/node_modules/playwright/index.mjs';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';

const serial = process.env.BBUI_SMOKE_DEVICE;
if (!serial) throw new Error('Set BBUI_SMOKE_DEVICE explicitly');
const data = await mkdtemp(path.resolve('runs/desktop-preview-device-'));
const executable = process.argv[2];
const root = executable ? path.join(path.dirname(path.resolve(executable)), 'resources/app') : path.resolve('.desktop/app');
const manifest = JSON.parse(await readFile(path.join(root, 'runtime-manifest.json')));
const adb = promisify(execFile);
const shade = action => adb(path.join(root, manifest.paths.adb), ['-s', serial, 'shell', 'cmd', 'statusbar', action], { windowsHide: true });
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const env = { ...process.env, BBUI_DESKTOP_DATA: data }; delete env.ELECTRON_RUN_AS_NODE;
const application = await electron.launch({ executablePath: executable || path.resolve('node_modules/electron/dist/electron.exe'),
  args: executable ? [] : [root], env, timeout: 30000 });
try {
  const page = await application.firstWindow(); page.setDefaultTimeout(40000);
  await page.getByRole('button', { name: '连接手机', exact: true }).waitFor();
  await page.evaluate(async serial => {
    const settings = await window.Desktop.invoke('settings');
    await window.Desktop.invoke('saveSettings', { ...settings, serial,
      connections: [{ id: 'preview-fixture', name: 'Preview fixture', provider: 'bbui', api: 'openai-completions', baseUrl: 'http://127.0.0.1:9/v1', apiKey: 'fixture-only', models: [{ id: 'fixture', input: ['text'] }] }],
      defaultModel: { connectionId: 'preview-fixture', modelId: 'fixture', thinkingLevel: '' },
    });
    await window.Desktop.invoke('connect');
  }, serial);
  await page.getByRole('button', { name: '开始使用', exact: true }).click();
  await page.getByAltText('main 实时画面').waitFor();
  // Let setup and the first video frame finish before measuring.
  await delay(1000);
  await page.evaluate(() => {
    window.previewLoads = [];
    document.querySelector('.phone-video img').addEventListener('load', () => window.previewLoads.push(performance.now()));
    window.previewStarted = performance.now();
  });
  for (let index = 0; index < 12; index++) {
    await shade('expand-notifications'); await delay(350);
    await shade('collapse'); await delay(350);
  }
  const measurement = await page.evaluate(() => {
    const elapsed = performance.now() - window.previewStarted;
    const intervals = window.previewLoads.slice(1).map((time, index) => time - window.previewLoads[index]).sort((a, b) => a - b);
    return { elapsedMs: Math.round(elapsed), displayedFrames: window.previewLoads.length,
      displayedFps: +(window.previewLoads.length * 1000 / elapsed).toFixed(2),
      intervalMedianMs: Math.round(intervals[Math.floor(intervals.length / 2)] || 0),
      intervalP95Ms: Math.round(intervals[Math.floor(intervals.length * .95)] || 0),
      errors: [...document.querySelectorAll('.desktop-error')].map(element => element.textContent) };
  });
  assert.deepEqual(measurement.errors, []);
  assert.ok(measurement.displayedFrames > 0);
  const minimum = Number(process.env.BBUI_PREVIEW_MIN_FPS || 0);
  assert.ok(measurement.displayedFps >= minimum, `Preview ${measurement.displayedFps} FPS < ${minimum}`);
  await writeFile(path.join(data, 'result.json'), JSON.stringify(measurement, null, 2));
  console.log(JSON.stringify({ data, ...measurement }));
} finally {
  await shade('collapse').catch(() => {});
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().forEach(window => window.close())).catch(() => {});
  await application.close();
}
