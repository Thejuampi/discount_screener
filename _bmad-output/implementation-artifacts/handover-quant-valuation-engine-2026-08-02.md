# Handover — Quant / Valuation Engine (2026-08-02)

**Audience:** next agent continuing quant/valuation motor work  
**Owner (product):** Juan (single analyst workstation)  
**Handoff type:** BMAD implementation-artifact + project-context update (no sprint ceremony)  
**Branch context (at handoff):** `valuation/wave1-integration` with uncommitted valuation/attribution work — verify `git status` before editing  

---

## 0. Read first (order)

| # | Doc / path | Why |
| --- | --- | --- |
| 1 | Root [`Agents.md`](../../Agents.md) | Valuation model family, Quant Lens, **qa profile only**, multi-name merge bar, **numerical conclusion protocol**, anti-patterns |
| 2 | [`_bmad-output/project-context.md`](../project-context.md) | Lean AI rules (updated this handoff) |
| 3 | **This file** | Live state of the motor workstream |
| 4 | [`valuation-multi-name-baseline-policy.md`](valuation-multi-name-baseline-policy.md) | Merge bar + high-signal cohort |
| 5 | [`shared/contracts/README.md`](../../shared/contracts/README.md) | Contracts including gap-attribution + high-signal |
| 6 | [`docs/valuation-live-qa-checklist.md`](../../docs/valuation-live-qa-checklist.md) | Live QA = **`npm run tauri:dev:qa` only** |

Do **not** invent PRD/sprint ceremony unless Juan asks. Prefer Direct/Express implementation with TDD + durable artifacts.

---

## 1. Product goal (unchanged)

Earn **usable high-signal forecasts** for the live screener surface:

- Solid rates/drivers (not provisional forever)
- Correct business-class routing (closed world)
- Scale-coherent vs market (OOM band as **quality**, never clamp)
- Street = **diagnostic only** (disagreement band / reporting) — **never** clamp, reverse-engineer rates, or optimand
- No `if ticker == "X"` hacks; multi-name robustness

**High-signal gate (integration):**  
`cargo test --lib valuation_high_signal::` from `apps/windows/src-tauri`  
Contract: `shared/contracts/valuation-high-signal-screener-cohort-v1.json`  
Last known progress: **~16/26 green** (recompute; not frozen soft snapshot). Do not declare done until 26/26 without clamps.

---

## 2. What was shipped in this workstream (code)

### 2.1 Rates / solid CoE path

- Live US 10Y → `MarketParams::from_live_risk_free` on demand valuation / high-signal recompute
- Industry beta policy `/2` with through-cycle shrink + leverage β floor (extreme D/E)
- FEM / forward candidate quality solid when CoE non-provisional

### 2.2 Operating router / projection

- Under structural distortion, prefer solid forward; soft FCFF does not veto solid forward — **but material candidate disagreement (> `DISPUTED_DIFFERENCE_BPS`) is still a refusal, not a preference.** An earlier round let quality override the dispute; that took the gate 16 → 18/26 by silencing 11 disputed names. Reverted. See `valuation-agent-failure-modes.md` §1.
- The forward lane keeps its distortion-only mandate. Quality does **not** promote it across the undistorted cohort.
- Fade shortened only for through-cycle / extreme leverage (not all compounders)
- Semiconductor hold years; scale-gap distortion → thin-margin routing pressure
- Runtime policy / router versions bumped in code — check `operating_valuation_runtime.rs`, `operating_valuation.rs`, contracts for current strings

### 2.3 Gap attribution waterfall (telemetry)

| Item | Location |
| --- | --- |
| Module | `apps/windows/src-tauri/src/valuation_gap_attribution.rs` |
| Contract | `shared/contracts/valuation-gap-attribution-v1.json` |
| Fixture capture | `apps/windows/src-tauri/tests/fixtures/valuation/gap_attribution_diagnostic_cohort.json` |
| Tests | `cargo test --lib valuation_gap_attribution::` |

