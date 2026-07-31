---
id: SPEC-explicit-driver-data-resolution
status: in-progress
baseline_commit: 8bcf758a96d15e7add1af19f3aca8648c8434d81
companions:
  - data-resolution-policy.md
  - architecture-diagrams.md
  - ../../planning-artifacts/architecture/architecture-discount_screener-2026-07-30/ARCHITECTURE-SPINE.md
sources:
  - ../../implementation-artifacts/spec-driver-based-fcff-2026-07-30.md
  - ../../implementation-artifacts/spec-pr24-android-valuation-parity.md
  - ../../planning-artifacts/valuation-model-family-architecture.md
  - ../../project-context.md
---

> **Canonical contract.** This SPEC and its companions define the data resolution, model routing, unavailable behavior, and Windows/Android parity rules for valuation driver inputs.

# Explicit financial driver resolution without silent defaults

## Why

FCFF/WACC results can be materially wrong when annual interest, debt, or tax fields are missing and the engine silently substitutes policy constants. The product needs a reproducible, auditable resolution path that exhausts eligible evidence, separates historical effective tax from marginal WACC tax, preserves the financial-services model family, and refuses only after the evidence path is genuinely exhausted.

## Capabilities

- **CAP-1**
  - **intent:** Resolve aligned annual operating, debt, interest, and tax inputs from all eligible sources before calculating an operating-company FCFF valuation.
  - **success:** Every resolved field exposes source, fiscal period, units, provenance, quality, and rejected-source reasons; no FCFF CoD/tax input comes from a silent hardcoded default.

- **CAP-2**
  - **intent:** Route valuation by business class without applying operating-company debt logic to financial businesses.
  - **success:** JPM, CI, ACGL, and equivalent financial services use residual income with cost of equity; missing book/ROE yields an explicit unavailable state; no financial FCFF fallback exists.

- **CAP-3**
  - **intent:** Represent unavailable valuation as an honest downstream state rather than a zero, synthetic gap, or hidden stale value.
  - **success:** Cache, scoring, Quant Lens, and Detail preserve unavailable reason codes; analyst ranges remain independent and may be shown as analyst-only evidence.

- **CAP-4**
  - **intent:** Make Windows and Android execute the same resolved-input contract and numeric policy.
  - **success:** Shared fixtures compare money in cents, rates in bps, provenance, periods, fingerprints, enums, and reason codes exactly; a one-cent mutation fails parity.

## Constraints

- Missing debt never means zero. Explicitly reported zero debt is `NotApplicable`; interest paired with zero debt is provider inconsistency.
- Fiscal alignment uses period start/end, duration, fiscal year, instant date, units, currency, and filing provenance; `asOfDate` alone is insufficient.
- Cost-of-debt resolution applies only to `OperatingNonFinancial` and follows observable market evidence, rating or synthetic spread, aligned SEC accounting evidence, aligned Yahoo evidence, then unavailable.
- Historical effective tax and marginal WACC tax are separate inputs. Effective tax may reconstruct observed FCFF; marginal tax is used for future and terminal tax shielding.
- Evidence quality is `solid` with three or more valid periods, `provisional` with one or two real periods, and `unavailable` after all sources are exhausted without a valid period.
- Public parity is fixed-point and exact. No epsilon is permitted.
- Analyst targets, market price, and market-implied disagreement never assign or cap intrinsic value.
- Cache keys include engine, policy, source, resolver, and driver fingerprints; policy or source changes invalidate stale valuations.

## Non-goals

- Replacing residual income with FCFF for banks, insurers, brokers, or managed care.
- Using analyst targets or market price as runtime valuation inputs or output caps.
- Adding desktop parity in this change.
- Treating a policy default as a solid valuation input merely because the UI labels it provisional.

## Success signal

The same pinned annual evidence produces identical Windows and Android outputs, while missing or contradictory evidence produces a visible unavailable/provisional state with a complete resolution trace. The multi-name cohort and checklist fixtures remain ordered, non-penny, correctly routed, and free of silent default CoD/tax paths.

## Code Map

- `apps/windows/src-tauri/src/dcf_model.rs`: operating FCFF routing, WACC derivation, driver normalization, and public provenance fields.
- `apps/windows/src-tauri/src/edgar.rs`: SEC annual duration/instant extraction and source provenance for operating drivers.
- `apps/windows/src-tauri/src/engine.rs`: valuation cache invalidation, unavailable propagation, and Detail-facing reason codes.
- `apps/windows/src-tauri/src/cross_platform_parity.rs`: Windows parity fixture export and exact field projection.
- `apps/android/core/src/main/kotlin/com/discountscreener/core/model/Models.kt`: annual fact metadata, resolved-rate provenance, and DCF public contract.
- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfAnalysisEngine.kt`: Android FCFF/residual-income routing and driver/WACC resolution.
- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfSourceSelectionPolicy.kt`: fiscal-period/source eligibility and resolution trace.
- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/remote/SecEdgarTimeseriesProvider.kt`: SEC facts mapped into aligned annual facts.
- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/remote/YahooFinanceClient.kt`: Yahoo facts retained only when period-aligned.
- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt`: cache clearing and unavailable state preservation.
- `scripts/compare-windows-android-valuation-parity.ps1`: exact parity comparator; no tolerance is permitted.
- `shared/contracts/valuation-model-family.json`: cross-platform fixtures and model-family contract.

## Tasks & Acceptance

- [ ] Add a typed, provenance-carrying resolver for aligned annual debt, interest, effective tax, and marginal tax inputs.
- [ ] Remove FCFF CoD/tax silent defaults and return unavailable with reason codes after source exhaustion.
- [ ] Preserve financial-services residual income routing and fail closed when book/ROE inputs are absent.
- [ ] Propagate unavailable through cache, Detail, scoring, and Quant Lens without zero/synthetic gaps.
- [ ] Extend Windows and Android parity exports and make exact comparison a required test gate, including a one-cent mutation test.
- [ ] Add fixtures for the specified operating, financial, tax, debt-source, period-alignment, contradiction, and downstream-unavailable cases.
- [ ] Pass the required Windows and Android gates; live QA, if performed, uses only the locked `qa` profile with at most 20 symbols.

Acceptance is met only when every resolved public monetary field is equal in cents, every rate in bps, and all provenance, periods, fingerprints, enums, and reason codes compare exactly; missing evidence never produces a fabricated FCFF value.

## Assumptions

- The new contract supersedes only conflicting default CoD/tax clauses in historical artifacts; historical files remain unchanged for audit.
- A jurisdictional marginal tax table is versioned data, not an unlabelled engine constant, and remains provisional when filing-level reconciliation is unavailable.
- Existing market-parameter bootstrap behavior outside FCFF CoD/tax is unchanged unless separately covered by a later policy.
