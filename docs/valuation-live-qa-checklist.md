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

Standing agent law is also in root [`AGENTS.md`](../AGENTS.md) → **Build And Test** / **Windows live QA = profile `qa` only**.

---

## Automated first

```text
cd apps/windows/src-tauri
cargo test --lib dcf_model::
cargo test --lib valuation_baseline::
cargo test --lib quant_lens::
```

Optional Android: `scripts/validate-android.ps1` when touching Kotlin engine.

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
| 5 | **JPM** or **ACGL** | Residual income / financial | FCFF on OCF−PPE |
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

## Notes

- Header gap may be **analyst vs market**, not model vs market — do not confuse the two.
- Quant Lens holds full WACC/FCF diagnostics; overview stays high-level.
- Desktop terminal may lag Windows policy (uplift/FCF normalize); it must still **refuse unclassified** rather than invent FCFF.
- **QA = profile `qa`.** Opening/closing full `sp500` is the anti-pattern this profile exists to kill.
