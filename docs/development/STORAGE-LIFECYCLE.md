# Android storage lifecycle

## Rules

- **Runtime payloads:** keep the payload named by the installed APK manifest. After the runtime successfully responds to the state/history handshake, collect other `no_backup/pi-payload-<SHA256>` directories in a background thread. A per-payload OS file lock protects any version still in use. Failed startup does not trigger collection; interrupted collection resumes on a later verified startup. Traversal never follows symbolic links. Remove the temporary runtime ZIP after extraction, including extraction failures.
- **Screenshots:** normal observations still send the encoded image to Pi, but do not save an extra original PNG. Original images are saved only for errors, unstable observations, or explicit diagnostic tests, under `cache/bbui-diagnostics`. Old UUID-named PNG duplicates in `files/device-runs` are removed during migration. Keep current observation/connection metadata.
- **Diagnostic evidence:** expire after seven days and evict oldest files above 100 MiB. New evidence is bounded on each write; service startup, task settlement/stop/channel release (throttled to once per minute), and Android low-storage notifications run maintenance off the UI thread. Maintenance includes the legacy `gate-evidence` and `paste-isolation` roots in the shared budget. Android can also reclaim the new cache directory. No guarantee of a wall-clock expiry while the app is not running.
- **User data:** this maintenance never deletes Pi session JSONL, images embedded in those sessions, memory, model/search credentials, drafts, queue state, or action-deduplication receipts. Existing explicit session deletion remains the way to remove conversation history; action receipts are retained because their identity protection must outlive uncertain input. No age-based history removal or automatic replay is introduced.
- **Other caches:** WebView and Node compilation caches remain under Android cache storage. This change does not sweep them on every task or add a storage-management UI.

## Verification — 2026-09-28

Device: vivo V2366GA, Android 16 / API 36. Main APK overwritten in place; app data was not reset.

| Private storage | Before (KiB) | After (KiB) |
| --- | ---: | ---: |
| `no_backup` | 2,668,243 | 284,841 |
| `files` | 300,211 | 9,811 |
| `cache` | 43,579 | 43,627 |

Measured reduction: **2,737,924,096 bytes (2.74 GB)**. Remaining measured private directories: **346,397,696 bytes (346 MB)**. These numbers exclude the APK, installed native libraries, and any other Android accounting categories; they are not the system Settings total app size.

- Payload directories: **22 → 1**; retained manifest payload uses 206,116 KiB. Normal screenshot duplicates: `device-runs` **290,416 → 16 KiB**.
- SHA-256 verification of **459 existing files** covering user sessions, memory, encrypted model/search configuration and action receipts: **459 unchanged; zero missing**. Post-install hashes were calculated on the device, returning only hashes for comparison.
- **115 Android unit tests passed:** app 68, device 36, runtime 11. Includes protected-data preservation, active-runtime leases, incomplete startup, symlink boundaries, age/size eviction and atomic diagnostic writes.
- **Three real-device instrumentation tests passed:** `SystemBindingTest` (2) and `RuntimeGateTest` (1). Verified read-only queries do not create a virtual screen, normal observations still contain model images without duplicate PNG files, and bundled Pi completes image/tool rounds using a local deterministic provider.
- App restarted after collection; history remained available. No real model/search API requests were made.

Build command: `scripts/build-android.ps1 -SkipRuntime -SkipChat -Tasks ':app:assembleDebug',':app:assembleDebugAndroidTest',':app:testDebugUnitTest',':device:testDebugUnitTest',':runtime:testDebugUnitTest'`.

Installed main APK SHA-256: `7d92d18cc7933c7daeaee526ea4f10c97f571f72bb3d770a284485b2d972ea6b`.

Local, ignored evidence: `runs/task-anomaly/storage-build.log`, `storage-build-final.log`, `storage-device-tests.log`, `storage-after.txt`, `storage-preservation-after.json`, and `storage-result.json`.
