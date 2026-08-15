# Live valuation QA checklist

Run this **after any** classifier / FCFF / CapEx / WACC / residual-income / model-policy change, before claiming calibration or merge quality.

---

## Profile rule (read first — non-negotiable)

# QA se hace con profile `qa`

| | |
| --- | --- |
| **Universe for live QA** | Always **`qa`** |
| **Command** | From `apps/windows`: **`npm run tauri:dev:qa`** |
| **Exception** | Only if the human **explicitly** orders another universe (`sp500`, `dow`, …) |
| **Not an exception** | Convenience, habit, “I already have sp500 open”, thoroughness, curiosity |

If the running app is **not** on profile `qa` (or is full SP500 / 500+ symbols), **stop**: relaunch with `npm run tauri:dev:qa` (or attach only if the existing process is already locked `qa`).

Standing agent law is also in root [`AGENTS.md`](../AGENTS.md) → **Build And Test** / **Live QA = profile `qa` only**.

---

## Automated first

```text
cd apps/windows/src-tauri
cargo test --lib dcf_model::
cargo test --lib valuation_baseline::
cargo test --lib quant_lens::
cargo test --lib quote_summary::
cargo test --lib operating_valuation::
```

Optional Android: `scripts/validate-android.ps1` when touching Kotlin engine.

## Bounded DCF-vs-analyst divergence audit

The Windows Estimates panel exposes **Auditoría DCF vs analistas**. Run it only
from the launch-locked `qa` profile. It computes missing Detail valuations
sequentially for the existing ≤20 symbols, ranks by symmetric relative
disagreement, and shows driver/WACC/CapEx evidence. It never changes universe
membership or uses analyst targets as model inputs. Zero-equity outcomes and
unavailable drivers remain visible with their structural reason.

## Launch Windows for this checklist

```text
# REQUIRED — profile qa (≤20 symbols, locked)
cd apps/windows
npm run tauri:dev:qa
```

Equivalent:

```text
$env:DS_UNIVERSE_PROFILE = "qa"
npm run tauri:dev
```

| Rule | Detail |
| --- | --- |
| Profile | **`qa` only** (alias `test` → `qa`) — top-ranking SP500 sample (score + gap≥25%), ≤20 symbols |
| Process | **Reuse** a running **`qa`** instance; do not start a second one. Restart only after native rebuild → one `qa` start again |
| Lock | Leave launch lock on; do not switch universe mid-session |
| Checklist names | Prefer names already in the 20; if missing, **one-shot** load only — never switch to `sp500` |

### Forbidden (broken or wrong universe)

```text
# Wrong universe for QA
npm run tauri:dev          # often full sp500 — NOT for QA
npm exec tauri dev         # same

# Broken: Cargo steals --profile as a compile profile
tauri dev -- -- --profile qa
cargo tauri dev -- -- --profile qa
```

Binary (frontend already up), only if needed:

```text
.\src-tauri\target\debug\discount-screener-windows.exe --universe qa
```

`qa` is a **bounded top-ranking sample**, not a full product-surface sample. Model-path completeness is this checklist + one-shot loads, not loading the whole index.

## Human path (Windows workstation) — only on profile `qa`

| # | Symbol | Expect | Fail if |
| --- | --- | --- | --- |
| 1 | **T** | FCFF operating; base in a defensible band vs analysts; provisional rates marked unreliable | CapEx≈0 / FCF≈OCF; absurd vs Street with no soft badge |
| 2 | **AMZN** | Ordered bear≤base≤bull; not ~$1; not inverted scenarios | Penny intrinsic; bull &lt; bear |
| 3 | **CI** | **Residual income** (not FCFF DCF copy); no $700+ float mirage | Model label/path is FCFF; value absurdly high vs book/analysts |
| 4 | **UNH** or **ELV** | Same managed-care family as CI → residual income | FCFF primary |
| 5 | **JPM**, **ACGL**, or **COF** | Residual income / financial; COF resolves reported payout from Yahoo `summaryDetail` | FCFF on OCF−PPE or COF unavailable for missing retention |
| 6 | **AAPL** or **MSFT** | FCFF operating; sensible vs market order of magnitude | Unclassified refuse; penny; inverted scenarios |
| 7 | **Unknown / garbage sector** (if you can force) | Slot **unavailable** with classified refuse copy | Silent invented DCF |

## Detail slot copy

| State | UI |
| --- | --- |
| Loading | “Valoración…” only |
| Ready + soft rates | Value/range + “no confiable aún” |
| Unclassified | “Valoración no disponible” + **categoría no catalogada** (fail-closed) |
| Not eligible | ETF/REIT/crypto message |
| Missing FCF/book | Specific missing-driver message |
| Forward selected | “Valor por ganancias forward” + soft / no confiable; no invented bear/bull range |
| FCFF ↔ forward disputed | Both named anchors, **no** single value/upside |
| Any unavailable/disputed route | Keyboard-focusable `(i)` with provider, period, reasons/refusals, policy/fingerprints, and stable code locators |

The `(i)` tooltip is an engineering diagnostic, not generic investor copy. For
a provider/model failure, verify it names the owning boundary well enough to
start debugging without reconstructing the pipeline from logs. Escape must
close it; focus/hover/click must expose it.

## Exact Tauri backend probe (debug builds only)

Windows debug builds expose the active WebView2 DevTools Protocol endpoint at
`http://127.0.0.1:9222/json/list`. It is loopback-only and absent from release
builds. Evaluate the following in that page target to exercise the same IPC
bridge as `apps/windows/src/api.ts#getSymbolDetail`:

```js
await window.__TAURI_INTERNALS__.invoke("get_symbol_detail", { symbol: "COF" })
```

### Attach CLI (preferred for agents)

With **one** long-lived `npm run tauri:dev:qa` already running, from `apps/windows`:

```text
npm run ds-ui -- status
npm run ds-ui -- open-detail COF
npm run ds-ui -- dcf-slot
npm run ds-ui -- invoke get_symbol_detail "{\"symbol\":\"COF\"}"
npm run ds-ui -- screenshot
npm run ds-ui -- qa-snapshot CI
npm run live-qa:checklist
```

Implementation: `apps/windows/scripts/ds-ui.mjs` + `e2e/native/cdp-client.mjs`
+ DEV bridge `window.__DS_AGENT__` in `App.tsx`.
Does not start/stop the app. Screenshots default to
`.agents/workspace/tmp/ui-shots/`. Full attach report example:
`_bmad-output/implementation-artifacts/live-qa-wave1-ds-ui-2026-08-01.md`.

For a Detail failure, capture both the returned backend fields
(`dcf_analysis`, `valuation_status`, `valuation_unavailable_reason`, and required
fundamentals) and the rendered `.detail-panel` / `.price-summary .dcf-slot` text.
A backend-only green result does not pass QA if the presentation boundary
suppresses it.

## Notes

- Header gap may be **analyst vs market**, not model vs market — do not confuse the two.
- Quant Lens holds full WACC/FCF diagnostics; overview stays high-level.
- Desktop terminal may lag Windows policy (uplift/FCF normalize); it must still **refuse unclassified** rather than invent FCFF.
- **QA = profile `qa`.** Opening/closing full `sp500` is the anti-pattern this profile exists to kill.
