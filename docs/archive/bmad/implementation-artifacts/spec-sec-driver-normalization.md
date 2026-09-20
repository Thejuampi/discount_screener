---
title: 'SEC canonical driver normalization for FCFF'
type: 'feature'
created: '2026-07-30'
status: 'in-progress'
review_loop_iteration: 0
baseline_commit: 'dd7290438de09447db5b1f9f6bff8b2d6304f713'
context:
  - 'AGENTS.md'
  - '_bmad-output/project-context.md'
  - '_bmad-output/specs/spec-explicit-driver-data-resolution/SPEC.md'
  - 'shared/contracts/valuation-model-family.json'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** SEC EDGAR reports economically different investment cash flows under distinct XBRL concepts, while Windows and Android each pass short, duplicated raw-concept lists toward valuation. Treating every property-acquisition fact as operating CapEx would make CRGY's $558.6M 2024 acquisition cash—primarily from the SilverBow merger and other acquisitions—look like recurring reinvestment, even though its development cash was separately $685.7M.

**Approach:** Put a canonical, provenance-carrying SEC normalization boundary between raw company facts and the QuantEngine. It first classifies facts by economic meaning (development, property acquisition, business acquisition, or unclassified investment), then applies a separate FCFF-consumption policy. One executable shared policy defines the approved QNames, fact shape, operation, precedence, and rejection rules for both implementations.

## Boundaries & Constraints

**Always:** The first version covers domestic US-GAAP `10-K`/`10-K/A` evidence for `OperatingNonFinancial` issuers with a CIK. Normalize duration drivers (OCF, revenue, interest, development CapEx) by fiscal start/end and duration; resolve debt as a consolidated instant at the matching fiscal close, with explicit 52/53-week and calendar-change tolerance. Preserve selected and rejected QName, taxonomy, form, accession, filing date, period, units, dimensions, evidence state, and policy fingerprint. Effective tax is derived from reported tax expense and pretax income; marginal tax is a separate reference policy with separate provenance. Missing/misaligned evidence makes FCFF unavailable, never zero. FinancialServices continue to bypass FCFF and use residual income. Windows and Android must have exact shared-contract parity.

**Ask First:** Adding a non-SEC provider; changing model family/ranking semantics; approving an acquisition-inclusive FCFF model; accepting an issuer-specific extension without a reviewed economic mapping; or expanding this first version to foreign private issuers, IFRS, `20-F`, or non-SEC-reporting listings.

**Never:** Use ticker-specific patches; infer economics from a QName's name; merge development, property acquisition, business acquisition, and unclassified investment into one CapEx driver; sum overlapping facts; count acquisition cash as recurring operating CapEx; represent reference-policy marginal tax as SEC-reported; use market price or analyst targets as valuation inputs; or make fetch failure/missing evidence look like a successful DCF.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|---------------|---------------------------|----------------|
| CRGY investment split | `PaymentsToExploreAndDevelopOilAndGasProperties` 2024 cash $685.7M and `PaymentsToAcquireOilAndGasProperty` cash $558.6M | Normalizer preserves two economic categories; FCFF consumes development only under the recurring policy | Acquisition is visible and tagged `RejectedAcquisition`, never relabeled as operating CapEx |
| Equivalent facts overlap | Multiple reported facts for the same canonical component and fiscal period | `SelectOneEquivalent` applies filing/accession precedence and records alternatives | Ambiguity remains `AmbiguousOverlap`; never sum or guess |
| Debt at fiscal close | Duration drivers and debt instant share a fiscal end | `SumDisjointComponents` combines current/noncurrent debt only when no approved total exists | Invalid unit, non-consolidated fact, or unmatched instant is rejected |
| Tax construction | Tax expense plus pretax income, and reference marginal-tax policy | `Derive` produces effective tax; reference policy supplies marginal tax with its own provenance | Never label derived/reference values as SEC-reported |
| Required driver absent | No approved concept after fiscal and unit checks | QuantEngine receives `NoApprovedConcept`, `InvalidUnit`, or `MisalignedPeriod` | No raw-JSON fallback or invented cash flow |
| Provider failure or stale cache | EDGAR request fails or only prior capture exists | State remains `FetchFailed` or `StaleCachedEvidence` | Stale evidence may be reference-only and cannot silently feed FCFF |
| Financial issuer | FinancialServices classification | Residual-income path remains selected | SEC FCFF normalization is not used to override model routing |
| Unsupported coverage | No CIK or non-SEC issuer | Source layer reports unsupported/unavailable provenance | No claim that SEC covers the symbol |

</frozen-after-approval>

## Code Map

- `shared/contracts/` -- executable, machine-readable normalization policy: canonical economic category, approved QName/taxonomy, units, period shape, operation (`select`, `sum`, `derive`, `reference`), form, precedence, and rejection rules.
- `apps/windows/src-tauri/src/sec_normalization.rs` -- new pure typed normalizer and evidence ledger; owns SEC fact categorization rather than the valuation engine.
- `apps/windows/src-tauri/src/edgar.rs` -- current SEC JSON extraction, annual/instant filtering, CapEx aliases, and `FcfPoint` construction; replace direct field-specific extraction with the Windows normalizer boundary and fact ledger.
- `apps/windows/src-tauri/src/driver_resolution.rs` -- consumes aligned annual driver values for rate provenance; must accept only normalized inputs.
- `apps/windows/src-tauri/src/dcf_model.rs` -- FCFF engine and driver fingerprints; remains the owner of valuation, not XBRL taxonomy selection.
- `apps/windows/src-tauri/src/commands.rs` -- demand valuation orchestration; propagate canonical unavailable diagnostics to Detail without a cache fallback.
- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/remote/SecEdgarTimeseriesProvider.kt` -- duplicate raw SEC lists and annual merging; becomes Android's adapter to generated/shared canonical policy, not a second alias list.
- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfSourceSelectionPolicy.kt` and `DcfAnalysisEngine.kt` -- consume source-resolved normalized drivers only.
- `shared/contracts/valuation-model-family.json` and a new SEC-normalization fixture -- versioned policy, cross-platform goldens, CRGY, overlap, missing-data, and financial-routing cases.
- `apps/windows/src-tauri/src/cross_platform_parity.rs` and Android contract tests -- exact cents/bps/provenance/fingerprint parity.

