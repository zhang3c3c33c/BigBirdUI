# Android tool adapters

The npm lockfile pins MIT-licensed pi-web-access 0.32.0, pi-memory 0.4.2 and @jyooi/pi-ask-user-question 0.1.1. `bundle-tools.mjs` copies each license into the APK and bundles their selected source modules. It never runs package install scripts. esbuild is a host-only development dependency.

## Deliberate adapters

- Web: actual upstream Bocha provider, direct `extractViaHttp` HTTP/Readability/Defuddle extraction, inline-data sanitization, and result/session cache. The build exports the existing private HTTP helper so Android cannot enter GitHub clone, video, browser-cookie or remote-provider routing. Private config disables image/PDF extraction. A bounded fetch wrapper caps provider JSON at 2 MiB before parsing; upstream page reader already caps streamed bodies at 5 MiB. Baidu is a small HTTP adapter to the Qianfan web search endpoint, normalized to upstream SearchResponse. No automatic provider fallback or summarization model.
- Memory: actual upstream read/write/forget/restore executors and recovery records; only long_term targets are registered. UI changes and model writes share the same serialized MEMORY.md store with optimistic SHA-256 revisions. Injection uses upstream bounded context formatting on the authoritative file every task, with no daily/scratchpad snapshots that could restore forgotten facts. Qmd and lifecycle auto-summary hooks are not registered. Explicit restore remains available.
- Questions: actual upstream TypeBox schema and exact formatAnswer/formatResult source slice (build fails if the seam changes), with native authenticated question transport replacing terminal Effect/TUI rendering. One request may hold the execution slot indefinitely; no default answer or HTTP response timeout. Native host owns session/run identities, STOP and invalidation.

Only local fake HTTP servers/response fixtures are used by `npm run test:agent-tools`. Real provider/API tests need separate authorization.

The payload build also applies a version-checked Pi 0.87 catalog-reader compatibility shim: Node 24 readline treats literal U+2028/U+2029 as line separators, even inside valid JSON strings. The shim escapes those two characters in the read stream only, preserving the original JSONL files and Pi indexing. The isolated payload gate covers Unicode session rename/list/restart.