**Method:** **Shapley** over `{rates, horizon, path, g_terminal}` (16 coalitions).  
**Factor baselines:** **own policy only** (`policy_own`) — unit-β CoE, hold=0/fade=10, near_growth=0, g_terminal defaults.  
**Never:** Street reverse-engineered neutrals.  
**Street fields:** `diagnostic_gap_vs_street_*` only; build fails if asserts use them as accept/optimand (`street_diagnostic_only_enforcement_scan`).

**Diagnostic cohort (fixed):** CHTR, T, MPWR, WDC, GOOGL (+ ancla GOOGL).

```text
cargo test --lib valuation_gap_attribution::tests::live_attribution_diagnostic_cohort -- --ignored --nocapture
cargo test --lib valuation_gap_attribution::tests::live_fcff_driver_audit_cohort -- --ignored --nocapture
```

### 2.4 Naive FCFF method column (corrected mechanics)

Earlier bug: capitalized FCFF/share at **CoE** without net debt → invalid.  
**Current:** firm FCFF discounted at **WACC**, **EV − net_debt**, ÷ shares.  
APIs in `dcf_model.rs`:

- `extract_normalized_fcff_level` — run-rate **without** bear/base/bull ordering (unblocks CHTR full-scenario failure)
- `resolve_attribution_wacc` — WACC with optional unit-β CoE inside
- `equity_cents_from_fcff_run_rate` — WACC + net-debt bridge

Audit fields on report: `naive_fcff_discount_rate_kind=wacc`, `naive_fcff_subtracted_net_debt=true`.

---

## 3. Empirical findings (validated — keep)

### 3.1 Horizon factor inert on most diagnostic names

| Symbol | hold/fade active | match baseline? | attr_horizon |
| --- | --- | --- | --- |
| CHTR, T, WDC, GOOGL | 0 / 10 | yes | $0 |
| MPWR | hold=3 (semi) | no | non-zero |

**Withdrawn hypothesis:** “long soft fade explains CHTR/T/WDC/GOOGL gap.” Settings already equal baseline.

### 3.2 Overvalued is not one mechanism

Gap composition `gap_tot = v_active − Street`; `%_base = (v_baseline−Street)/gap_tot`:

| Symbol | % gap in baseline (EPS@unit-β) | % from active policy |
| --- | ---: | ---: |
| **CHTR** | ~**76%** | ~24% |
| **T** | ~**25%** | ~**75%** |

→ Treat CHTR (baseline-level / cash-flow definition) and T (rates+path policy) **separately**. Do not generalize CHTR→“overvalued cluster.”

### 3.3 CHTR FCFF/share $140 — **RESOLVED by policy/16** (was BLOCKER)

> **Status 2026-08-02 (later session):** fixed. `maintenance = κ × δ/(δ+g)` replaced the
> profitability-linked cap. CHTR now prints **$69.71/sh** (`owner_earnings=false`,
> base margin 1518 bps ≈ the reported annual identity) and the audit sniff reports
> **PASS**. The historical diagnosis below is retained because it explains *why* the
> old path was wrong. See §4 P0 (done) and §12.



| Field | Value |
| --- | --- |
| Engine FCFF total | **$16.766B** |
| Shares used | **119,277,492** |
| Engine FCFF/sh | **$140.57** |
| External TTM ~ | ~$31.66/sh |
| Research post-capex ~ | ~$61–69/sh |

**Share count is clean** (Yahoo SO ~119.28M; Q2’26 diluted WAS ~121M).  
**Inflation is in the numerador** (~4.4×): owner-earnings path.

| CHTR driver (norm) | bps | % rev |
| --- | ---: | ---: |
| OCF margin | 2763 | 27.6% |
| Gross CapEx intensity | 2035 | 20.4% |
| **Maintenance CapEx (engine)** | **414** | **4.1%** |
| Interest add-back | 712 | 7.1% |
| **Owner-earnings base margin** | **3061** | **30.6%** |
| Annual reported FCFF margin (median nonneg) | 1478 | 14.8% |

Mechanism: `investment_wave` + maint = `min(hist CapEx p25, 15% of OCF margin)` → ~4% rev vs reported CapEx ~20% rev.  
Reported 2025 FCFF ~$8.3B ≈ **~$70/sh** (near optimistic external band); OE path doubles it.

