import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

// Node readline treats U+2028/U+2029 in JSON strings as line breaks. Keep Pi's
// pinned parser/index and disk format; escape separators only in its input stream.
export async function patchSessionReader(stage) {
  const file = path.join(stage, 'node_modules/@earendil-works/pi-coding-agent/dist/core/session-manager.js');
  let source = await readFile(file, 'utf8');
  if (source.includes('bbuiJsonlSeparators')) return;
  const original = 'input: createReadStream(filePath, { encoding: "utf8", signal }),';
  if (!source.includes(original)) throw new Error('Pinned Pi catalog reader changed');
  source = 'import { Transform as BbuiJsonlTransform } from "node:stream";\n' + source.replace(original,
    'input: createReadStream(filePath, { encoding: "utf8", signal }).pipe(new BbuiJsonlTransform({ decodeStrings: false, transform(chunk, _encoding, done) { /* bbuiJsonlSeparators */ done(null, chunk.replace(/\\u2028/g, "\\\\u2028").replace(/\\u2029/g, "\\\\u2029")); } })),');
  await writeFile(file, source);
}
