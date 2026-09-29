import { join } from 'node:path';

// CLI development fallback. Packaged hosts provide their manifest's executable.
export function pythonRuntime(root: string): string {
  if (process.env.BBUI_PYTHON) return process.env.BBUI_PYTHON;
  return process.platform === 'win32' ? join(root, '.venv', 'Scripts', 'python.exe') : join(root, '.venv', 'bin', 'python');
}
