import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { readFile, writeFile, mkdir, copyFile, cp, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { stripTypeScriptTypes } from 'node:module';
import { bundleTools } from './bundle-tools.mjs';
import { patchSessionReader } from './patch-session-reader.mjs';
import { extensionTypeScriptSources } from '../../pi/android/extensions-manifest.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const moduleDir = path.join(root, 'android/runtime');
const vendor = path.join(moduleDir, 'vendor');
const stage = path.join(moduleDir, 'build/pi-payload');
const output = path.join(moduleDir, 'build/generated/runtime-assets');
const version = '24.21.0-0';
const archiveName = `nodejs-mobile-android-${version}.zip`;
const archive = path.join(vendor, archiveName);
const nativeHash = 'e3cd29a1be03405f11dd5c857af8cd3ad13f84f1409ea648f5328f0bada5bd76';
for (const directory of [vendor, stage, output]) await mkdir(directory, { recursive: true });
function run(command, args, cwd = root) {
  const result = spawnSync(command, args, { cwd, stdio: 'inherit', windowsHide: true });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} exited ${result.status}`);
}
function sha(bytes) { return createHash('sha256').update(bytes).digest('hex'); }
if (!existsSync(archive)) {
  const response = await fetch(`https://github.com/fogtape/nodejs-mobile/releases/download/v${version}/${archiveName}`);
  if (!response.ok) throw new Error(`Node download failed: HTTP ${response.status}`);
  await writeFile(archive, Buffer.from(await response.arrayBuffer()));
}
if (sha(await readFile(archive)) !== nativeHash) throw new Error('Pinned Node artifact SHA256 mismatch; refusing to build');
const nodeLicense = path.join(vendor, 'LICENSE.node');
if (!existsSync(nodeLicense)) {
  const response = await fetch(`https://raw.githubusercontent.com/fogtape/nodejs-mobile/v${version}/LICENSE`);
  if (!response.ok) throw new Error('Cannot download pinned Node license');
  await writeFile(nodeLicense, Buffer.from(await response.arrayBuffer()));
}
if (sha(await readFile(nodeLicense)) !== 'c373dabed995013e091b1908996a69a1647ef19d400e6c1fd4b7fb06d8bc0f66') {
  throw new Error('Pinned Node license SHA256 mismatch');
}
await mkdir(path.join(output, 'licenses'), { recursive: true });
await copyFile(nodeLicense, path.join(output, 'licenses/node.txt'));
await copyFile(path.join(root, 'LICENSE'), path.join(output, 'licenses/bigbirdui.txt'));
await copyFile(path.join(root, 'THIRD-PARTY-NOTICES.md'), path.join(output, 'licenses/third-party-notices.md'));
run(process.env.BBUI_PYTHON_HOST || 'python', ['-c', `
import pathlib,sys,zipfile
archive,vendor,libdir=map(pathlib.Path,sys.argv[1:])
libdir.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(archive) as z:
 for name in z.namelist():
  if name.startswith('include/node/') and not name.endswith('/'):
   destination=vendor/name
   if not destination.resolve().is_relative_to(vendor.resolve()): raise ValueError('unsafe archive path')
   destination.parent.mkdir(parents=True,exist_ok=True)
   content=z.read(name)
   if not destination.is_file() or destination.read_bytes()!=content: destination.write_bytes(content)
 library=libdir/'libnode.so'
 content=z.read('bin/arm64-v8a/libnode.so')
 if not library.is_file() or library.read_bytes()!=content: library.write_bytes(content)
`, archive, vendor, path.join(moduleDir, 'src/main/jniLibs/arm64-v8a')]);

const lock = await readFile(path.join(root, 'package-lock.json'));
const lockHash = sha(lock);
const marker = path.join(stage, '.dependencies-sha256');
await copyFile(path.join(root, 'package.json'), path.join(stage, 'package.json'));
await writeFile(path.join(stage, 'package-lock.json'), lock);
if (!existsSync(marker) || (await readFile(marker, 'utf8')) !== lockHash) {
  const args = ['ci', '--omit=dev', '--ignore-scripts', '--os=android', '--cpu=arm64', '--no-audit', '--no-fund'];
  if (process.platform === 'win32') run('cmd.exe', ['/d', '/c', 'npm.cmd', ...args], stage);
  else run('npm', args, stage);
  await writeFile(marker, lockHash);
}
const piPackage = JSON.parse(await readFile(path.join(stage, 'node_modules/@earendil-works/pi-coding-agent/package.json'), 'utf8'));
if (piPackage.version !== '0.87.0') throw new Error(`Unexpected Pi version ${piPackage.version}`);
await patchSessionReader(stage);

