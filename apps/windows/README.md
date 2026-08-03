# Vantage — Windows workstation (Tauri + React)

Windows desktop app for Discount Screener / Vantage.

## Setup

```text
cd apps/windows
npm install
```

Requires Node.js, Rust toolchain, [Tauri prerequisites](https://v2.tauri.app/start/prerequisites/), and WebView2.

## Commands

| Command | Purpose |
| --- | --- |
| `npm test` | Frontend unit tests |
| `npm run build` | Type-check + Vite production build |
| `npm run tauri:dev` | Live Tauri app (default universe = last saved / `sp500`) |
| **`npm run tauri:dev:qa`** | **Live Tauri with QA universe locked (≤20 symbols)** — use for agent/manual live QA |
| `npm run test:e2e` | WebdriverIO e2e (when configured) |
| `npm run test:e2e:native:cof` | Native hidden Tauri regression: real COF IPC → hero valuation slot |
| **`npm run ds-ui -- <cmd>`** | **Attach CLI** to a running debug app (screenshot, invoke, open-detail) — see below |
| `npm run live-qa:checklist` | Attach live valuation checklist (needs `tauri:dev:qa` already running) |

### Live QA (agents and humans) — ALWAYS profile `qa`

**QA se hace con profile `qa`.** No exceptions unless the user explicitly orders another universe.

| | |
| --- | --- |
| **Required command** | `npm run tauri:dev:qa` |
| **Wrong** | `npm run tauri:dev` / bare `tauri dev` for QA (often full SP500) |
| **Wrong** | `tauri dev -- -- --profile qa` (Cargo steals `--profile`) |

```text
npm run tauri:dev:qa
```

This sets `DS_UNIVERSE_PROFILE=qa` and starts Tauri. The app locks membership to ≤20 SP500 names (top score + ≥25% gap from `history.sqlite`, with priority fill if the DB is thin).

**Do not** use:

```text
tauri dev -- -- --profile qa
cargo tauri dev -- -- --profile qa
```

Cargo treats `--profile` as a **compile** profile (`error: profile qa is not defined`), so the flag often never reaches the app and agents fall back to a full-universe cold start.

Equivalent env form:

```text
$env:DS_UNIVERSE_PROFILE = "qa"
npm run tauri:dev
```

Binary (when the frontend/dev server is already up):

```text
.\src-tauri\target\debug\discount-screener-windows.exe --universe qa
```

See also: [`docs/valuation-live-qa-checklist.md`](../../docs/valuation-live-qa-checklist.md) and `AGENTS.md` § Windows live QA profile.

### Agent / attach UI control (`ds-ui`)

Debug builds expose WebView2 DevTools Protocol on **loopback only** (`127.0.0.1:9222`). The attach CLI talks to that endpoint **without restarting** the app — keep one long-lived `tauri:dev:qa` process and drive it from the terminal (or an agent).

```text
# terminal 1 — once
npm run tauri:dev:qa

# terminal 2 — gate then probes (do not restart the app)
npm run ds-ui:self-check
npm run live-qa:checklist
npm run ds-ui -- status
npm run ds-ui -- open-detail COF
npm run ds-ui -- dcf-slot
npm run ds-ui -- screenshot
npm run ds-ui -- invoke get_symbol_detail "{\"symbol\":\"CI\"}"
```

| Command | Purpose |
| --- | --- |
| `status` / `ping` | CDP up? Tauri bridge? profile locked? |
| `invoke <cmd> [json]` | Same IPC as `src/api.ts` |
| `text` / `click` / `type` | DOM helpers |
| `open-detail <SYM>` | Opens Detail via DEV `window.__DS_AGENT__` (fallback DOM) |
| `agent-snap` | React snapshot (selected symbol, profile, list) |
| `dcf-slot` | Hero valuation text |
| `screenshot [path]` | PNG under `.agents/workspace/tmp/ui-shots` by default |
| `qa-snapshot [SYM]` | Compact feed + backend detail + slot JSON |

DEV builds expose `window.__DS_AGENT__` (`openSymbol`, `closeDetail`, `snapshot`) so agents can drive UI without fragile list filters.

Shared client: `e2e/native/cdp-client.mjs`. Port override: `DS_UI_CDP_PORT` (native COF e2e uses **9333** on an isolated process; live attach uses **9222**).

Live attach checklist (app must already be running):

```text
npm run live-qa:checklist
```

## Rust tests (valuation / feed)

```text
cd src-tauri
cargo test --lib
cargo test --lib dcf_model::
cargo test --lib valuation_baseline::
```