**Do not re-run gap-attribution section 3 / close EPS-vs-FCFF for CHTR until FCFF/sh is ~$30–70.**

Contraste (driver only — not a cluster conclusion):

| | CHTR | T | WDC |
| --- | ---: | ---: | ---: |
| OE base / reported margin | ~2.1× | ~1.7× | ~0.9× |
| Gross CapEx % | ~20% | ~15% | ~6% |
| Maint CapEx % | ~4% | ~4% | ~2% |

---

### 3.4 Reporting-basis break (WDC) — **RESOLVED 2026-08-03**

WDC printed $1.92 FCFF/sh against a reported $4.38. Root cause was not the
maintenance-CapEx calibration: the driver history mixed two reporting bases.
After the SanDisk separation the FY2025 10-K restated FY2023/FY2024 revenue to
continuing operations (12.318B → 6.255B, 13.003B → 6.317B) while ASC 205-20 left
the cash-flow statement whole-company. The 1061 bps median OCF margin therefore
pooled two different issuers.

`restated_years` (`edgar.rs`) now reports, per driver, which fiscal years a later
filing materially revised. A year with restated revenue and unrestated OCF is a
`reporting_basis_broken` point; `driver_model_inputs` drops it and every earlier
year. WDC keeps one clean year (2025), below the three-point minimum, so **FCFF
is now Unavailable** and WDC routes to the forward lane on an honest reason
rather than printing a number built from two companies.

Anchors unmoved (AAPL $7.01, GOOGL $20.54, AMZN $6.11, MSFT $18.86 — all
byte-identical; none has a restated year). T improved as a side effect of its
2021 WarnerMedia restatement: $3.57 → $3.61 against a reported $3.70.

### 3.5 AAPL silent history truncation — **RESOLVED 2026-08-03**

AAPL's driver history ended at FY2023 while every other driver ran to FY2025.
Cause: `driver_model_inputs` dropped any year lacking interest + effective tax,
and Apple stopped disclosing interest separately from FY2024 (every interest
concept it files ends 2023). The engine presented a two-year-old level as current.

The FCFF bridge fields are now `Option` per aligned year. A year without interest
contributes revenue growth and OCF / CapEx margins, and is absent from the
scenario distribution and the interest add-back — nothing is defaulted. AAPL now
reads FY2025: revenue $383.29B → **$416.16B**, own trend 551 → **643 bps**, FCFF/sh
$7.01 → **$7.61**. All other names byte-identical. See failure modes §12.

### 3.6 Anchor set — AAPL replaced by PG (2026-08-03)

Anchors are **PG, GOOGL, AMZN, MSFT**. AAPL is out: a 10% single-session drawdown
makes its street target a poor validation target, and calibrating a model against
that volatility is backwards. PG fills the same structural seat — the only anchor
with `owner_earnings = false` and engine level equal to reported ($6.35 vs $6.34),
so it controls for the owner-earnings adjustment the other three all receive. It
is also uncontaminated (no acquisition years) and non-tech, which de-correlates a
set that was otherwise all megacap tech. CSCO was the runner-up (Splunk 2024
contamination); JNJ and HD were rejected — JNJ's own trend is the refusal
sentinel, HD's engine/reported ratio is 1.13.

### 3.7 Near-term growth blend — **PROPOSED, NOT SHIPPED**

`resolve_near_growth` blends the **revenue leg** of consensus toward
`DcfAnalysis::base_growth_bps`, weighted 67/33 when the two agree and decaying to
33/67 when consensus has fully departed from the company's own history — the same
endpoints `industry-beta-policy-v1.json` uses for beta. The earnings leg is
untouched and the two recombine. Production still reads `legacy_capped_bps`.

Cohort effect: `median |value/street - 1|` 0.319 → **0.258**, band 10/25 → 11/25,
10 names up and 7 down (no level shift). Anchors: PG **-0.7%**, MSFT **-2.4%**,
AMZN **-2.8%** — all inside ±5%. **GOOGL -7.2% is the one breach** and the only
open decision: consensus puts GOOGL revenue growth at 21.7% against a 13.9%
five-year median, and the blend lands at 17.8%. That is genuine like-for-like
disagreement, not an artefact.

