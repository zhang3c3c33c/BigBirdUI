import register, { NativeToolsBridge } from '../android/tools/agent-tools.js';
export default function desktopTools(pi) {
  const bridge = new NativeToolsBridge(process.env.BBUI_BRIDGE_URL, process.env.BBUI_BRIDGE_TOKEN);
  bridge.desktop = true;
  bridge.deviceCapabilities = process.env.BBUI_DEVICE_CAPABILITIES ? JSON.parse(process.env.BBUI_DEVICE_CAPABILITIES) : undefined;
  bridge.stop = async (source = 'user') => {
    await fetch(bridge.url + (source === 'transport' ? '/stop' : '/cancel'), {
      method: 'POST', headers: bridge.headers(), body: '{}', signal: AbortSignal.timeout(5000),
    }).catch(() => {});
  };
  register(pi, bridge);
}
