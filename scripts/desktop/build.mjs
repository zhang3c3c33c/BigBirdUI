import { build } from 'esbuild';
import { mkdir, readFile, writeFile, copyFile, cp, readdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { stripTypeScriptTypes } from 'node:module';
import { bundleTools } from '../android-runtime/bundle-tools.mjs';
import { patchSessionReader } from '../android-runtime/patch-session-reader.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const app = path.join(root, '.desktop/app'), runtime = path.join(app, 'runtime'), python = path.join(app, 'python');
const hostPython = process.env.BBUI_PYTHON_HOST || path.join(root, '.venv/Scripts/python.exe');
if (process.version !== 'v24.15.0') throw new Error('Desktop runtime requires pinned Node 24.15.0');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
function run(file, args, cwd = root, env = process.env) {
  const result = spawnSync(file, args, { cwd, env, stdio: 'inherit', windowsHide: true, shell: false });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${path.basename(file)} exited ${result.status}`);
}
for (const folder of [app, runtime, python]) await mkdir(folder, { recursive: true });
run(process.execPath, [path.join(root, 'design/brand/generate.mjs')]);
run(hostPython, ['-c', 'from PIL import Image; import sys,pathlib; p=pathlib.Path(sys.argv[1]); sizes=[16,24,32,48,64,128,256]; Image.open(p/"bbui-app-icon-256.png").save(sys.argv[2],sizes=[(s,s) for s in sizes],append_images=[Image.open(p/f"bbui-app-icon-{s}.png") for s in sizes])',
  path.join(root, 'design/brand/png'), path.join(app, 'brand.ico')]);
const lock = await readFile(path.join(root, 'package-lock.json'));
await copyFile(path.join(root, 'package.json'), path.join(runtime, 'package.json'));
await writeFile(path.join(runtime, 'package-lock.json'), lock);
const marker = path.join(runtime, '.dependencies-sha256');
if (!existsSync(marker) || await readFile(marker, 'utf8') !== sha(lock)) {
  run('cmd.exe', ['/d', '/c', 'npm.cmd', 'ci', '--omit=dev', '--ignore-scripts', '--no-audit', '--no-fund'], runtime);
  await writeFile(marker, sha(lock));
}
// Share the Android runtime bootstrap and tools; only the device bridge differs.
await patchSessionReader(runtime);
for (const name of ['bootstrap', 'sessions', 'session-title', 'runtime-config', 'model-probe', 'rpc-timing', 'display-projection', 'request-budget', 'extensions-manifest']) {
  await copyFile(path.join(root, 'pi/android', name + '.mjs'), path.join(runtime, name + '.mjs'));
}
for (const file of ['pi/phone-contract.ts', 'pi/task-state.ts', 'pi/phone-skill.ts', 'pi/android/screenshot-context.ts', 'pi/desktop/extension.ts']) {
  const target = path.join(runtime, file.replace(/\.ts$/, '.js')); await mkdir(path.dirname(target), { recursive: true });
  await writeFile(target, stripTypeScriptTypes(await readFile(path.join(root, file), 'utf8'), { mode: 'transform' }));
}
await cp(path.join(root, 'pi/desktop/tools.mjs'), path.join(runtime, 'pi/desktop/tools.mjs'));
await cp(path.join(root, 'pi/skills/phone-operation'), path.join(runtime, 'pi/skills/phone-operation'), { recursive: true });
await bundleTools(path.join(runtime, 'pi/android/tools'));
await copyFile(path.join(root, 'android/app/src/main/assets/model-catalog.json'), path.join(runtime, 'model-catalog.json'));
await build({ entryPoints: [path.join(root, 'desktop/main.mjs')], outfile: path.join(app, 'main.mjs'), bundle: true,
  platform: 'node', format: 'esm', target: 'node24', external: ['electron'], legalComments: 'linked', banner: { js: "import { createRequire as bbuiCreateRequire } from 'node:module'; const require = bbuiCreateRequire(import.meta.url);" } });
await copyFile(path.join(root, 'desktop/preload.cjs'), path.join(app, 'preload.cjs'));
await copyFile(path.join(root, 'LICENSE'), path.join(app, 'LICENSE'));
await copyFile(path.join(root, 'THIRD-PARTY-NOTICES.md'), path.join(app, 'THIRD-PARTY-NOTICES.md'));
await copyFile(path.join(root, 'docs/guides/DESKTOP.md'), path.join(app, 'README.md'));
await copyFile(path.join(root, 'docs/guides/IOS-DESKTOP-CAPABILITIES.md'), path.join(app, 'IOS-CAPABILITIES.md'));
if (existsSync(path.join(root, 'docs/reports/IOS-DESKTOP-ACCEPTANCE.md'))) await copyFile(path.join(root, 'docs/reports/IOS-DESKTOP-ACCEPTANCE.md'), path.join(app, 'IOS-ACCEPTANCE.md'));
await writeFile(path.join(app, 'package.json'), JSON.stringify({ name: 'bbui-desktop', productName: 'BBUI', version: '0.1.0', license: 'MIT', type: 'module', main: 'main.mjs' }));
run(process.execPath, [path.join(root, 'android/chat-ui/node_modules/vite/bin/vite.js'), 'build'], path.join(root, 'android/chat-ui'), { ...process.env, BBUI_DESKTOP_BUILD: '1' });
await mkdir(path.join(app, 'node'), { recursive: true });
await copyFile(process.execPath, path.join(app, 'node/node.exe'));
const nodeLicense = path.join(path.dirname(process.execPath), 'LICENSE');
if (existsSync(nodeLicense)) await copyFile(nodeLicense, path.join(app, 'node/LICENSE'));
else if (!existsSync(path.join(app, 'node/LICENSE'))) {
  const response = await fetch('https://raw.githubusercontent.com/nodejs/node/v24.15.0/LICENSE');
  if (!response.ok) throw new Error('Cannot obtain pinned Node license');
  await writeFile(path.join(app, 'node/LICENSE'), await response.text());
}
// Copy a relocatable CPython runtime, excluding the host's unrelated packages.
const base = execFileSync(hostPython, ['-c', 'import sys;print(sys.base_prefix)'], { encoding: 'utf8' }).trim();
if (execFileSync(hostPython, ['--version'], { encoding: 'utf8' }).trim() !== 'Python 3.12.14') throw new Error('Desktop runtime requires pinned Python 3.12.14');
for (const entry of await readdir(base, { withFileTypes: true })) {
  if (['Lib', 'DLLs', 'libs', 'include'].includes(entry.name) || /^(python.*\.(exe|dll|zip)|vcruntime.*\.dll|LICENSE.*)$/.test(entry.name)) {
    await cp(path.join(base, entry.name), path.join(python, entry.name), { recursive: true,
      filter: source => !source.split(path.sep).some(part => ['site-packages', '__pycache__', 'test', 'tests', 'idlelib', 'tkinter', 'turtledemo', 'ensurepip'].includes(part)) });
  }
}
const requirements = path.join(root, 'scripts/desktop/requirements.lock');
const pyMarker = path.join(python, '.dependencies-sha256'); const pyHash = sha(await readFile(requirements));
if (!existsSync(pyMarker) || await readFile(pyMarker, 'utf8') !== pyHash) {
  // hexdump 3.3 is pure Python and upstream publishes only an sdist. All native
  // dependencies still require wheels; the portable app never builds packages.
  run(hostPython, ['-m', 'pip', 'install', '--only-binary=:all:', '--no-binary=hexdump', '--upgrade', '--target', path.join(python, 'Lib/site-packages'), '-r', requirements]);
  await writeFile(pyMarker, pyHash);
}
run(path.join(python, 'python.exe'), ['-c', 'from pymobiledevice3.services.afc import AfcService; from pymobiledevice3.remote.userspace_tunnel import UserspaceRsdTunnel; from pymobiledevice3.remote.core_device.hid_service import UniversalHIDServiceService']);
await cp(path.join(root, 'bbui'), path.join(app, 'source/bbui'), { recursive: true, filter: source => !source.includes('__pycache__') });
await cp(path.join(root, 'vendor/scrcpy/scrcpy-win64-v4.1'), path.join(app, 'assets/vendor/scrcpy/scrcpy-win64-v4.1'), { recursive: true });
await copyFile(path.join(root, 'vendor/scrcpy/scrcpy-server-v4.1'), path.join(app, 'assets/vendor/scrcpy/scrcpy-server-v4.1'));
await copyFile(path.join(root, 'vendor/scrcpy/LICENSE'), path.join(app, 'assets/vendor/scrcpy/LICENSE'));
const agent = path.join(root, 'android/desktop-agent/build/outputs/apk/debug/desktop-agent-debug.apk');
if (!existsSync(agent)) throw new Error('Build :desktop-agent:assembleDebug first');
await copyFile(agent, path.join(app, 'assets/vendor/desktop-agent.jar'));
const manifest = { version: 1, platform: 'win32', arch: 'x64', dataVersion: 1, node: process.version,
  python: execFileSync(hostPython, ['--version'], { encoding: 'utf8' }).trim(), pi: '0.87.0', scrcpy: '4.1',
  ios: { transport: 'usb', preview: 'screenshots', previewTargetFps: 15, pymobiledevice3: '9.34.0', pmdPytcp: '0.0.6', pytunPmd3: '3.0.3' },
  nodeLockSha256: sha(lock), pythonLockSha256: pyHash,
  paths: { icon: 'brand.ico', node: 'node/node.exe', pi: 'runtime', python: 'python/python.exe', pythonSource: 'source',
    assets: 'assets', adb: 'assets/vendor/scrcpy/scrcpy-win64-v4.1/adb.exe' },
  hashes: { androidAgent: sha(await readFile(agent)), node: sha(await readFile(process.execPath)) } };
await writeFile(path.join(app, 'runtime-manifest.json'), JSON.stringify(manifest, null, 2));
const notices = [];
async function collectNotices(directory, copyLicenses = false) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (!entry.isDirectory() || entry.name.startsWith('.')) continue;
    const folder = path.join(directory, entry.name), file = path.join(folder, 'package.json');
    if (entry.name.startsWith('@')) { await collectNotices(folder, copyLicenses); continue; }
    if (existsSync(file)) {
      const pkg = JSON.parse(await readFile(file, 'utf8'));
      const files = (await readdir(folder)).filter(name => /^(licen[cs]e|copying|notice)/i.test(name));
      const destination = copyLicenses ? path.join(app, 'licenses/ui', pkg.name, pkg.version) : folder;
      if (copyLicenses) {
        await mkdir(destination, { recursive: true });
        for (const file of files) await cp(path.join(folder, file), path.join(destination, file), { recursive: true });
      }
      notices.push({ name: pkg.name, version: pkg.version, license: pkg.license,
        directory: path.relative(app, destination).split(path.sep).join('/'), notices: files });
    }
    if (existsSync(path.join(folder, 'node_modules'))) await collectNotices(path.join(folder, 'node_modules'), copyLicenses);
  }
}
await collectNotices(path.join(runtime, 'node_modules'));
await collectNotices(path.join(root, 'android/chat-ui/node_modules'), true);
await writeFile(path.join(app, 'THIRD-PARTY-NOTICES.json'), JSON.stringify({ node: 'node/LICENSE', python: 'python/LICENSE.txt',
  scrcpy: 'assets/vendor/scrcpy/LICENSE', pythonPackages: 'python/Lib/site-packages/*-info', packages: notices }, null, 2));
console.log('Desktop application staged:', app);
