# Headless valuation PoC — forward owner earnings

Date: 2026-07-31  
Status: validated locally; **not integrated into Windows, Android, desktop, or UI**

## Purpose

Test whether the reported valuation failures are mostly presentation problems, bad SEC/Yahoo source continuity, or a structural limitation of trailing FCFF. Analyst targets are validation anchors only; neither PoC receives market price or target price as a model input.

## Reproduction

1. The ignored Rust diagnostic `valuation_baseline::live_headless_current_engine_poc` fetched one live Yahoo snapshot, Yahoo `earningsTrend`, and normalized SEC annual rows for all 15 symbols without starting Tauri.
2. The frozen capture is `.agents/workspace/tmp/poc-current-engine.json`.
3. Run `python .agents/workspace/tmp/poc_valuation_models.py` from the repository root.
4. Machine-readable output is `.agents/workspace/tmp/poc-model-comparison.json`.

The script asserts that every symbol becomes calculable, every existing numeric result moves closer to the independent analyst anchor, and every PoC-1 result is within a broad 45% diagnostic band. Those are experiment checks, not proposed production price clamps.

## Results

| Symbol | Market | Analyst validation | Current FCFF | PoC 1: forward owner-earnings DCF | PoC 2: forward earnings perpetuity |
| --- | ---: | ---: | ---: | ---: | ---: |
| DVN | $44.65 | $59.38 | $123.21 | **$56.12** | $56.12 |
| GDDY | $79.67 | $111.93 | $152.14 | **$146.54** | $133.95 |
| WYNN | $99.21 | $133.32 | $0.00 | **$90.50** | $87.12 |
| SNDK | $1,265.62 | $2,217.77 | unavailable | **$2,129.46** | $2,129.46 |
| BR | $153.19 | $206.50 | $149.27 | **$189.33** | $179.57 |
| BSX | $46.06 | $64.28 | $35.54 | **$84.53** | $79.43 |
| AMZN | $270.63 | $313.07 | $8.85 | **$240.79** | $173.13 |
| AVGO | $388.49 | $527.88 | $119.62 | **$437.28** | $260.24 |
| HPE | $48.08 | $64.34 | $12.96 | **$69.68** | $54.00 |
| MU | $849.75 | $1,507.38 | $51.49 | **$1,611.53** | $1,611.53 |
| ORCL | $128.57 | $248.15 | $13.99 | **$220.01** | $132.16 |
| AAPL | $301.15 | $321.66 | $111.62 | **$179.78** | $145.26 |
| CPRT | $29.05 | $40.30 | $19.45 | **$29.54** | $28.67 |
| CEG | $264.38 | $352.86 | unavailable | **$213.24** | $218.18 |
| ALB | $117.58 | $187.16 | unavailable | **$182.29** | $182.29 |

Across the 11 previously calculable cases, mean absolute error versus the validation anchor fell from **70.7% to 19.5%**. Coverage increased from **11/15 to 15/15**.

## What the PoCs prove

- The main low-value failure is structural. Trailing `OCF − total CapEx` treats a temporary investment wave as perpetual maintenance: AMZN subtracts $131.8B CapEx from $139.5B OCF and ORCL subtracts $55.7B from $32.0B.
- Full-history median FCFF is not a valid current-cycle refusal rule. CEG and ALB have positive latest SEC FCFF, but older negative years make the median negative and suppress a current estimate.
- SNDK is a source-continuity failure. The SEC path exposes only three older annual rows and ends at $84M OCF / −$120M reported FCF, while the same live Yahoo capture reports $4.64B OCF, $2.26B FCF, and forward EPS of $212.95. The engine silently prefers stale/non-comparable SEC history.
- DVN requires a through-cycle cost of equity. Its trailing beta of 0.43 makes the current model capitalize commodity cash flow as bond-like; the PoC uses an explicit commodity-cycle risk floor rather than a price cap.
- Forward operational consensus (EPS/revenue and analyst count) contains information missing from a trailing-only FCFF. It can be consumed without using target price.

## Candidate production direction

Do not replace FCFF silently. Add a versioned `forward_owner_earnings_equity` candidate for operating companies when trailing FCFF is distorted by an investment cycle, corporate discontinuity, or non-positive robust history but forward evidence is complete. It should use cost of equity, publish forecast date/analyst count/range/revisions, retain the trailing FCFF as conflicting evidence, and remain soft until independently corroborated.

Before integration, replace the PoC's coarse commodity/new-issuer risk rules with versioned sector/industry beta evidence and define a deterministic source-continuity gate. Windows and Android then need an exact shared contract and the UI needs model/provenance/refusal diagnostics.

## Iteration 2 — evidence router and independent holdout

The unconditional competitive-advantage variant was rejected: although it fit the 15 reported names, it produced 57.4% mean absolute error on an unseen 12-name holdout. The retained PoC routes by observable evidence instead of applying the forward model universally.

Routing evidence includes trailing-model availability, latest SEC period, latest CapEx spike, normalized FCFF margin, acquisition discontinuity, extreme leverage, through-cycle industry, durable excess returns, and closed-world financial classification. Mature defensive cash generators and high-growth trailing evidence retain FCFF. Financial services are refused by this PoC and remain on residual income.

### Reported 15-name cohort