Two defects were found and fixed inside this proposal before it was measured —
reading the `acquisition_normalized` refusal sentinel as a zero trend (§10) and
comparing a revenue-and-earnings consensus against a revenue-only trend (§13).

---
## 4. Open work (priority order for next agent)

### P0 — Unblock honest FCFF run-rate (cable / high structural CapEx) — **DONE (policy/16)**

**Was:** Owner-earnings maintenance CapEx understated network-maintenance CapEx for CHTR-class (and T).  
**Acceptance met:** CHTR normalized FCFF/sh **$69.71** (band ~$30–70), audit sniff **PASS**.  
**Shipped:** see §12. No ticker special-cases; AMZN OE path retained.

### P1 — Re-open method diagnostic — **UNBLOCKED**

- Re-run attribution cohort section 3 with WACC+net-debt column  
- Answer EPS-vs-FCFF for CHTR with clean input  
- Keep T as **policy-driven** overvalue (rates+path), separate workstream

### P2 — Continuous CoE risk function (process redesign item 2)

Replace ad-hoc sector patches with:

`CoE = rf + β_shrunk×ERP + f(net leverage / EBITDA) + f(driver volatility)`

Generalize oil/leverage work; no industry hardcode prizes.

### P3 — Path / EPS≠distributable cash (after run-rate honesty)

Bridge OCF → maintenance CapEx → FCFF explicit; EPS/growth for **revenue trajectory only**, not capitalizable proxy.

### P4 — CapEx cycle regime classifier (MPWR/WDC under)

7–10y window + trough/mid/peak vs own CapEx/Revenue history; weight mid-cycle.  
**May need PIT store later** — flag explicitly; do not assume it exists.

### P5 — Empirical fade / PIT backtest

**Separate project.** Primary calibration = realized drivers point-in-time, not Street.  
No PIT store today → do not block P0–P3 on it.

### P6 — High-signal 26/26

Continue engine fixes that earn green without Street clamps; recompute gate remains the acceptance.

### P7 — Policy version governance

Already partial (policy version strings + baseline suite). Harden: every CoE/fade/capex policy bump → mandatory `valuation_baseline` + `dcf_model` + attribution identity tests.

---

## 5. Process redesign (agreed principles — not fully implemented)

Juan’s ordered process (calibrate **policies as statistical models**, not “fix ticker X”):

1. Attribution waterfall (Shapley) — **MVP done**  
2. Continuous CoE — **open**  
3. Evidence fade — **blocked on PIT (separate)**  
4. FCFF ≠ EPS capitalizado — **blocked on honest FCFF run-rate (P0)**  
5. CapEx cycle regime — **open**  
6. PIT driver backtest as primary validation — **separate project**  
7. Version governance — **partial**

Full process doc `valuation-policy-calibration-process.md` was **deferred** until attribution schema validated — after P0, write it from this handoff + contract, not before.

---

## 6. Numerical conclusion protocol (mandatory)

From `Agents.md` — before presenting $ figures as **conclusions**:

1. Sniff vs 10-K/10-Q or known OOM (~50% band)  
2. No one-name → cluster  
3. Declare baseline before experiment  
4. Dubious inputs = pending, not “clean evidence”

---

## 7. Verification commands

```text
# From apps/windows/src-tauri
cargo test --lib dcf_model::
cargo test --lib valuation_baseline::
cargo test --lib valuation_gap_attribution::
cargo test --lib valuation_high_signal::     # goal gate; may fail until 26/26
cargo test --lib quant_lens::                # if EV/lens touched
cargo fmt

# Live QA Windows (never full SP500 for agent QA)
cd apps/windows && npm run tauri:dev:qa
```

---

## 8. Key modules map