for (const file of extensionTypeScriptSources) {
  const source = await readFile(path.join(root, file), 'utf8');
  const compiled = stripTypeScriptTypes(source, { mode: 'transform' });
  const destination = path.join(stage, file.replace(/\.ts$/, '.js'));
  await mkdir(path.dirname(destination), { recursive: true });
  await writeFile(destination, compiled);
}
await cp(path.join(root, 'pi/skills/phone-operation'), path.join(stage, 'pi/skills/phone-operation'), { recursive: true });
await bundleTools(path.join(stage, 'pi/android/tools'));
await copyFile(path.join(root, 'pi/android/extensions-manifest.mjs'), path.join(stage, 'extensions-manifest.mjs'));
await copyFile(path.join(root, 'pi/android/bootstrap.mjs'), path.join(stage, 'bootstrap.mjs'));
await copyFile(path.join(root, 'pi/android/sessions.mjs'), path.join(stage, 'sessions.mjs'));
await copyFile(path.join(root, 'pi/android/session-title.mjs'), path.join(stage, 'session-title.mjs'));
await copyFile(path.join(root, 'pi/android/runtime-config.mjs'), path.join(stage, 'runtime-config.mjs'));
await copyFile(path.join(root, 'pi/android/model-probe.mjs'), path.join(stage, 'model-probe.mjs'));
await copyFile(path.join(root, 'pi/android/rpc-timing.mjs'), path.join(stage, 'rpc-timing.mjs'));
await copyFile(path.join(root, 'pi/android/display-projection.mjs'), path.join(stage, 'display-projection.mjs'));
await copyFile(path.join(root, 'pi/android/request-budget.mjs'), path.join(stage, 'request-budget.mjs'));
await copyFile(path.join(root, 'pi/android/reasoning-request.mjs'), path.join(stage, 'reasoning-request.mjs'));
run(process.env.BBUI_PYTHON_HOST || 'python', ['-c', `
import pathlib,sys,zipfile
root,output=map(pathlib.Path,sys.argv[1:])
with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for file in sorted(root.rglob('*')):
  if not file.is_file() or file.is_symlink(): continue
  relative=file.relative_to(root)
  if '.bin' in relative.parts or file.suffix in ('.map','.ts') or file.name in ('package-lock.json','.dependencies-sha256'): continue
  # Runtime invokes Pi headlessly, with no terminal or dynamically built plugins.
  # No host native addon/binary may accidentally cross into the APK.
  if file.suffix in ('.node','.exe') or any(part.startswith(('win32-','darwin-','linux-','@esbuild')) for part in relative.parts): continue
  info=zipfile.ZipInfo(relative.as_posix(),date_time=(2026,1,1,0,0,0))
  info.compress_type=zipfile.ZIP_DEFLATED
  z.writestr(info,file.read_bytes())
`, stage, path.join(output, 'pi-runtime.zip')]);
// Vite bundles UI dependencies separately from Pi; retain their attribution too.
const uiNotices = [];
async function collectUiLicenses(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (!entry.isDirectory() || entry.name.startsWith('.')) continue;
    const folder = path.join(directory, entry.name);
    if (entry.name.startsWith('@')) { await collectUiLicenses(folder); continue; }
    const manifest = path.join(folder, 'package.json');
    if (existsSync(manifest)) {
      const pkg = JSON.parse(await readFile(manifest, 'utf8'));
      const destination = path.join(output, 'licenses/ui', pkg.name, pkg.version);
      const files = (await readdir(folder)).filter(name => /^(licen[cs]e|copying|notice)/i.test(name));
      await mkdir(destination, { recursive: true });
      for (const file of files) await cp(path.join(folder, file), path.join(destination, file), { recursive: true });
      uiNotices.push({ name: pkg.name, version: pkg.version, license: pkg.license,
        directory: path.relative(output, destination).split(path.sep).join('/'), notices: files });
    }
    if (existsSync(path.join(folder, 'node_modules'))) await collectUiLicenses(path.join(folder, 'node_modules'));
  }
}
await collectUiLicenses(path.join(root, 'android/chat-ui/node_modules'));
await writeFile(path.join(output, 'licenses/ui-packages.json'), JSON.stringify(uiNotices, null, 2));
const payload = await readFile(path.join(output, 'pi-runtime.zip'));
await writeFile(path.join(output, 'runtime-manifest.json'), JSON.stringify({
  piVersion: '0.87.0', nodeVersion: version, nativeArchiveSha256: nativeHash,
  payloadSha256: sha(payload), dependencyLockSha256: lockHash,
  source: `https://github.com/fogtape/nodejs-mobile/releases/tag/v${version}`,
}, null, 2));
console.log(`Prepared Pi 0.87.0 + Node ${version}; payload ${(payload.length / 1048576).toFixed(1)} MiB`);
