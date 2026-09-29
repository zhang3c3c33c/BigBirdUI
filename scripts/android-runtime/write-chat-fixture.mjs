// Sanitize an actual exact-payload mock run; never run this against a real user session.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const events = JSON.parse(await readFile(path.join(root, 'android/runtime/build/pi-chat-events.json'), 'utf8'));
if (!events.some(event => event.message?.role === 'user' &&
    JSON.stringify(event.message.content).includes('BBUI_REAL_FIRST: 只观察当前页面并保存任务进展。用户约束：'))) {
  throw new Error('Expected only the deterministic mock payload test capture');
}
const omitted = new Set(['usage', 'api', 'provider', 'model', 'responseId', 'sections', 'toolsAdded']);
function sanitize(value) {
  if (Array.isArray(value)) return value.filter(item => item?.role !== 'system' && item?.type !== 'image').map(sanitize);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value)
    .filter(([key]) => !omitted.has(key)).map(([key, item]) => [key, sanitize(item)]));
  return value;
}
const fixture = sanitize(events.filter(event => event.message?.role !== 'system'));
const output = JSON.stringify(fixture, null, 2) + '\n';
if (/base64|[A-Za-z]:\\\\|apiKey|BBUI_MODEL_KEY/.test(output)) throw new Error('Unexpected sensitive fixture content');
const directory = path.join(root, 'pi/android/fixtures');
await mkdir(directory, { recursive: true });
await writeFile(path.join(directory, 'chat-events.json'), output);
console.log(`Wrote ${fixture.length} real Pi mock events (${Buffer.byteLength(output)} bytes)`);