| Concern | Path |
| --- | --- |
| FCFF / WACC / CoE / owner earnings | `apps/windows/src-tauri/src/dcf_model.rs` |
| Operating router | `apps/windows/src-tauri/src/operating_valuation.rs` |
| Runtime orchestration | `apps/windows/src-tauri/src/operating_valuation_runtime.rs` |
| Quant Lens | `apps/windows/src-tauri/src/quant_lens.rs` |
| High-signal recompute gate | `apps/windows/src-tauri/src/valuation_high_signal.rs` |
| Gap attribution | `apps/windows/src-tauri/src/valuation_gap_attribution.rs` |
| SEC drivers | `apps/windows/src-tauri/src/edgar.rs`, `sec_normalization.rs` |
| Industry beta policy | `shared/contracts/industry-beta-policy-v1.json` |
| High-signal contract | `shared/contracts/valuation-high-signal-screener-cohort-v1.json` |
| Attribution contract | `shared/contracts/valuation-gap-attribution-v1.json` |

---

## 9. Explicit non-goals / forbidden

- Clamp intrinsic to Street or price  
- Accept tests that minimize `diagnostic_gap_vs_street_*`  
- `if symbol == "CHTR"`  
- Full SP500 cold-start for agent QA  
- Silent FCFF for financials / unclassified  
- Declaring EPS-vs-FCFF settled for CHTR while FCFF/sh fails sniff  
- Assuming PIT store exists  

---

## 10. Suggested first session for next agent

1. Read this file + `Agents.md` numerical protocol.  
2. Reproduce CHTR driver audit:  
   `cargo test --lib valuation_gap_attribution::tests::live_fcff_driver_audit_cohort -- --ignored --nocapture`  
3. Design **policy-level** fix for maintenance CapEx under structural high CapEx (cable/telco) without ticker hacks; TDD on synthetic OCF/CapEx series.  
4. Confirm CHTR FCFF/sh enters ~$30–70; then re-run attribution section 3.  
5. Update this handoff “Open work” status and policy version notes when shipping.

---

## 11. Artifact index

| Artifact | Role |
| --- | --- |
| This handoff | Continuity for quant motor |
| `project-context.md` | Standing AI rules (updated) |
| `valuation-multi-name-baseline-policy.md` | Merge bars |
| `fix-amzn-owner-earnings-vs-street-2026-08-01.md` | Why OE path exists (AMZN) — do not blindly undo |
| `retro-valuation-calibration-session-2026-07-30.md` | Prior calibration lessons |
| `deferred-work.md` | Cross-cutting deferred items (attribution/OE appended) |

---

## 12. P0 shipped — policy/16 growth-earned sustaining CapEx (2026-08-02, later session)

### 12.1 The defect

Policy/15 set `maintenance = min(historical CapEx p25, 15% of OCF margin)`, floor 2% of
revenue. The OCF term made **sustaining CapEx a function of profitability**: a cable
network at 27.6% OCF margin was charged 4.1% of revenue to renew plant it actually
reinvests 20.4% of revenue into. It was also **circular** — `investment_wave` fires when
`latest_capex ≥ maintenance + 500`, and a maintenance figure that low made *every* name
in the diagnostic cohort an "investment wave" (all six printed `owner_earnings=true`).

### 12.2 The policy

Steady-state capital identity `κ = c·(δ + g)` ⇒ sustaining share is `δ/(δ+g)`:

```text
maintenance_bps = clamp( κ × δ / (δ + max(g, 0)),  min(200, κ),  κ )
κ = normalized_capex_intensity_bps      g = base_growth_bps (near-term revenue growth)
δ = ASSET_RENEWAL_RATE_BPS = 1000       (~10-year average productive asset life)
```

Growth CapEx must be **earned by revenue growth**. Flat-growth issuers keep essentially
all of their CapEx as sustaining, so they stop qualifying as investment waves and fall
back to the reported annual FCFF identity. Genuine compounders keep owner earnings.
`base_growth_bps` is now computed *before* the base-margin block; `historical_capex_p25`
is gone.

### 12.3 Live cohort (recomputed, `live_fcff_driver_audit_cohort`)

| Symbol | FCFF/sh before | FCFF/sh after | OE | maint bps | 2025 reported FCFF/sh |
| --- | ---: | ---: | --- | ---: | ---: |
| **CHTR** | $140.57 | **$69.71** ✅ | no | — | $69.7 |
| **T** | $5.23 | **$3.57** | no | — | $3.70 |
| WDC | $2.95 | $1.92 | yes | 573 | $4.38 |
| MPWR | $15.35 | $16.41 | yes | 200 | $14.0 |
| GOOGL | $20.11 | $20.54 | yes | 452 | $12.6 |
| AMZN | — | $6.11 | yes | 595 | $0.72 |

