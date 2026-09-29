# Android device backend

This module runs the pinned, unmodified scrcpy 4.1 server under a Shizuku
UserService (ADB UID 2000). It creates one `virtual` display at 1080×2160 / 480 dpi (portrait 18:9).
`main`, system notification panels, and accessibility/root fallback are rejected.

`ShizukuPhoneDevice.connect()` runs off the UI thread. The caller requests Shizuku
permission through `DevicePermission` first. A healthy existing connection is
reused. A new connection opens the BBUI test activity and starts paused.

The service owns the scrcpy process and abstract sockets. It returns duplicated
video/control file descriptors through Binder. A single MediaCodec decodes into
an independent SurfaceTexture. Grafika EGL helpers render that texture to an
offscreen surface and an optional preview. PNG observations are read from the
offscreen surface; Android view overlays are never part of this image source.

All modifying phone actions require `截图编号` and `动作编号`. Observations expire
after 180 seconds, are consumed once, and are invalidated by rotation, STOP,
manual takeover, or reconnection. Claims are persisted before input dispatch;
uncertain input is never replayed. `setStopped` and `setManual` are atomic and do
not wait for the action lock. Manual touches carry the takeover epoch and are
revalidated after acquiring the action lock.

The caller must run `action` and `touch` on a worker. `attachPreview` synchronizes
with the GL thread. For manual takeover, call `setStopped(true)` and
`setManual(true, epoch)`. STOP/start must call `setManual(false, newEpoch)`. Resume
requires a fresh observation. UI touch coordinates are relative to the preview
surface and use a fit-center inverse transform.

Private evidence lives in `files/device-runs/` (connection JSON, latest metadata,
PNG observations). Action claims live in `files/device-claims/`. These are app
private runtime data, not repository assets.

## Reused sources

- `assets/scrcpy-server-v4.1`: existing repository binary, SHA-256
  `deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae`.
  Apache-2.0 license included as `assets/SCRCPY-LICENSE`.
- Eight unchanged Google Grafika EGL helper classes. Exact commit and origin are
  recorded in `GRAFIKA-UPSTREAM.txt`; Apache-2.0 license is included as
  `assets/GRAFIKA-LICENSE`.

Build tests with `:device:testDebugUnitTest`. The tests cover pinned scrcpy packet
fixtures, required identifiers, expired/rotated observations, and manual epoch
cancellation. Real-device display isolation, decoder behavior and Binder FD
SELinux compatibility must be reported separately; a host build does not prove
those device capabilities.
