import { deviceIdentity } from './state.mjs';

function output(platform, executable, args, options = {}, timeout = 15000) {
  return new Promise((resolve, reject) => {
    const child = platform.spawn(executable, args, { ...options, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '', stderr = '';
    const timer = setTimeout(() => { child.kill(); reject(new Error('设备发现超时')); }, timeout);
    child.stdout.on('data', chunk => { stdout += chunk; });
    child.stderr.on('data', chunk => { stderr += chunk; });
    child.once('error', error => { clearTimeout(timer); reject(error); });
    child.once('close', code => {
      clearTimeout(timer);
      if (!code) return resolve(stdout);
      let details; try { details = JSON.parse(stderr); } catch {}
      reject(Object.assign(new Error(details?.error || '设备发现失败，请检查手机支持组件'), typeof details?.code === 'string' ? { code: details.code } : {}));
    });
  });
}

async function androidDevices(platform) {
  const text = await output(platform, platform.resource('adb'), ['devices', '-l']);
  return Promise.all(text.split(/\r?\n/).filter(line => /^\S+\s+(device|unauthorized)\b/.test(line)).map(async line => {
    const [serial, state] = line.split(/\s+/);
    let label = '';
    if (state === 'device') {
      for (const command of [['settings', 'get', 'global', 'device_name'], ['getprop', 'ro.product.model']]) {
        try {
          const value = (await output(platform, platform.resource('adb'), ['-s', serial, 'shell', ...command], {}, 2500)).trim();
          if (value && value !== 'null' && !/[\r\n]/.test(value) && value.length <= 200) { label = value; break; }
        } catch { /* A name is optional; retain the enumerated device. */ }
      }
    }
    label ||= line.match(/\bmodel:(\S+)/)?.[1]?.replace(/_/g, ' ') || `Android · ${serial.slice(-8)}`;
    return { serial, label, devicePlatform: 'android', ...(state === 'unauthorized' ? { status: 'unauthorized' } : {}) };
  }));
}

export async function discoverDevices(platform, { devicePlatform } = {}) {
  if (devicePlatform && !['android', 'ios'].includes(devicePlatform)) throw new Error('不支持的设备平台');
  const platforms = devicePlatform ? [devicePlatform] : ['android', 'ios'];
  const results = await Promise.allSettled(platforms.map(kind => kind === 'android' ? androidDevices(platform) :
    output(platform, platform.resource('python'), ['-X', 'utf8', '-m', 'bbui.ios_discovery'], {
      cwd: platform.resource('pythonSource'), env: { ...process.env, PYTHONPATH: platform.resource('pythonSource'), PYTHONUTF8: '1' },
    }).then(text => JSON.parse(text))));
  const devices = results.flatMap(result => result.status === 'fulfilled' ? result.value : []);
  const labels = new Map();
  for (const device of devices) labels.set(device.label, (labels.get(device.label) || 0) + 1);
  return { devices: devices.map(device => ({ ...device, deviceId: deviceIdentity(device),
    label: labels.get(device.label) > 1 ? `${device.label.slice(0, 180)} · ${device.serial.slice(-8)}` : device.label })),
    errors: results.flatMap((result, index) => result.status === 'rejected' ? [{ devicePlatform: platforms[index], message: result.reason.message,
      ...(typeof result.reason.code === 'string' ? { code: result.reason.code } : {}) }] : []) };
}
