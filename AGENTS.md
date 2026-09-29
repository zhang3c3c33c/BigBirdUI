# Repository Guidelines

## Project Structure & Module Organization

BBUI supports two user-facing products: a Windows Electron app for Android/iOS and an Android-local app. Pi, Python and MCP are implementation/development components; the standalone Pi CLI, MCP client integration and legacy CLI are not supported user-facing usage modes.

- `bbui/`: Python runtime, screen management, application policy, scrcpy control, CLI, and MCP servers. `unified_mcp.py` is the current MCP entry point.
- `pi/`: Pi launcher, phone extension, and MCP bridge.
- `desktop/`: Windows Electron host.
- `android/`: Android app, native runtime, desktop helper, and shared chat UI.
- `docs/`: guides, contracts, development notes, and historical reports; start at `docs/README.md`.
- `tests/`: Python unit tests, TypeScript tests, and integration/smoke scripts.
- `scripts/` and `setup_scrcpy.py`: scrcpy installation helpers; `vendor/scrcpy/` contains upstream resources.
- `runs/`: generated screenshots, observations, and action logs; ignored by Git.
- `docs/development/PI-HARNESS.md` and `docs/development/UNIFIED-TOOL.md`: architecture, setup, and tool contracts.

## Product Positioning

The full product name is BigBirdUI, the Chinese name is 大鸟手机助手, and BBUI is the abbreviation. Describe it as an AI phone assistant that helps users carry out tasks. Lead public copy with user scenarios and the Windows/Android app experience; keep Pi/MCP mechanics in developer documentation. Use `docs/development/PRODUCT-POSITIONING.md` for scope and capability claims.

Do not automatically open documentation files or editor panels while editing docs unless the user explicitly requests it.

## Build, Test, and Development Commands

Run from the repository root in PowerShell:

- `python -m venv .venv`: create the Python environment.
- `.\.venv\Scripts\python.exe -m pip install -e .`: install the Python package in editable mode.
- `npm ci --ignore-scripts`: install locked Node dependencies.
- `npm run phone`: internal Pi debugging harness only; not a supported end-user entry point.
- `npm run typecheck`: check strict TypeScript types without emitting files.
- `.\.venv\Scripts\python.exe -m unittest discover -s tests -v`: run Python unit tests.
- `npm run test:pi`: run Node's test runner through `tsx`.
- `npm run smoke:pi`: run the Pi smoke check; adding `-- --device` performs real phone actions.

## Coding Style & Naming Conventions

Follow surrounding code: Python uses four-space indentation, `snake_case` functions/modules, and `PascalCase` classes. TypeScript uses two-space indentation, single quotes, semicolons, and `camelCase` functions. Preserve the Chinese keys in the public phone-action schema. TypeScript uses strict NodeNext modules. No formatter or linter is configured; avoid unrelated reformatting.

## Testing Guidelines

Use Python `unittest` in `tests/test_*.py` and `node:test` in `tests/*.test.ts`. Add regression coverage for changed behavior, particularly stale observations, policy enforcement, cancellation, and screen cleanup. No numeric coverage threshold is configured. Run both unit suites and type checking for cross-language changes. Report device smoke tests separately, including device/Android version and observed results.

## Commit & Pull Request Guidelines

Use concise imperative subjects, optionally prefixed with `fix:`, `feat:`, or `docs:`. Keep commits focused. PRs should explain the behavior change, link relevant issues, list validation commands/results, and include sanitized screenshots when phone behavior changes.

## Configuration & Runtime Safety

For internal harness debugging, copy `config.example.json` to `config.local.json`; keep device settings, credentials, sessions, and private screenshots untracked. Preserve observation validation, STOP handling, and per-device ownership. Never automatically replay uncertain phone input; inspect the resulting state first.

## Tool Design Philosophy

Tools execute explicit operations and report facts; the model decides next steps from the user's task, preferences, and current observations. Do not embed task-level decisions such as mandatory human takeover, fixed tool-call budgets, or success based only on a target package name into an executor. Preserve the user's explicit authorization boundaries.

Report dispatch, observation, and channel health separately. A post-action screenshot failure must not erase a known dispatch receipt. Business rejections and intermediate pages must not invalidate a healthy connection. Keep read-only observation available when its channel works; do not automatically replay or resume uncertain input. User STOP and takeover remain authoritative.

Declare only capabilities actually implemented by each backend. Editor type and visual changes are evidence, not blanket business prohibitions. Retain display/user/coordinate identity, latest-observation validation, action deduplication, and user-configured restrictions. See `docs/contracts/TOOL-DESIGN-REVIEW.md` and `docs/contracts/TOOL-CONTRACT.md` for the audit and implemented contract.
