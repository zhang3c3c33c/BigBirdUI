# BBUI Android chat assets

The Android service is the only state owner. This React surface renders its snapshots
with assistant-ui's `useExternalStoreRuntime`; it does not contact a model, execute
tools, persist credentials, or regenerate phone actions.

```powershell
cd android/chat-ui
npm ci --ignore-scripts
npm test
npm run build
# Uses installed Chrome; BBUI_TEST_BROWSER=edge selects installed Edge.
npm run test:browser
```

Copy the complete `dist/` directory into the APK's `assets/chat/` directory and load
`https://appassets.androidplatform.net/assets/chat/index.html` with WebViewAssetLoader.
All assets use relative paths and the CSP disallows network requests. The native
WebView must route external HTTPS links outside the privileged chat origin.

The bridge and snapshot schema are documented in `../../ANDROID-CHAT-CONTRACT.md`.
The browser listens before sending `ready`. Every accepted snapshot replaces the
displayed state, and unchanged message identities remain stable for assistant-ui.
Markdown uses the library's streaming parser with artificial smoothing disabled.
Only native `send` and `stop` commands control execution; no edit/retry/regenerate
or client-side tool handlers are registered. Thought and tool sections begin closed.

`npm test` covers Unicode chunk boundaries, final correction, long history, stable
identity, older-page insertion, tool failures and interrupted replies. Android
instrumentation covers the trusted origin, WebView recreation, native panels and
phone controls separately.