The two flat-growth, high-CapEx networks now take the reported identity; the four
growing/investing names keep owner earnings. AMZN was **added to the audit cohort** so
maintenance-CapEx policy is always read against both ends of the range.

### 12.4 Gates

| Gate | Result |
| --- | --- |
| `cargo test --lib dcf_model::` | 46/46 green |
| `cargo test --lib valuation_baseline::` | 10/10 green |
| `cargo test --lib` (Windows) | 481 pass / 4 fail — all 4 pre-existing (proven, §12.5) |
| `scripts/validate-android.ps1` | 3 fail — all pre-existing (proven, §12.5) |
| `valuation_high_signal::` | **16/26**, unchanged from handoff level |
| `cargo fmt` | clean |

Android `DcfAnalysisEngine.kt` mirrors the policy (`maintenanceCapexIntensityBps`).

### 12.5 Judgment calls to review

1. **AMZN `valuation_baseline` thresholds lowered** (`base ≥ $100 → $75`,
   `run ≥ $50B → $45B`). Policy/15 floored AMZN sustaining CapEx at ~2.0% of revenue
   against a ~$300B gross asset base and ~$60B annual D&A — that was too low, so AMZN's
   value legitimately falls (base $88.40). The fixture is *also* stricter than live AMZN:
   its six-year window makes 2025 look like a CapEx spike, so normalized OCF margin is
   12.32% there vs **14.78%** on the full SEC history (live run-rate $65.7B, base > $100).
   Consider extending that fixture to live history depth rather than keeping the lower bar.

   > **REVERTED 2026-08-02 (forward-lane session).** Both thresholds are back at
   > `base ≥ 10_000` / `run ≥ 50_000_000_000`. Lowering a bar so a change passes is the
   > failure mode this workstream is explicitly correcting, so the hack does not stand.
   > `baseline_megacap_amzn_class_not_penny_intrinsic` is therefore **red**, and it fails
   > on both assertions by a narrow margin:
   >
   > ```text
   > amzn_baseline base_cents=8840 run_rate=49252678800 wacc=1016 growth=1183
   > ```
   >
   > $88.40 vs the $100.00 bar; $49.25B vs the $50.00B bar. This is **policy/16 backlog,
   > owned by the FCFF lane — not a blocker for the forward-lane work in §13**, which
   > does not touch it. The fix is the one already identified above: extend the fixture to
   > live SEC history depth so its OCF margin stops being a six-year artifact. Do not
   > re-lower the thresholds.
2. **T contract golden** `fcfRunRateDollars` 39.04B → **24.65B**. Hand-verified: the new
   value is exactly the reported FCFF identity (OCF 31.5% + after-tax interest 4.3% −
   actual CapEx 16.4% on $125.6B revenue). The old value charged a national telecom
   network 4.3% of revenue to maintain its network.
3. **Pre-existing failures, not caused by this change** (each proven by re-running with
   the change reverted/stashed): `engine::…operating_route_is_atomic…`,
   `operating_valuation_runtime::…material_candidate_disagreement…`,
   `quant_lens::…operating_candidate_dispute…` (all three `Selected` vs expected
   `Disputed`), and Android `ContractFixtureTest:135`, `DcfAnalysisEngineTest:286`,
   `OperatingValuationTest:128`. These sit in wave1 files that were already dirty.

### 12.6 Known follow-ups (not P0, deliberately not bundled)

- **Median OCF margin lags a monotone margin expansion.** AMZN's OCF margin rose
  9.1 → 14.8 → 18.2 → 19.5%; a median of that window returns a three-year-old margin.
  Policy/15 masked it with an absurdly low maintenance figure; /16 exposes it.
- **The CapEx-spike filter drops a whole year's OCF evidence**, not just its CapEx, and
  it is history-length sensitive (2025 is a spike on a 6-year AMZN window, not on 16).
