// Explicit APK extension entrypoints. Package auto-discovery remains disabled.
export const extensionManifest = Object.freeze([
  { id: 'bbui-phone', entry: 'pi/android/extension.js' },
  { id: 'bbui-tools', entry: 'pi/android/tools/agent-tools.js' },
]);
export const desktopExtensionManifest = Object.freeze([
  { id: 'bbui-phone', entry: 'pi/desktop/extension.js' },
  { id: 'bbui-tools', entry: 'pi/desktop/tools.mjs' },
]);
export const extensionTypeScriptSources = Object.freeze([
  'pi/phone-contract.ts', 'pi/task-state.ts', 'pi/phone-skill.ts', 'pi/android/bridge.ts',
  'pi/android/screenshot-context.ts', 'pi/android/extension.ts',
]);
