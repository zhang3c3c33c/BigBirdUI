import { packager } from '@electron/packager';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const output = path.resolve(process.env.BBUI_DESKTOP_RELEASE_DIR || path.join(root, '.desktop/releases'));
const [directory] = await packager({ dir: path.join(root, '.desktop/app'), name: 'BBUI', platform: 'win32', arch: 'x64',
  icon: path.join(root, '.desktop/app/brand.ico'),
  electronVersion: JSON.parse(await readFile(path.join(root, 'node_modules/electron/package.json'), 'utf8')).version,
  out: output, overwrite: true, asar: false, prune: false });
const archive = path.join(output, 'BBUI-Windows-x64.zip');
execFileSync(path.join(root, '.venv/Scripts/python.exe'), ['-c', 'import shutil,sys;shutil.make_archive(sys.argv[1],"zip",sys.argv[2])', archive.slice(0, -4), directory], { stdio: 'inherit', windowsHide: true });
const hash = createHash('sha256').update(await readFile(archive)).digest('hex');
await writeFile(archive + '.sha256', hash + '  ' + path.basename(archive) + '\n');
console.log(archive, hash);