- **`investment_wave` latches on any lifetime CapEx spike**, so a spike from a decade ago
  still asserts a *current* wave. Consider scoping to the recent window.

---
## 14. Four visibly-wrong cards, diagnosed one by one (2026-08-03)

User pointed at four cards whose numbers were plainly wrong: COF, MPWR, FIS, HPE.
Diagnostic entry point: `valuation_gap_attribution::tests::live_off_name_lane_audit`
(`--ignored --nocapture`) prints both lanes, their inputs, and the route decision
side by side for a symbol list. Raw-source probes:
`edgar::tests::probe_investing_outflows` (every filed CapEx-class concept, plus an
old-vs-new CapEx column) and `fetcher::list_ready_tests::probe_forward_eps_ladder`
(the whole `earningsTrend` ladder with period end dates).

**One of the four was a data defect and is fixed. The other three are policy
decisions, measured and left for the owner.**

### 14.1 FIS — SHIPPED. CapEx policy could not see capitalized software

FIS reinvests through software, not plant: FY2025 `PaymentsForSoftware` $0.835B
against `PaymentsToAcquirePropertyPlantAndEquipment` $0.154B. The `development`
concept list had no software concept, so the engine read CapEx as 1.17% of
revenue. For FY2014-2021 FIS filed no tangible CapEx fact at all and those holes
were imputed, truncating usable history to four years.

Fix: `developmentSoftware` component class summed with the tangible one, dropped
when the tangible selection is `PaymentsToAcquireProductiveAssets` (us-gaap
defines that element as covering PP&E, **software** and other intangibles, so
summing double counts). Contract `sec-driver-normalization/6`; FIS and INTU added
to the frozen fixture corpus, which now pins the summed total, not only per-fact
evidence states.

| FIS FY2025 | before | after |
|---|---|---|
| CapEx | $0.154B (1.17% of revenue) | **$0.989B (8.07%)** |
| FCFF | $2.757B ($5.33/sh) | **$1.922B ($3.72/sh)** |
| OCF − CapEx | $2.46B | **$1.61B** |
| usable driver history | 4 years | **10 years** |
| FCFF lane value | $131.91 | **$88.19** |
| forward lane value | $103.16 | **$90.11** |
| lane disagreement | 2446 bps | **215 bps** |

Sniff anchor and tolerance, stated explicitly: FIS's own reported adjusted free
cash flow, ~$1.3-1.5B. The comparable engine quantity is OCF − CapEx (no interest
add-back). Before $2.46B = 1.6-1.9x the anchor → **FAIL**. After $1.61B = 1.07-1.24x
→ **PASS** within a ±25% band, deliberately looser than the ±5% anchor rule because
"adjusted free cash flow" is a company-defined non-GAAP measure, not an identity.

Corroboration that is not the anchor: two structurally independent lanes converged
from 2446 bps apart to 215 bps.

Blast radius, measured rather than argued — tangible-only selection vs summed total
for all 26 cohort names plus the four anchors: **28 unchanged, only FIS and INTU
moved.** PG / GOOGL / AMZN / MSFT byte-identical. INTU $0.084B → $0.124B (0.44% →
0.65% of revenue) and it still passes the gate.

FIS's remaining gap is *not* this: both lanes now agree at ~$89 against a $44.78
market and a $56.55 street. Two untouched causes, both on the forbidden/closed
list — WACC of 607 bps against $20.4B of net debt, and a forward lane capitalizing
$6.85 of adjusted EPS against $3.72 of FCFF per share (EPS≠distributable cash, P3).

### 14.2 HPE — DECISION REQUIRED. A declared refusal consumed as a growth rate

`sec-driver-normalization.json` declares
`onLatestContaminatedOrInsufficientClean: normalize_near_term_growth_to_zero`. HPE
closed Juniper in FY2025, so 2025 is acquisition-contaminated, `base_growth_bps`
is set to 0 — and the FCFF lane then discounts HPE as a **zero-growth perpetuity
forever**: $2.233B of FCFF, g0 = 0, WACC 9.04%, net debt $16.0B → **$15.11**,
against a forward lane at $52.22 and a street at $65.56. Disagreement 11023 bps →
`Disputed`, so HPE publishes no value at all.

