# Handover — Honest path and Street stretch (2026-08-16)

**Audience:** next agent on identity cash, holdout measure, or Street-implied stretch  
**Owner (product):** Juan (single analyst workstation)  
**Handoff type:** BMAD implementation-artifact + project-context pointer (no sprint, no new PRD)  
**Branch:** `valuation/honest-path-and-street-stretch` @ `df0e744b` (clean at handoff)  
**PR:** [#39](https://github.com/Thejuampi/discount_screener/pull/39) against `main`  
**Older motor brief:** [`handover-quant-valuation-engine-2026-08-02.md`](handover-quant-valuation-engine-2026-08-02.md) (CHTR / high-signal / Shapley). Do not replace it.

---

## 0. Read first (order)

| # | Path | Why |
| --- | --- | --- |
| 1 | Root [`AGENTS.md`](../../AGENTS.md) | Model family, honesty modes, `qa` only, merge bar, numerical protocol |
| 2 | [`_bmad-output/project-context.md`](../project-context.md) | Lean AI rules (honesty `/3`, path `/4`, SEC `/11`) |
| 3 | **This file** | Live state of this workstream |
| 4 | [`shared/contracts/street-implied-honesty.json`](../../shared/contracts/street-implied-honesty.json) | Dual-mode contract |
| 5 | [`shared/contracts/sec-driver-normalization.json`](../../shared/contracts/sec-driver-normalization.json) | CapEx + interest classes (`/11`) |
| 6 | [`holdout-book-2026-08-16.json`](holdout-book-2026-08-16.json) | Seeded 20-name holdout (no fit names) |
| 7 | [`docs/operational-anti-patterns.md`](../../docs/operational-anti-patterns.md) | Lease coupon, intangibles, expand-without-franchise, Street-mix, `ape_nh` tautology |
| 8 | [`docs/valuation-live-qa-checklist.md`](../../docs/valuation-live-qa-checklist.md) | Live QA = profile `qa` only |

Do **not** invent PRD or sprint ceremony. Implement with TDD against this file + `AGENTS.md` + contracts.

---

## 1. Battle stance (locked)

Juan ordered two typed modes. Exhaust honest first. Then publish NonHonest as a signal.

| Mode | Role | Rule |
| --- | --- | --- |
| `Honest` | Working identity | Every input is evidence or economics. Forecast consumes this only. |
| `NonHonest` | Parallel typed signal | One-knob inversion vs Street. Label stretch. Never mix Street into honest cash. |

Street is the **scoreboard**. Street is never an honest runtime input and never a forecast mix.

v5 Street-weighted mix is discarded. Price layer is `price-forecast/6-identity-cash`.

**Do not** retune honest path knobs to this holdout’s Street numbers.

**Do not** switch the working mode to NonHonest unless Juan says so.

---

## 2. Git / PR

| Item | Value |
| --- | --- |
| Branch | `valuation/honest-path-and-street-stretch` (tracks origin) |
| Product HEAD | this branch — debt engine, factory plus lender, `valuation-policy/1` |
| Prior | `df0e744b` honest cash + Street stretch; `beac5107` holdout book |
| PR | [#39](https://github.com/Thejuampi/discount_screener/pull/39) |
| Worktree at this brief | dirty product tree lands in the next commit on this PR |

---

## 3. Policy versions (current)

| Layer | Version | Owner |
| --- | --- | --- |
| Engine / model policy | `business-class-policy/35-weak-franchise-secular` plus Android `coupon-resolution/1`, `debt-resolution/1`, and `issuer-market-yield/2` | Android `DcfAnalysisEngine.kt`. Windows string is `/35` and still drops hole years |
| Policy book | `valuation-policy/1` | `shared/contracts/valuation-policy.yaml`. Android reads it. Windows keeps literals |
| Factory plus lender | `component-sotp/2` | Android `ComponentSumValuation`. Mixed factory + finance filings |
| Industry operating path | `industry-operating-path/4` | Auto through-cycle cash margin is 300 bps |
| FCFF path | `valuation-path/4-no-expand-without-franchise` | Android `ValuationPathPolicy` |
| Residual path | `residual-path/4-care-quality-roe` | Android `ResidualPathPolicy` (same file) |
| SEC normalization | `sec-driver-normalization/11` | Contract + generated Android/Windows |
| Sieve | `fcff-intangibles-1` | Android `SecEdgarTimeseriesProvider` |
| Street-implied honesty | `street-implied-honesty/3` | Android `StreetImpliedHonesty` |
| Price forecast | `price-forecast/6-identity-cash` | Android `PriceForecastEngine` |

Windows carries `/35` and the secular 10% + 3/4-year rule. Windows does **not** yet carry `persist_frac`, the expand-without-franchise gate, residual-path `/4`, or the NonHonest stretch UI.

---

## 4. What shipped

### 4.1 Honest cash (evidence / economics)

- **SEC `/11`.** Development CapEx = plant + software + wells + `PaymentsToAcquireIntangibleAssets`. Drop software and intangibles when the tangible tag is `PaymentsToAcquireProductiveAssets`. Generator ran on Android and Windows.
- **Interest.** Drop `FinanceLeaseInterestExpense` (lease subset, not total coupon). Keep `InterestIncomeExpenseNet` / `NonoperatingNet`. Use `abs()` for coupon and coverage. Add `LongTermDebtAndCapitalLeaseObligationsIncludingCurrentMaturities` to total debt.
- **Path `/4`.** Internet retail/content may expand to the industry FCFF margin only when `excessRoe > 0`. DASH (ROE below WACC) no longer expands to 20%.
- **Weak franchise.** `0 ≤ excessRoe < 300` bps. Persist only in that band. Reinvestment needs `capex ≥ 500` when CapEx is passed.
- **Secular.** Recent median growth ≥ 10% and growth in at least 3 of 4 years. One soft year is not a cycle.
- **Bank lift.** Through-cycle floor 13% ROE, but year-1 lift is at most 400 bps.
- **Managed care.** 2000 bps persist only when ROE ≥ 20%. Mid-teens ROE uses 350 bps.
- **Honest refuses.** TTWO: equity wipe / near-zero OCF. GM now uses `component-sotp/2` when the 10-K and the finance subsidiary book are present.
- **Debt engine.** Year-end stock, filed or peer coupon, and `issuer-market-yield/2` for k_d. Estimates stay in year-cash.
- **Factory plus lender.** Parent dimensions plus the finance-arm 10-K. Factory FCFF is NOPAT + D&A − sustaining CapEx.
- **Policy YAML.** Engine knobs live in `shared/contracts/valuation-policy.yaml`.

### 4.2 NonHonest signal (diagnostic, not a second cheat model)

- Types: `ValuationHonesty`, `HonestyKnob`, `ImpliedStretch`, `HonestyTaggedKnob`, `StreetImpliedView`.
- Policy `/3`: invert one knob (WACC/CoE, stable margin, near-term growth, starting ROE). Publish implied bps, delta vs honest, and stretch.
- Stretch bands (bps): DiscountRate 200/500; StableMargin 400/1000; NearTermGrowth 400/1200; StartingRoe 300/800.
- **Aligned clamp.** If `|honest − Street| / Street ≤ 200` bps, force Modest, implied = honest, delta = 0. Stops GOOGL-class near-matches from publishing Absurd WACC. Invert pricer ≠ engine.
- Detail tertiary title: `Non-honest (Street-implied): $stretch · $knob $need vs $honest (delta $delta)`.
- Scoreboard columns: `ape_h`, `ape_nh`, `nh_knob`, `nh_honest`, `nh_implied`, `nh_delta`, `nh_stretch`. Plus `STRETCH,Modest=n,...`.
- **`ape_nh` ≈ 0 is inversion tautology, not a win.**

### 4.3 Measure books

- Fit = Android `qa.txt` 20 names. Gate `DS_WAVE1B=true`. MEAN_APE_HONEST must be ≤ 2/3 of 5% = **3.33%**.
- Holdout = [`holdout-book-2026-08-16.json`](holdout-book-2026-08-16.json). Seed `20260816`. Balanced strata. Excludes the fit set. Gate `DS_HOLDOUT=true`. Measure only. **No fit gate on holdout.**

Caches (local, not product):

- Fit: `.agents/workspace/tmp/e2e/thinkable-identity-qa/build/wave-1b/`
- Holdout: `.agents/workspace/tmp/e2e/thinkable-identity-qa/build/holdout/`

Market params used for both remesures: `rf=470`, `erp=442`, ImpliedIndex, Yahoo TNX, `g_macro=380`.

---

## 5. Last remesured boards (after `/11` + stretch `/3` + aligned clamp)

**Do not treat these dollars as a new conclusion without a remesure.** They are the last printed scoreboard.

### 5.1 Fit (n=20, all priced)

| Metric | Value |
| --- | --- |
| MEAN_APE_HONEST | **0.02151** (gate 0.0333) **green** |
| MEAN_APE_NONHONEST | 0.0047 (aligned names publish honest cents) |
| STRETCH | Modest=20 |

### 5.2 Holdout (n=18 priced, 2 honest refuses)

| Metric | Value |
| --- | --- |
| MEAN_APE_HONEST | **0.4001** |
| MEAN_APE_NONHONEST | ~0.0004 (tautology) |
| STRETCH | Modest=6, Stretched=4, Absurd=8, Unreachable=0 |

| Symbol | Street | Identity | ape_h | Stretch | Knob | Honest → implied (bps) |
| --- | ---: | ---: | ---: | --- | --- | --- |
| PFE | 28.00 | 30.29 | 0.082 | Modest | StableMargin | 1400 → 1418 |
| TGT | 146.50 | 171.40 | 0.170 | Modest | DiscountRate | 784 → 855 |
| CHD | 107.00 | 129.39 | 0.209 | Modest | StableMargin | 1621 → 1359 |
| PNC | 279.00 | 231.74 | 0.169 | Modest | StartingRoe | 1098 → 1226 |
| C | 157.50 | 127.43 | 0.191 | Modest | DiscountRate | 927 → 827 |
| ELV | 457.00 | 359.56 | 0.213 | Modest | DiscountRate | 806 → 641 |
| CMCSA | 29.40 | 61.34 | 1.086 | Stretched | StableMargin | 1306 → 814 |
| DELL | 500.00 | 144.19 | 0.712 | Stretched | NearTermGrowth | 458 → 1439 |
| EOG | 156.00 | 135.10 | 0.134 | Stretched | NearTermGrowth | 899 → 1569 |
| HUM | 415.00 | 264.67 | 0.362 | Stretched | DiscountRate | 821 → 566 |
| DASH | 255.00 | 126.67 | 0.503 | Absurd | StableMargin | 1654 → 3398 |
| WDAY | 170.00 | 222.93 | 0.311 | Absurd | NearTermGrowth | 838 → −399 |
| AMAT | 650.00 | 135.24 | 0.792 | Absurd | DiscountRate | 1139 → 519 |
| BIIB | 238.50 | 132.84 | 0.443 | Absurd | NearTermGrowth | −140 → 2527 |
| CPAY | 454.50 | 834.06 | 0.835 | Absurd | StableMargin | 3200 → 2094 |
| EA | 210.00 | 127.08 | 0.395 | Absurd | StableMargin | 2702 → 4495 |
| LOW | 262.00 | 161.08 | 0.385 | Absurd | NearTermGrowth | 158 → 2179 |
| CB | 369.00 | 292.40 | 0.208 | Absurd | StartingRoe | 1584 → 3032 |
| TTWO | 290.00 | — | — | refuse | — | equity wipe after net debt |
| GM | 101.00 | — | — | refuse | — | no aligned yield / spread / SEC interest·debt |

CMCSA $61.34 vs Street $29.4 is textbook ~13.1% FCFF at ~6.4% WACC. Street wants ~8.1% margin (Stretched). That is not a missing-cash bug.

LOW moved $148 → $161 after the lease-subset coupon drop. 2023–2026 net interest is in.

DASH stays $126.67 vs $255 with no expand (ROE below WACC). Correct.

CPAY identity printed $827.6 then $834.06 across remesures with no identity-code change between some runs. Treat as cache/overwrite uncertainty. Do not cite the drift as a finding.

---

## 6. Honest extraction is exhausted on this holdout

Remaining gaps are **Street vs identity**, not missing cash.

| Name | Why Street sits apart | Next honest move? |
| --- | --- | --- |
| CMCSA | Street wants a thinner margin than reported FCFF | No. Identity is the 10-K cash. |
| AMAT / DELL | Street prices an AI multiple / growth the books do not show | No. Do not raise g or cut WACC to Street. |
| CPAY | Yahoo Software-Infrastructure fade (3200). A payments remap would **raise** value further | No ticker remap. |
| DASH | No franchise to expand margin | No. Path `/4` is correct. |
| LOW | Growth 158 bps vs Street ~22% implied | No. Coupon is now honest. |
| GM / TTWO | Honest refuse | Keep refuse + reason. Do not invent interest or book. |

If a new evidence hole appears (wrong qname, missing statement class, sign error), fix the **equivalence class**. Do not add a ticker `if`.

---

## 7. Hard constraints (do not violate)

1. Street is never an honest input, never a forecast mix, never a path knob target.
2. Closed-world classifier. Unknown sector/industry → refuse. Never silent FCFF.
3. Financials stay residual income. Never FCFF-on-float (ACGL/CI class).
4. `InterestPaidNet` is cash paid, not expense. `FinanceLeaseInterestExpense` is the lease slice, not the coupon.
5. No naked `sum / n`. Use `valuation_core::robust_mean` / Android `RobustCentre`.
6. No ticker tweaks. No “value near Street” accept tests.
7. Fit gate stays **honest-only**. Do not retune honest knobs to holdout Street.
8. `ape_nh` ≈ 0 is not a win.
9. Live QA = profile `qa` only. Never `sp500` for agent QA.
10. Single-name green is not a merge. Windows: `dcf_model::` + `valuation_baseline::`.
11. A field the engine writes needs a reader. Grep `src/main` before you call it shipped.
12. Do not claim valuation done until live `qa` after identity-visible CapEx/interest (still due).

---

## 8. Open work (priority)

| # | Item | Status | Notes |
| --- | --- | --- | --- |
| P0 | Live QA `qa` after CapEx/interest | **Done on Android** | [`live-qa-android-honest-path-2026-08-16.md`](live-qa-android-honest-path-2026-08-16.md). Checklist 1–6 PASS. AAPL remesure: SEC FCFF $343, red coupon caveat, Street primary on a 12361 bps fan. |
| P1 | Windows lag | **Deferred by Juan 2026-08-16** | Do not port persist_frac / path `/4` / NonHonest UI until he asks. |
| P2 | NonHonest signal quality | Ready, not working mode | Stretch is published. Next NonHonest work is diagnostic (how far Street sits), not a multi-knob Street mix. Wait for Juan. |
| P3 | CPAY industry class | Do not ticker-tweak | Payments vs Software-Infrastructure is a classifier table change with tests, or leave it. |
| P4 | 2026-08-02 motor (high-signal 26/26, CoE function) | Separate | Use the older handover. Do not mix into this slice. |

Juan’s last product order: Android is the live QA surface. Windows comes next. Do not port the model to Windows in this slice.

---

## 9. Commands

Work from the owning tree. `./gradlew` from repo root fails. `cargo` for Windows from repo root fails.

```text
# Android core (from apps/android). One --rerun per task.
Set-Location apps/android
./gradlew :core:test --rerun :app:testDebugUnitTest --rerun

# Fit remesure + gate
$env:DS_WAVE1B = "true"
./gradlew :core:test --tests com.discountscreener.core.engine.ThinkableIdentityWave1bMeasureTest --rerun

# Holdout remesure (measure only; no APE gate)
$env:DS_HOLDOUT = "true"
./gradlew :core:test --tests com.discountscreener.core.engine.ThinkableIdentityWave1bMeasureTest --rerun

# Prefetch (only if caches are missing)
./gradlew :core:test --tests com.discountscreener.core.engine.ThinkableIdentityWave1bPrefetchTest --rerun
./gradlew :core:test --tests com.discountscreener.core.engine.ThinkableIdentityHoldoutPrefetchTest --rerun

# Windows merge bar (from apps/windows/src-tauri)
Set-Location apps/windows/src-tauri
cargo test --lib dcf_model::
cargo test --lib valuation_baseline::
cargo fmt

# Live QA this slice = Android profile qa. Never full sp500. Do not pm clear.
make android-run-qa
# Windows live QA waits for the later port. Do not launch tauri:dev:qa for this work.
```

SEC policy generator (after contract edits): `-ExecutionPolicy Bypass` on the project script. Then remesure.

Print last holdout board: `.agents/workspace/tmp/print_stretch_board.py` (local helper, not product).

---

## 10. Module map

| Concern | Path |
| --- | --- |
| Honesty types | `apps/android/core/.../model/ValuationHonesty.kt` |
| Street invert + stretch | `apps/android/core/.../engine/StreetImpliedHonesty.kt` |
| Dual scoreboard | `apps/android/core/.../engine/StreetScoreboard.kt` |
| Measure / prefetch | `apps/android/core/src/test/.../ThinkableIdentityWave1bMeasureTest.kt` |
| Path + residual path | `apps/android/core/.../engine/ValuationPathPolicy.kt` |
| Engine `/35` | `apps/android/core/.../engine/DcfAnalysisEngine.kt` |
| SEC policy + generated | `SecDriverNormalizationPolicy.kt`, `SecDriverNormalizationPolicyGenerated.kt` |
| Interest / coupon | `DriverResolution.kt` (`abs` on net) |
| Sieve | `apps/android/app/.../SecEdgarTimeseriesProvider.kt` (`fcff-intangibles-1`) |
| Detail NonHonest title | `apps/android/app/.../ValuationJudgmentPresentation.kt` |
| Assembler | `ValuationJudgmentAssembler.kt` |
| Price forecast v6 | `PriceForecastEngine.kt` |
| Windows FCFF/WACC | `apps/windows/src-tauri/src/dcf_model.rs` (version `/35`; path `/4` not ported) |
| Windows SEC `/11` | `sec_normalization.rs`, `sec_driver_normalization_policy_generated.rs` |
| Windows coupon `abs` | `driver_resolution.rs` |
| Contracts | `shared/contracts/street-implied-honesty.json`, `sec-driver-normalization.json`, `valuation-model-family.json` |
| Holdout book | `_bmad-output/implementation-artifacts/holdout-book-2026-08-16.json` |

---

## 11. First 15 minutes for the next session

1. Read this file. Do not reload the compacted chat.
2. `git status` / `git log -1`. Expect `df0e744b` plus a BMAD-only commit if this brief is committed.
3. Confirm you will **not** retune CMCSA / AMAT / DELL / CPAY / DASH to Street.
4. If Juan wants product next: run **P0 live QA** on `qa`. If Juan wants NonHonest next: wait for a clear order, then improve signal quality, not a Street mix.
5. Remesure only after an identity-visible change. Cite the new CSV, not this table.

---

## 12. Artifact index

| Artifact | Role |
| --- | --- |
| This file | Continuity for honest path + Street stretch |
| [`handover-quant-valuation-engine-2026-08-02.md`](handover-quant-valuation-engine-2026-08-02.md) | Older Windows motor (CHTR P0 later shipped as policy/16; high-signal 16/26 still open there) |
| [`holdout-book-2026-08-16.json`](holdout-book-2026-08-16.json) | Holdout membership |
| [`spec-valuation-judgment-core-2026-08-15.md`](spec-valuation-judgment-core-2026-08-15.md) | Stance object above identity |
| [`valuation-multi-name-baseline-policy.md`](valuation-multi-name-baseline-policy.md) | Windows merge bar |
| [`deferred-work.md`](deferred-work.md) | Cross-cutting tickets |

---

## 13. Session facts the next agent must not rediscover

- Dual-error board exists. Fit gate is honest-only.
- Honest extraction on **this** holdout is exhausted. Next honest fix needs a new evidence hole, not a Street miss.
- Aligned APE ≤ 200 bps must stay Modest + implied=honest. Invert pricer is a subset of the engine.
- Gradle `--rerun` once for two tasks leaves the first UP-TO-DATE. Write `--rerun` once per task.
- `./gradlew` and Windows `cargo test` need their own directories.
- CPAY dollar drift across remesures without a code change is not a finding.
- Do not add R&D expense or `CapitalizedComputerSoftwareAdditions` as development CapEx.
- Payment networks (V, MA) stay FCFF. Lenders stay residual.
