// Opt-in real-device test. Only observes; never sends phone input or launches apps.
import assert from 'node:assert/strict';
import { mkdtemp, readFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { DesktopHost } from '../desktop/host.mjs';

if (!process.env.BBUI_SMOKE_DEVICE) throw new Error('Set BBUI_SMOKE_DEVICE explicitly');
const root = path.resolve('.desktop/app');
const manifest = JSON.parse(await readFile(path.join(root, 'runtime-manifest.json')));
const platform = { data: await mkdtemp(path.resolve('runs/desktop-recovery-')), root,
  resource: name => name === 'deviceState' ? path.join(process.env.LOCALAPPDATA, 'BBUI/devices') : path.join(root, manifest.paths[name]),
  spawn: (file, args, options) => spawn(file, args, { ...options, windowsHide: true, shell: false }),
  seal: value => JSON.stringify(value), unseal: JSON.parse };
const host = new DesktopHost(platform);
try {
  await host.initialize();
  await host.saveSettings({ ...host.publicSettings(), serial: process.env.BBUI_SMOKE_DEVICE,
    devicePlatform: process.env.BBUI_SMOKE_PLATFORM || 'android' });
  await host.ensureDevice();
  assert.ok((await host.deviceControl('frame', { screen: 'main' })).image);
  const oldPid = host.bridge.transport.pid;
  process.kill(oldPid, 'SIGKILL');
  await host.bridge.exitState.promise;
  const states = [];
  host.on('snapshot', snapshot => states.push(snapshot.desktop.connectionStatus.code));
  await host.checkDeviceConnection();
  assert.equal(host.snapshot().desktop.connected, true);
  assert.ok(states.includes('reconnecting')); assert.equal(states.at(-1), 'ready');
  assert.equal(host.state.paused, true);
  assert.equal((await host.deviceControl('status')).stopped, true);
  assert.notEqual(host.bridge.transport.pid, oldPid);
  assert.ok((await host.deviceControl('frame', { screen: 'main' })).image);
  assert.equal(host.state.active, undefined);
  console.log('Real device executor loss, confirmed exit/lease release and automatic read-only recovery: PASS');
} finally { await host.close(); }
