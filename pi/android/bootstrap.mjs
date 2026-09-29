import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { webcrypto } from 'node:crypto';
import { Worker } from 'node:worker_threads';
import { timedRpcWriter } from './rpc-timing.mjs';
import { budgetedFetch } from './request-budget.mjs';
import { extensionManifest, desktopExtensionManifest } from './extensions-manifest.mjs';

// Pi captures stdout's writer when taking ownership of RPC output. Install the
// timestamp adapter first; Pi retains its original framing and backpressure.
process.stdout.write = timedRpcWriter(process.stdout.write.bind(process.stdout));
// App dependencies and extensions are bundled; only user-requested services go online.
process.env.PI_OFFLINE = '1';

let config = {};
const fromEnvironment = Boolean(process.env.BBUI_BOOTSTRAP_CONFIG);
const saveStartup = async value => { if (!fromEnvironment) await writeFile(process.argv[2], value, { mode: 0o600 }); };
try {
  config = JSON.parse(fromEnvironment ? process.env.BBUI_BOOTSTRAP_CONFIG : await readFile(process.argv[2], 'utf8'));
  delete process.env.BBUI_BOOTSTRAP_CONFIG;
  // Consume credentials before imports/validation can fail or take time.
  await saveStartup('{}');
  if (!config.catalogOnly) globalThis.fetch = budgetedFetch(globalThis.fetch.bind(globalThis), config.baseUrl);
  // Keep dependency-resolution errors inside our structured, redacted channel.
  const { modelConfiguration, runtimeSettings, validateThinkingLevel } = await import('./runtime-config.mjs');
  await validateThinkingLevel(config);
  const home = process.env.PI_CODING_AGENT_DIR;
  if (config.probeOnly === true) {
    // The test must never read/write sessions or global Pi preferences.
    await mkdir(home, { recursive: true });
    process.env.BBUI_MODEL_KEY = config.apiKey ?? '';
    await saveStartup('{}');
    const { probeModel } = await import('./model-probe.mjs');
    process.stdout.write(JSON.stringify({ type: 'model_probe_result', ...await probeModel(config, home) }) + '\n');
    process.stdin.resume();
    await new Promise(resolve => process.stdin.on('end', resolve));
    process.exit(0);
  }
  const root = path.dirname(fileURLToPath(import.meta.url));
  process.env.BBUI_BRIDGE_URL = config.bridgeUrl;
  process.env.BBUI_BRIDGE_TOKEN = config.bridgeToken;
  if (config.platform === 'desktop' && config.deviceCapabilities) process.env.BBUI_DEVICE_CAPABILITIES = JSON.stringify(config.deviceCapabilities);
  else delete process.env.BBUI_DEVICE_CAPABILITIES;
  process.env.BBUI_MODEL_KEY = config.apiKey ?? '';
  const provider = config.provider || 'bbui';
  process.env.BBUI_ENVIRONMENT_ID = config.environmentId ?? '';
  process.env.BBUI_PREVIOUS_ENVIRONMENT_ID = config.previousEnvironmentId ?? '';
  process.env.BBUI_ENVIRONMENT_REBUILT = config.environmentRebuilt === true ? 'true' : 'false';
  const model = config.model || 'bbui-unconfigured';
  await mkdir(home, { recursive: true });
  const isGate = config.gate === true;
  // Mock runs must not replace the user's model, thinking settings, or memory.
  const settingsHome = isGate || config.mockPhone === true ? path.join(home, 'gate-agent-settings') : home;
  await mkdir(settingsHome, { recursive: true });
  process.env.PI_CODING_AGENT_DIR = settingsHome;
  process.env.PI_MEMORY_DIR = config.memoryDir || path.join(settingsHome, 'user-memory');
  process.env.PI_MEMORY_NO_SEARCH = '1'; process.env.PI_MEMORY_QMD_UPDATE = 'off';
  process.env.PI_MEMORY_EXIT_SUMMARY = '0'; process.env.PI_MEMORY_SUMMARIZE_TRANSITIONS = '0';
  const search = !isGate ? config.tools?.search : null;
  process.env.BBUI_SEARCH_PROVIDER = search?.provider ?? '';
  process.env.BBUI_SEARCH_KEY = search?.apiKey ?? '';
  process.env.BOCHA_API_KEY = search?.provider === 'bocha' ? search.apiKey ?? '' : '';
  await writeFile(path.join(settingsHome, 'web-search.json'), JSON.stringify({
    fetchRouting: { providers: ['http'], allowRemoteHostedProviders: false },
    pdf: { enabled: false }, image: { enabled: false }, youtube: { enabled: false },
  }), { mode: 0o600 });
  const { memoryCommand } = await import('./pi/android/tools/memory-manager.js');
  // Keep cwd stable across APK/payload upgrades; Pi groups sessions by cwd.
  // Gate history has its own directory and is never continued into real work.
  const workspace = path.join(home, isGate ? 'gate-workspace' : 'workspace');
  await mkdir(workspace, { recursive: true });
  process.chdir(workspace);
  const { SessionCatalog, installSessionRouter } = await import('./sessions.mjs');
  const sessionDirectory = path.join(home, isGate ? 'gate-sessions' : 'sessions');
  const catalog = new SessionCatalog(sessionDirectory, workspace);
  const sessionPath = config.sessionId ? await catalog.resolve(config.sessionId) : null;
  if (!config.catalogOnly && !isGate && !sessionPath) throw new Error('执行任务必须指定会话');
  installSessionRouter(catalog, { catalogOnly: config.catalogOnly === true, sessionId: config.sessionId, memoryCommand });
  if (config.catalogOnly === true) {
    // Keep stdin alive for catalogue commands without starting an agent or connecting a phone.
    await saveStartup('{}');
    await new Promise(resolve => process.stdin.on('end', resolve));
    process.exit(0);
  }
  await writeFile(path.join(settingsHome, 'models.json'), JSON.stringify(modelConfiguration(config)), { mode: 0o600 });
  let previousSettings = {};
  try { previousSettings = JSON.parse(await readFile(path.join(settingsHome, 'settings.json'), 'utf8')); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  await writeFile(path.join(settingsHome, 'settings.json'),
    JSON.stringify(runtimeSettings(previousSettings, provider, model)), { mode: 0o600 });
  // Runtime configuration is app-private. Remove the duplicate plaintext key once
  // consumed; the Android settings store and in-memory provider own credentials.
  await saveStartup(JSON.stringify({ ...config, apiKey: undefined, bridgeToken: undefined, tools: undefined }));
  const cryptoProbe = await webcrypto.subtle.digest('SHA-256', new TextEncoder().encode('bbui'));
  await WebAssembly.instantiate(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0]));
  await new Promise((resolve, reject) => {
    const worker = new Worker("require('node:worker_threads').parentPort.postMessage('ready')", { eval: true });
    const timer = setTimeout(() => { void worker.terminate(); reject(new Error('Node worker probe timed out')); }, 10000);
    worker.once('message', () => { clearTimeout(timer); void worker.terminate(); resolve(); });
    worker.once('error', error => { clearTimeout(timer); reject(error); });
  });
  process.stdout.write(JSON.stringify({ type: 'runtime_boot', node: process.version,
    pi: '0.87.0', platform: process.platform, arch: process.arch,
    wasm: true, worker: true, crypto: cryptoProbe.byteLength === 32 }) + '\n');
  process.argv = ['node', path.join(root, 'node_modules/@earendil-works/pi-coding-agent/dist/bundle/rpc-entry.js'),
    '--no-builtin-tools', '--no-skills', '--skill', path.join(root, 'pi/skills/phone-operation/SKILL.md'), '--no-extensions', ...(config.platform === 'desktop' ? desktopExtensionManifest : extensionManifest).flatMap(item => ['-e', path.join(root, item.entry)]),
    '--provider', provider, '--model', model,
    ...(config.thinkingLevel ? ['--thinking', config.thinkingLevel] : []),
    '--session-dir', sessionDirectory,
    ...(sessionPath ? ['--session', sessionPath] : [])];
  await import('./node_modules/@earendil-works/pi-coding-agent/dist/bundle/rpc-entry.js');
} catch (error) {
  let message = error instanceof Error ? error.message : String(error);
  for (const secret of [config.apiKey, config.bridgeToken, config.tools?.search?.apiKey]) if (secret) message = message.split(secret).join('[redacted]');
  process.stdout.write(JSON.stringify({ type: 'runtime_error', message: message.slice(0, 500) }) + '\n');
  process.exitCode = 1;
}