Consensus has HPE revenue +31.1% in FY2026 and +11.3% in FY2027.

This is failure mode §10 (reading a refusal sentinel as evidence) inside the FCFF
lane itself. It was fixed for the *forward* blend last round via `own_growth_bps()`;
the FCFF lane still consumes its own sentinel. Five of 26 cohort names sit in
`acquisition_normalized`: APH, CRM, HPE, SW, EXE.

Zero is not a measurement. The choice is between two contracts and belongs to the
owner, not to an agent:
- **Refuse** — FCFF `Unavailable` when growth is unmeasurable, route to the forward
  lane (the WDC precedent). Removes the false dispute; HPE would publish ~$52.
- **Prior** — fall back to a defensible rate (macro anchor, or the issuer's own
  pre-acquisition trend) instead of zero. Keeps two lanes but requires choosing the
  prior, which no test decides.

### 14.3 MPWR — no defect found; it is the g0 cap plus a lane basis gap

Forward EPS $34.80 was **not** contaminated (see failure mode §15): the `+1y` period
ends 2027-12-31 and consensus revenue is $2.79B → $4.12B → $5.18B.

Two real observations:
1. Consensus growth (2578 revenue / 2704 earnings) and MPWR's **own** five-year
   revenue median (2643) agree almost exactly, and the flat cap truncates both to
   2000. This is the cleanest case in the cohort for the held g0 blend — the blend's
   deviation term is ~0 here, so it would keep full consensus weight.
2. The lanes are measured off different periods: FCFF anchors on FY2025 actuals
   ($16.41/sh), the forward lane on FY2027 estimates. `$16.41 x 1.475 x 1.258 ≈
   $30.5` of FY2027 FCFF per share against $34.80 of EPS — the two are consistent.
   The $542 vs $909 gap is a horizon-basis gap, not a valuation disagreement.

### 14.4 COF — DECISION REQUIRED. Residual income on a single contaminated ROE

COF routes `FamilyFinancialServices` → `Unavailable`, and the card shows the raw
residual-income figure marked "not reliable yet": **$167.68** against $217.68
market and $256.73 street.

The arithmetic is doing exactly what it is told: snapshot ROE 903 bps against a
cost of equity of 909 bps is a ~zero excess return, so residual income collapses
to book value per share (~$171). The input is the problem — that ROE is a trailing
figure carrying the day-one CECL provision on the acquired Discover book. Forward
consensus is $20.27 (FY2026) and $24.05 (FY2027) EPS, which on the same book value
implies ~11.9% ROE, not 9.03%.

The operating lane already detects and excludes acquisition-contaminated years.
The residual-income lane consumes one un-normalized TTM point. Same class of
defect, different lane. What the normalization should be (window, contamination
rule, whether to prefer forward-implied ROE) is a design choice with no test to
decide it.

Unrelated but noted while reading COF: `extract_normalized_fcff_level` returns
nonsense for COF (revenue steps $27.24B → $3.43B in 2018 on a concept change,
FCFF margins of 811%). It is not consumed by the residual-income lane, but it *is*
consumed by through-cycle ROIC elsewhere. Not touched this round.

### 14.5 State after this round

- Suite **498 passed, 3 failed, 14 ignored** — the same three intentional reds
  (`durable_…recompute_in_normal_gate` 15.27 vs 11.0, `high_signal_…all_members_pass`,
  `baseline_megacap_amzn_class_not_penny_intrinsic` = policy/16 backlog). No
  threshold moved.
- Gate **10/26**, unchanged: AVY, COF, EME, INTU, GOOGL, GOOG, CRM, SLB, EXE, PTC.
- FIS narrowed from several structural failures to a single objection —
  `street_disagreement_exceeds_high_signal_band` — i.e. it is now available, solid,
  correctly classed and internally coherent, and only disagrees with street.
- g0 blend still **held** at `legacy_capped_bps`, pending the GOOGL -7.2% decision
  from the previous round (§3.7). MPWR above is new evidence in its favour.