| Symbol | Analyst validation | Current FCFF | Evidence-routed PoC | Route summary |
| --- | ---: | ---: | ---: | --- |
| DVN | $59.38 | $123.21 | **$56.12** | Through-cycle E&P rate |
| GDDY | $111.93 | $152.14 | **$127.20** | Extreme leverage |
| WYNN | $133.32 | $0.00 | **$105.12** | Trailing equity value unavailable |
| SNDK | $2,217.77 | unavailable | **$2,129.46** | Stale/discontinuous SEC history |
| BR | $206.50 | $149.27 | **$217.87** | Durable excess-return evidence |
| BSX | $64.28 | $35.54 | **$64.67** | Acquisition discontinuity |
| AMZN | $313.07 | $8.85 | **$241.98** | Thin normalized FCFF margin |
| AVGO | $527.88 | $119.62 | **$437.28** | Durable excess-return evidence |
| HPE | $64.34 | $12.96 | **$76.44** | Thin margin + acquisition discontinuity |
| MU | $1,507.38 | $51.49 | **$1,611.53** | Latest CapEx spike / cycle recovery |
| ORCL | $248.15 | $13.99 | **$220.01** | Latest CapEx spike |
| AAPL | $321.66 | $111.62 | **$271.35** | Durable excess-return period |
| CPRT | $40.30 | $19.45 | **$33.47** | Stale SEC period |
| CEG | $352.86 | unavailable | **$303.48** | Non-positive history + forward evidence |
| ALB | $187.16 | unavailable | **$182.29** | Through-cycle recovery |

Result: **15/15 within ±25%**, mean absolute error **11.8%**, maximum **22.7%**. The current FCFF baseline had 70.7% mean error across its 11 calculable names.

### Unseen 12-name holdout

The holdout used T, MSFT, NVDA, JNJ, XOM, V, WMT, GOOGL, META, HD, PG, and MRK. V was correctly excluded because credit services require the financial-services model family. The remaining 11 operating names were all within ±25%: mean absolute error **11.0%**, maximum **22.1%**.

Representative routing checks:

- T and XOM use explicit through-cycle rates.
- MSFT, GOOGL, META, and HD use forward evidence only where CapEx or durable-return evidence supports it.
- NVDA retains trailing FCFF because executable driver growth is already strong.
- JNJ, PG, and MRK retain mature-defensive trailing FCFF.
- WMT switches because its normalized trailing FCFF margin is only about 2.2%.
- V remains unavailable here instead of silently running an operating-company formula.

The PoC also mutates both market price and analyst target to absurd values and asserts bit-for-bit identical routed values/refusals. This guards against accidental validation-anchor leakage.

### Remaining production work

The evidence router is now a viable design candidate, not production code. Integration still requires a versioned contract, deterministic current-year/as-of handling (the PoC currently freezes the 2026 audit boundary), structured reason codes, Windows/Android parity, model-quality rules, and UI disclosure as a distinct forward equity model rather than relabeling it FCFF DCF.

## Iteration 3 — fixed-point headless core

The experimental float formula was re-expressed as a provider-independent
integer recurrence in Rust and Kotlin. Both implementations consume
`shared/contracts/operating-valuation-router-v1.json` and match its candidate,
route, reason, and fingerprint fields exactly. Price and target do not exist in
the engine/router DTOs. The source-audit diagnostic recomputes business class
from frozen sector/industry fundamentals; that normalized class is versioned
in the durable cohort, so `V` exits through the financial model boundary in
both normal gates.

The normal Rust and Kotlin test gates now recompute the two cohorts from durable
normalized inputs in `shared/contracts/operating-valuation-router-v1.json`.
The ignored Rust diagnostic remains only as a source-audit bridge back to the
original frozen Yahoo/SEC captures and writes
`.agents/workspace/tmp/poc-fixed-point-operating-core.json` when explicitly run.

| Symbol | Diagnostic route value | State | Analyst validation only | Absolute error |
| --- | ---: | --- | ---: | ---: |
| DVN | $56.13 | Disputed | $59.38 | 5.5% |
| GDDY | $128.27 | Selected | $111.93 | 14.6% |
| WYNN | $107.41 | Selected | $133.32 | 19.4% |
| SNDK | $2,129.49 | Selected | $2,217.77 | 4.0% |
| BR | $224.03 | Selected | $206.50 | 8.5% |
| BSX | $64.81 | Disputed | $64.28 | 0.8% |
| AMZN | $250.81 | Disputed | $313.07 | 19.9% |
| AVGO | $462.64 | Disputed | $527.88 | 12.4% |
| HPE | $79.38 | Disputed | $64.34 | 23.4% |
| MU | $1,611.29 | Disputed | $1,507.38 | 6.9% |
| ORCL | $232.28 | Disputed | $248.15 | 6.4% |
| AAPL | $281.77 | Disputed | $321.66 | 12.4% |
| CPRT | $34.06 | Disputed | $40.30 | 15.5% |
| CEG | $314.03 | Selected | $352.86 | 11.0% |
| ALB | $182.33 | Selected | $187.16 | 2.6% |

Reported cohort: 15/15 calculable, 10.9% mean absolute validation error, 23.4%
maximum. Independent holdout: 11/12 calculable, 11.3% mean, 20.9% maximum;
`V` is the intentional financial-services refusal. These metrics measure the
experiment only. No runtime branch, clamp, router threshold, or test assertion
uses them to force proximity.

For `Disputed` rows, “Diagnostic route value” is the forward candidate retained
beside FCFF; it is **not** a published primary. The router intentionally leaves
the singular selected model/value empty until a downstream presentation shows
both anchors honestly.

This iteration is still headless. Provider normalization, cache/runtime wiring,
Quant Lens correlation handling, and UI labels remain outside the slice.
