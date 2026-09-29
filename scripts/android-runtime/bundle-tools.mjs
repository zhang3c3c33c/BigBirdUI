import { build } from 'esbuild';
import { readFile, mkdir, copyFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
export async function bundleTools(output) {
  const versions = { 'pi-web-access': '0.32.0', 'pi-memory': '0.4.2', '@jyooi/pi-ask-user-question': '0.1.1' };
  for (const [name, version] of Object.entries(versions)) {
    const directory = path.join(root, 'node_modules', name);
    const pkg = JSON.parse(await readFile(path.join(directory, 'package.json'), 'utf8'));
    if (pkg.version !== version) throw new Error(`Unexpected ${name} version ${pkg.version}`);
    await mkdir(path.join(output, 'licenses'), { recursive: true });
    await copyFile(path.join(directory, 'LICENSE'), path.join(output, 'licenses', `${name.replaceAll('/', '-')}.txt`));
  }
  await build({ entryPoints: { 'agent-tools': path.join(root, 'pi/android/agent-tools.mjs'),
    'memory-manager': path.join(root, 'pi/android/memory-manager.mjs') }, outdir: output,
    bundle: true, splitting: true, format: 'esm', platform: 'node', target: 'node24',
    sourcemap: false, legalComments: 'linked', plugins: [{ name: 'android-upstream-adapters', setup(b) {
      // Retain APK's locked dependencies; only upstream extension source is bundled.
      b.onResolve({ filter: /^[^./]|^@/ }, args => {
        if (args.kind === 'entry-point' || path.isAbsolute(args.path)) return;
        if (Object.keys(versions).some(name => args.path === name || args.path.startsWith(name + '/'))) return;
        return { path: args.path, external: true };
      });
      b.onLoad({ filter: /pi-web-access[\\/]bocha\.ts$/ }, async args => ({
        contents: `import { boundedSearchFetch } from ${JSON.stringify(path.join(root, 'pi/android/bounded-http.mjs'))};\n` +
          (await readFile(args.path, 'utf8')).replace('await fetch(BOCHA_SEARCH_URL,', 'await boundedSearchFetch(BOCHA_SEARCH_URL,'), loader: 'ts' }));
      b.onLoad({ filter: /pi-web-access[\\/]extract\.ts$/ }, async args => ({
        contents: await readFile(args.path, 'utf8') + '\nexport { extractViaHttp };\n', loader: 'ts' }));
      b.onLoad({ filter: /pi-memory[\\/]index\.ts$/ }, async args => ({
        contents: await readFile(args.path, 'utf8') + '\nexport { formatContextSection, writeRecoveryRecord, CONTEXT_LONG_TERM_MAX_LINES, CONTEXT_LONG_TERM_MAX_CHARS };\n', loader: 'ts' }));
      b.onLoad({ filter: /pi-ask-user-question[\\/]src[\\/]index\.ts$/ }, async args => {
        const original = await readFile(args.path, 'utf8');
        const start = original.indexOf('function formatAnswer('), end = original.indexOf('async function executeAsk(');
        if (start < 0 || end < start) throw new Error('Upstream ask formatter changed');
        return { contents: 'import { sanitizeTerminalText } from "./terminal-text.js";\n' + original.slice(start, end) + '\nexport { formatResult };', loader: 'ts' };
      });
    } }], logLevel: 'warning' });
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await bundleTools(path.resolve(process.argv[2] || path.join(root, 'runs/tool-build')));
}
