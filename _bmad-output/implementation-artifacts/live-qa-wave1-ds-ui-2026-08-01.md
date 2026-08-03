# Live QA — wave1 integration + ds-ui agent control (2026-08-01)

## Scope

- Branch: `valuation/wave1-integration`
- Single long-lived Windows app: `npm run tauri:dev:qa` (profile **qa**, locked, 20 symbols)
- Agent UI control: `apps/windows/scripts/ds-ui.mjs` + DEV bridge `window.__DS_AGENT__`
- Automated attach checklist: `npm run live-qa:checklist`

## Offline gates (merge bar)

From `apps/windows/src-tauri`:

| Suite | Result |
| --- | --- |
| `cargo test --lib dcf_model::` | **41 passed** |
| `cargo test --lib valuation_baseline::` | **10 passed**, 1 ignored (live headless PoC) |
| `cargo test --lib quant_lens::` | **8 passed** |

## Agent control plane

| Piece | Role |
| --- | --- |
| Debug WebView2 CDP | `127.0.0.1:9222` (loopback only, debug builds) |
| `e2e/native/cdp-client.mjs` | Shared CDP attach client |
| `scripts/ds-ui.mjs` | CLI: status, invoke, open-detail, screenshot, dcf-slot, … |
| `window.__DS_AGENT__` (DEV) | React-state openSymbol / snapshot / closeDetail |
| `scripts/live-qa-checklist.mjs` | Checklist attach runner |

**Does not** start/stop the app. Reuse one `tauri:dev:qa` process.

### Proven commands

```text
cd apps/windows
npm run tauri:dev:qa          # once
npm run ds-ui -- status
npm run ds-ui -- open-detail CI
npm run ds-ui -- dcf-slot
npm run ds-ui -- screenshot
npm run live-qa:checklist
```

## Live checklist (attach) — PASS 7/7

Evidence dir: `.agents/workspace/tmp/live-qa-2026-08-01/` (`report.json`, per-symbol detail JSON + PNGs).

| Symbol | Model | Base | UI hero (settled) | Notes |
| --- | --- | --- | --- | --- |
| **T** | `fcff_wacc` | $60.55 | Valor DCF · soft / no confiable | FCFF path; ordered scenarios |
| **AMZN** | `fcff_wacc` | $5.15 | **Valoración en disputa** — DCF $5.15 vs forward ~$195 | CapEx spike (2025) collapses FCFF/share; product marks **disputed**, does not sell solid single value |
| **CI** | `residual_income_equity` | $191.13 | Residual income | Not FCFF float mirage |
| **JPM** | `residual_income_equity` | $156.03 | Residual income | Financial path |
| **ACGL** | `residual_income_equity` | $87.09 | Residual income | Not FCFF-primary from float OCF |
| **AAPL** | `fcff_wacc` | $117.14 | Valor DCF | Operating FCFF; ordered scenarios |
| **COF** | `residual_income_equity` | $168.81 | Residual income | Matches native contract order of magnitude |

Feed at run: `profile_name=qa`, `profile_locked=true`, `symbols_loaded=20`.

## AMZN note (honest)

Live SEC drivers show ~$9.5B normalized FCFF vs ~10.8B shares after 2025 CapEx intensity → ~$5 FCFF base. That is **not** treated as a solid megacap intrinsic: `valuation_status=disputed` and Detail shows both anchors without a single confident value. Offline AMZN fixture baseline still guards the multi-ten-B CapEx-trough anti-collapse path separately.

## Fix applied during session

1. Initial DOM `open-detail` failed (ES sidebar **Mercados**, list filters, React search submit).
2. Added DEV `window.__DS_AGENT__` bridge in `App.tsx`.
3. `ds-ui open-detail` prefers agent path; waits for symbol selection + settled `.dcf-slot`.
4. Live checklist requires real UI settle (no stale previous-symbol slot).

## Residual risks

- `open-detail` without DEV/HMR (release binary) falls back to DOM path only.
- Opportunity list numeric filters in localStorage can hide rows for DOM fallback; agent bridge bypasses that.
- AMZN FCFF soft collapse under extreme CapEx years may deserve a later policy calibration (not silently “fixed” with a price clamp).