## Tasks & Acceptance

**Execution:**
- [ ] `shared/contracts/sec-driver-normalization.json` and `shared/contracts/sec-driver-normalization-fixtures.json` -- define the versioned executable policy and frozen real SEC cases across at least five issuers, including CRGY's development/property-acquisition split, overlap, restatement, and missing evidence.
- [ ] `apps/windows/src-tauri/src/sec_normalization.rs` and `apps/windows/src-tauri/src/edgar.rs` -- implement typed raw SEC facts, the four investment categories, fact-shape validation, evidence states, and `SelectOneEquivalent`/`SumDisjointComponents`/`Derive`/`ReferencePolicy` without raw aliases in EDGAR orchestration.
- [ ] `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/SecDriverNormalizationPolicy.kt` and `apps/android/app/src/main/kotlin/com/discountscreener/android/data/remote/SecEdgarTimeseriesProvider.kt` -- consume the same executable/generated policy and fact acceptance rules; eliminate divergent aliases and preserve the same ledger.
- [ ] `apps/windows/src-tauri/src/dcf_model.rs`, `apps/windows/src-tauri/src/driver_resolution.rs`, `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfAnalysisEngine.kt`, and `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfSourceSelectionPolicy.kt` -- consume recurring-development FCFF inputs only, include policy/source fingerprints in cache identity, retain model-family routing, and distinguish provider failure from evidence absence.
- [ ] `apps/windows/src-tauri/src/cross_platform_parity.rs`, `apps/android/core/src/test/kotlin/com/discountscreener/core/contracts/ContractFixtureTest.kt`, and the relevant Rust tests -- add red regressions for CRGY category separation, alias overlap, filing precedence, instant debt alignment, missing/fetch/stale states, and financial-service bypass; extend the multi-name baseline with no new quarantine.
- [ ] `AGENTS.md` and `_bmad-output/project-context.md` -- document the normalization invariant and the SEC-reporting coverage boundary when implementation establishes them as durable rules.

**Acceptance Criteria:**
- Given CRGY's frozen 2024 SEC facts, when normalization runs, then `PaymentsToExploreAndDevelopOilAndGasProperties` ($685.7M) and `PaymentsToAcquireOilAndGasProperty` ($558.6M) remain separate; acquisition is never relabeled or added to recurring FCFF.
- Given duration facts and debt at the fiscal close, when normalization runs, then it validates each fact's shape independently and only combines explicitly disjoint debt components.
- Given amended/restated or comparative duplicate filings, when normalization runs, then approved form, accession, filing-date, consolidation, and period rules select exactly one fact or report `AmbiguousOverlap`.
- Given fetch failure, stale evidence, no approved concept, invalid unit, or misaligned period, when valuation is requested, then Detail and Quant Lens receive the corresponding state and no fresh intrinsic value.
- Given the same fixture corpus on Windows and Android, when parity executes, then economic categories, selected/rejected evidence, provenance, periods, fixed-point values, fingerprints, model, and unavailable reasons match exactly.
- Given a financial-service issuer, when SEC facts are available, then it remains residual-income or explicitly unavailable; it never emits FCFF-primary.

## Design Notes

The normalizer is a source adapter, not a second valuation engine. Its output is a categorized evidence ledger plus canonical annual drivers. Category selection and FCFF consumption are deliberately separate: `PaymentsToAcquireOilAndGasProperty` may be useful evidence of acquisition cash, but it is not a recurring-development driver. The policy is executable rather than duplicated data so platform parity is by construction; facts that lack an approved economic mapping stay visible but unavailable.

Post-QA valuation amendment (policy/12): acquisition evidence remains unchanged at the normalization boundary, but the valuation consumer no longer blanket-zeroes an entire recent window. Material acquisition cash in year Y excludes only growth Y−1→Y. At least two clean recent transitions with a clean latest transition retain their observed growth; otherwise near-term growth is zero with explicit provenance. Base FCFF margin is calculated from aligned annual FCFF identities, retaining negative and CapEx-expansion years.

Policy/9: `PaymentsToAcquireOilAndGasPropertyAndEquipment` is a disjoint well-program development component. EOG-class issuers file it as the recurring CapEx line beside a smaller other-plant tag. `PaymentsToAcquireOilAndGasProperty` (no equipment) remains rejected acreage acquisition. When `PaymentsToExploreAndDevelopOilAndGasProperties` is the selected tangible tag, the well component is dropped so the wells are not counted twice.

## Verification

**Commands:**
- `cargo fmt --check` and `cargo test --lib dcf_model::` from `apps/windows/src-tauri` -- expected: formatting and FCFF/model-family regressions pass.
- `cargo test --lib valuation_baseline::` and `cargo test --lib quant_lens::` from `apps/windows/src-tauri` -- expected: the multi-name cohort has no quarantine and downstream valuation semantics remain valid.
- `scripts/compare-windows-android-valuation-parity.ps1` -- expected: exact normalization and valuation parity for every fixture.
- `scripts/validate-android.ps1` -- expected: core and available app tests pass.
- `npm run tauri:dev:qa` from `apps/windows` -- expected: one QA-profile process verifies CRGY DCF provenance plus the existing operating/financial/unavailable checklist; no full-universe launch.
