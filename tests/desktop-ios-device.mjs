// Opt-in real iPhone acceptance. Uses isolated settings, a local model fixture,
// and the already-installed MultiTouch Visualizer. Never installs phone apps.
import { _electron as electron } from '../android/chat-ui/node_modules/playwright/index.mjs';
import { createServer } from 'node:http';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { startPreviewDiagnostics, readPreviewDiagnostics } from './preview-diagnostics.mjs';

const serial = process.env.BBUI_SMOKE_DEVICE;
if (!serial) throw new Error('Set BBUI_SMOKE_DEVICE explicitly');
const seconds = Number(process.env.BBUI_IOS_BENCHMARK_SECONDS || 300);
const settingsScene = process.env.BBUI_IOS_SCENE === 'settings';
const fixtureApp = settingsScene ? 'com.apple.Preferences' : 'com.Goodix.MultiTouch-Visualizer';
const data = await mkdtemp(path.resolve('runs/desktop-ios-device-'));
let toolStep = 0;
const requests = [];
const api = createServer(async (req, res) => {
  try {
    let body = ''; for await (const chunk of req) body += chunk;
    const request = JSON.parse(body); requests.push(request);
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    const send = (delta, finish_reason = null) => res.write('data: ' + JSON.stringify({ id: 'ios-fixture', object: 'chat.completion.chunk', created: 1,
      model: request.model, choices: [{ index: 0, delta, finish_reason }] }) + '\n\n');
    send({ role: 'assistant' });
    if (!request.tools?.length) send({ content: 'iPhone USB 预览验收' });
    else if (toolStep < 2) {
      const observe = toolStep++ === 0;
      const previous = request.messages.filter(message => message.role === 'tool').at(-1);
      const text = typeof previous?.content === 'string' ? previous.content : JSON.stringify(previous?.content);
      const observationId = text?.match(/截图编号["\\]*\s*:\s*["\\]*([a-f0-9]{32})/)?.[1];
      if (!observe) assert.ok(observationId, 'Observation must reach the model');
      send({ tool_calls: [{ index: 0, id: observe ? 'observe-fixture' : 'launch-fixture', type: 'function', function: {
        name: observe ? 'phone_action' : 'system_apps', arguments: JSON.stringify(observe
          ? { 操作: '查看', 参数: { 屏幕会话: 'main' }, 意图: '观察当前手机' }
          : { operation: 'launch', params: { packageName: fixtureApp, screen: 'main', observationId }, intent: '打开验收页面' }),
      } }] });
      send({}, 'tool_calls'); res.end('data: [DONE]\n\n'); return;
    } else send({ content: '测试应用启动步骤已返回，请进行预览验收。' });
    send({}, 'stop'); res.end('data: [DONE]\n\n');
  } catch (error) { res.writeHead(500).end(JSON.stringify({ error: error.message })); }
});
await new Promise(resolve => api.listen(0, '127.0.0.1', resolve));
const env = { ...process.env, BBUI_DESKTOP_DATA: data }; delete env.ELECTRON_RUN_AS_NODE;
const executable = process.argv[2];
const application = await electron.launch({ executablePath: executable || path.resolve('node_modules/electron/dist/electron.exe'),
  args: executable ? [] : [path.resolve('.desktop/app')], env, timeout: 30000 });
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
let page, manual = false;
const partial = {};
try {
  page = await application.firstWindow(); page.setDefaultTimeout(60000);
  await application.evaluate(({ BrowserWindow }) => {
    const window = BrowserWindow.getAllWindows()[0];
    window.restore(); window.showInactive();
    window.acceptanceVisibility = [];
    for (const event of ['minimize', 'restore', 'hide', 'show']) window.on(event, () => window.acceptanceVisibility.push({ event, at: Date.now() }));
  });
  await page.getByRole('heading', { name: '开始使用 BBUI' }).waitFor();
  await page.evaluate(async ({ serial, baseUrl }) => {
    window.addEventListener('bbui-message', event => { if (event.detail?.type === 'snapshot') window.iosSnapshot = event.detail; });
    window.addEventListener('bbui-preview-metric', event => { if (event.detail?.kind === 'commit') window.iosFrameId = event.detail.frameId; });
    const settings = await window.Desktop.invoke('settings');
    await window.Desktop.invoke('saveSettings', { ...settings, serial, devicePlatform: 'ios',
      connections: [{ id: 'fixture', name: 'Local acceptance', provider: 'fixture', api: 'openai-completions', baseUrl, apiKey: 'local-only', models: [{ id: 'fixture', input: ['text', 'image'] }] }],
      defaultModel: { connectionId: 'fixture', modelId: 'fixture', thinkingLevel: '' } });
    await window.Desktop.invoke('connect');
  }, { serial, baseUrl: `http://127.0.0.1:${api.address().port}/v1` });
  await page.getByRole('button', { name: '开始使用', exact: true }).click();
  await page.locator('.phone-video img').waitFor();
  await page.evaluate(() => window.BBUI.postMessage(JSON.stringify({ type: 'send', sessionId: window.iosSnapshot.sessionId, submissionId: crypto.randomUUID(), text: '打开已有触摸测试应用，准备 USB 预览验收。' })));
  await page.waitForFunction(() => window.iosSnapshot?.isRunning === true);
  await page.waitForFunction(() => !window.iosSnapshot?.isRunning, null, { timeout: 90000 });
  await delay(500);
  await page.screenshot({ path: path.join(data, 'before-manual.png') });
  await writeFile(path.join(data, 'model-requests.json'), JSON.stringify(requests, null, 2));
  const modelRequest = requests.find(request => request.tools?.length);
  assert.ok(modelRequest, 'Model fixture must be called');
  const names = modelRequest.tools.map(tool => tool.function.name);
  assert.ok(names.includes('system_apps') && names.includes('phone_action'));
  for (const name of ['system_notifications', 'system_calendar', 'system_contacts', 'system_sms', 'system_call_log', 'system_media', 'system_clock']) assert.ok(!names.includes(name), name);
  const phoneSchema = modelRequest.tools.find(tool => tool.function.name === 'phone_action').function.parameters;
  assert.ok(!JSON.stringify(phoneSchema).includes('创建屏幕'));
  await page.locator('.phone-pane').getByRole('button', { name: '我来操作', exact: true }).click();
  await page.waitForFunction(() => window.iosSnapshot?.control?.mode === 'manual'); manual = true;
  await delay(150);
  const input = async (operation, params) => page.evaluate(async ({ operation, params }) => {
    const frameId = window.iosFrameId || (await window.Desktop.invoke('frame', { screen: 'main' })).frameId;
    const result = await window.Desktop.invoke('input', { operation, params, screen: 'main', frameId,
      controlId: window.iosSnapshot.control.id, actionId: crypto.randomUUID() });
    if (result.错误) throw new Error(result.错误);
    return result;
  }, { operation, params });
  // The existing test app's X-Y mode shows touches only while held.
  await input('点击', { 位置: settingsScene ? [680, 90] : [180, 44] });
  await page.evaluate(() => {
    window.iosFrames = []; window.iosStarted = performance.now();
    window.iosPhase = 'static';
    document.querySelector('.phone-video img').addEventListener('load', () => {
      window.iosFrames.push({ at: performance.now(), phase: window.iosPhase });
    });
  });
  await startPreviewDiagnostics(page);
  const metrics = []; const start = Date.now(); let gestureIndex = 0;
  while (Date.now() - start < seconds * 1000) {
    await application.evaluate(({ BrowserWindow }) => {
      const window = BrowserWindow.getAllWindows()[0];
      if (window.isMinimized()) window.restore();
      if (!window.isVisible()) window.showInactive();
    });
    const elapsed = (Date.now() - start) / 1000;
    const phase = elapsed < Math.min(20, seconds / 5) ? 'static' : 'touch';
    await page.evaluate(phase => { window.iosPhase = phase; }, phase);
    if (phase === 'touch') {
      const reverse = gestureIndex++ % 2;
      await input('滑动', settingsScene ? { 起点: [375, reverse ? 450 : 1050], 终点: [375, reverse ? 1050 : 450], 时间: 700 }
        : { 起点: [230, 500], 终点: [510, 850], 时间: 700 });
    }
    else await delay(700);
    if (!metrics.length || elapsed - metrics.at(-1).at >= 10) metrics.push({ at: elapsed,
      processes: await application.evaluate(({ app }) => app.getAppMetrics().map(value => ({ type: value.type, cpu: value.cpu.percentCPUUsage, memory: value.memory.workingSetSize }))),
      processTree: JSON.parse(execFileSync(path.resolve('.venv/Scripts/python.exe'), ['-c',
        'import psutil,json,sys;root=psutil.Process(int(sys.argv[1]));print(json.dumps([{ "pid":p.pid,"name":p.name(),"cpuSeconds":sum(p.cpu_times()[:2]),"rssBytes":p.memory_info().rss} for p in [root]+root.children(recursive=True) if p.is_running()]))',
        String(application.process().pid)], { encoding: 'utf8', windowsHide: true })) });
  }
  const measurement = await page.evaluate(() => {
    const now = performance.now(), phases = {};
    for (const phase of ['static', 'touch']) {
      const samples = window.iosFrames.filter(frame => frame.phase === phase).map(frame => frame.at);
      const gaps = samples.slice(1).map((at, i) => at - samples[i]).sort((a, b) => a - b);
      const elapsed = samples.length > 1 ? samples.at(-1) - samples[0] : 0;
      phases[phase] = { frames: samples.length, elapsedMs: elapsed, fps: elapsed ? (samples.length - 1) * 1000 / elapsed : 0,
        p95FrameIntervalMs: gaps[Math.floor(gaps.length * .95)] || 0, maxFrameIntervalMs: gaps.at(-1) || 0 };
    }
    return { elapsedMs: now - window.iosStarted, phases,
      errors: [...document.querySelectorAll('.desktop-error')].map(element => element.textContent) };
  });
  const diagnostics = await readPreviewDiagnostics(page);
  const visibilityEvents = await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].acceptanceVisibility);
  Object.assign(partial, { data, scene: settingsScene ? 'settings-scroll' : 'touch-visualizer', measurement, diagnostics, metrics, visibilityEvents });
  await writeFile(path.join(data, 'performance.json'), JSON.stringify(partial, null, 2));
  await page.evaluate(() => window.previewDiagnostics.stop());
  if (!settingsScene) { await input('点击', { 位置: [80, 44] }); await input('点击', { 位置: [290, 44] }); }
  // Measure from a real renderer pointer-up to changed phone pixels decoded in
  // that renderer, rather than reporting the HID socket-write duration.
  const visibleLatency = [];
  for (let index = 0; index < (settingsScene ? 0 : 8); index++) {
    await application.evaluate(({ BrowserWindow }) => { const window = BrowserWindow.getAllWindows()[0]; window.restore(); window.showInactive(); });
    await input('点击', { 位置: [290, 44] }); await delay(250);
    const x = 180, y = 420 + index * 20;
    await page.evaluate(({ x, y }) => {
      const img = document.querySelector('.phone-video img');
      const canvas = document.createElement('canvas');
      canvas.width = img.naturalWidth; canvas.height = img.naturalHeight;
      const context = canvas.getContext('2d', { willReadFrequently: true });
      const sx = x / 750 * canvas.width, sy = y / 1334 * canvas.height;
      const sample = () => {
        context.drawImage(img, 0, 0); return context.getImageData(Math.round(sx), Math.round(sy - 12), 100, 25).data;
      };
      const baseline = sample();
      window.visibleLatency = { started: null, ms: null };
      const listener = () => {
        if (window.visibleLatency.started === null) return;
        const pixels = sample(); let difference = 0;
        for (let i = 0; i < pixels.length; i++) difference += Math.abs(pixels[i] - baseline[i]);
        if (difference / pixels.length > .5) {
          window.visibleLatency.ms = performance.now() - window.visibleLatency.started;
          img.removeEventListener('load', listener);
        }
      };
      img.addEventListener('load', listener);
    }, { x, y });
    const box = await page.locator('.phone-video img').boundingBox();
    await page.mouse.move(box.x + x / 750 * box.width, box.y + y / 1334 * box.height);
    await page.mouse.down();
    await page.mouse.move(box.x + 480 / 750 * box.width, box.y + y / 1334 * box.height, { steps: 5 });
    await page.evaluate(() => { window.visibleLatency.started = performance.now(); });
    await page.mouse.up();
    await page.waitForFunction(() => window.visibleLatency.ms !== null, null, { timeout: 5000 });
    visibleLatency.push(await page.evaluate(() => window.visibleLatency.ms));
    partial.visibleLatencyMs = visibleLatency;
    await writeFile(path.join(data, 'performance.json'), JSON.stringify(partial, null, 2));
    await delay(500);
  }
  if (!settingsScene) await input('点击', { 位置: [290, 44] });
  await writeFile(path.join(data, 'performance.json'), JSON.stringify({ ...partial, visibleLatencyMs: visibleLatency }, null, 2));
  await startPreviewDiagnostics(page);
  await page.getByRole('button', { name: '手机画面', exact: true }).click();
  await delay(300); const collapsedBefore = await readPreviewDiagnostics(page);
  await delay(1500); const collapsedAfter = await readPreviewDiagnostics(page);
  assert.equal(collapsedAfter.phases.touch?.requests || 0, collapsedBefore.phases.touch?.requests || 0);
  await page.getByRole('button', { name: '手机画面', exact: true }).click();
  await delay(500);
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].minimize());
  await delay(400); const minimizedBefore = await readPreviewDiagnostics(page);
  assert.equal(await page.evaluate(() => window.Desktop.invoke('frame', { screen: 'main' })), null);
  await delay(1500); const minimizedAfter = await readPreviewDiagnostics(page);
  assert.equal(minimizedAfter.phases.touch?.committedFrames || 0, minimizedBefore.phases.touch?.committedFrames || 0);
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].restore());
  await delay(500);
  await input('按键', { 键名: '主页' });
  await page.locator('.phone-pane').getByRole('button', { name: '结束操作', exact: true }).click(); manual = false;
  const report = { data, scene: settingsScene ? 'settings-scroll' : 'touch-visualizer', measurement, diagnostics, visibleLatencyMs: visibleLatency,
    collapsedAndMinimizedPause: true, metrics, modelToolNames: names };
  await writeFile(path.join(data, 'result.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report));
  assert.ok(diagnostics.phases.touch.committedFps >= Number(process.env.BBUI_PREVIEW_MIN_FPS || 12), 'Displayed dynamic FPS below target');
} catch (error) {
  if (page) {
    await page.screenshot({ path: path.join(data, 'failure.png') }).catch(() => {});
    partial.error = String(error);
    partial.window = await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().map(window => ({ minimized: window.isMinimized(), visible: window.isVisible() }))).catch(() => null);
    partial.ui = await page.evaluate(() => ({ error: document.querySelector('.preview-error')?.textContent,
      control: window.iosSnapshot?.control, frameId: window.iosFrameId, latency: window.visibleLatency })).catch(() => null);
    await writeFile(path.join(data, 'failure.json'), JSON.stringify(partial, null, 2));
  }
  throw error;
} finally {
  if (manual && page) await page.evaluate(async () => {
    const frame = await window.Desktop.invoke('frame', { screen: 'main' });
    await window.Desktop.invoke('input', { operation: '按键', params: { 键名: '主页' }, screen: 'main', frameId: frame.frameId,
      controlId: window.iosSnapshot.control.id, actionId: crypto.randomUUID() });
  }).catch(() => {});
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().forEach(window => window.close())).catch(() => {});
  await application.close(); api.close();
}
