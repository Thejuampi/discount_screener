---
name: 'explicit-driver-data-resolution'
type: architecture-spine
purpose: build-substrate
altitude: feature
paradigm: 'Ports and adapters with a pure typed valuation core'
scope: 'Windows and Android annual driver resolution, model routing, unavailable propagation, caching and exact parity'
status: final
created: '2026-07-30'
updated: '2026-07-30'
binds:
  - CAP-1
  - CAP-2
  - CAP-3
  - CAP-4
sources:
  - ../../../specs/spec-explicit-driver-data-resolution/SPEC.md
  - ../../../project-context.md
companions:
  - ../../../specs/spec-explicit-driver-data-resolution/data-resolution-policy.md
  - ../../../specs/spec-explicit-driver-data-resolution/architecture-diagrams.md
---

# Architecture Spine — explicit driver data resolution

## Design Paradigm

Provider adapters normalize external facts into a canonical resolver contract. Pure Windows Rust and Android Kotlin engines consume the same semantic contract and shared goldens. UI, persistence, and network shells do not decide valuation policy.

## Invariants & Rules

### AD-1 — One canonical resolver boundary

- **Binds:** CAP-1, CAP-4
- **Prevents:** Windows and Android choosing different periods, signs, units, or sources independently
- **Rule:** Provider adapters emit canonical facts; one resolver policy determines alignment, precedence, quality, provenance, and rejection reasons before model computation.

### AD-2 — Typed resolved rate inputs

- **Binds:** CAP-1, CAP-4
- **Prevents:** A platform replacing missing evidence with a hardcoded CoD or tax constant
- **Rule:** FCFF consumes `ResolvedRateInputs`; every rate has a source kind, periods, quality, fingerprint, and reason codes. Default is not a valid FCFF CoD/tax source.

### AD-3 — Classification precedes rate resolution

- **Binds:** CAP-2
- **Prevents:** Financial float, deposits, or reserves entering FCFF as operating debt/cash flow
- **Rule:** `FinancialServices` invokes residual income only; `OperatingNonFinancial` invokes the operating resolver; `Unclassified` and `NotEligible` refuse without model fallback.

### AD-4 — Financial and marginal tax separation

- **Binds:** CAP-1, CAP-2
- **Prevents:** Historical effective tax becoming a perpetual WACC tax shield
- **Rule:** Historical effective tax and marginal WACC tax are distinct fields with distinct provenance and consumers. Marginal tax governs WACC/terminal economics.

### AD-5 — Unavailable is typed and destructive to stale FCFF

- **Binds:** CAP-3
- **Prevents:** Stale intrinsic values, zero-valued gaps, and fake scoring when drivers are absent
- **Rule:** Unavailable clears stale FCFF caches, emits reason codes, contributes no synthetic intrinsic or gap, and projects the reason to Detail/Quant Lens.

### AD-6 — Exact fixed-point parity

- **Binds:** CAP-4
- **Prevents:** Tolerance-based drift being accepted between Rust and Kotlin
- **Rule:** Public numeric fields are cents/bps/integers; parity compares all numeric, enum, list, fingerprint, and reason fields exactly. One-cent or one-bps drift fails.

### AD-7 — Cache identity follows policy identity

- **Binds:** CAP-1, CAP-3, CAP-4
- **Prevents:** An old source selection or policy continuing to serve a new model
- **Rule:** Cache identity includes engine version, model policy, resolver policy, selected-source fingerprint, rejected-source fingerprint, and canonical driver fingerprint.

### AD-8 — Bounded live QA

- **Binds:** CAP-4 and operational validation
- **Prevents:** Full-universe Yahoo requests and rate-limit incidents during QA
- **Rule:** Live QA launches one long-lived `qa` process with at most twenty symbols; Russell/SP500/unbounded profiles are forbidden.

## Consistency Conventions

| Concern | Convention |
| --- | --- |
| Money and rates | Public money is integer cents; rates are integer bps; no public floats |
| Periods | Fiscal start/end, duration, fiscal year, and instant date are explicit |
| Provenance | Source, concept, filing/as-of metadata, quality, attempts, rejections, and fingerprints travel with the result |
| Errors | Missing evidence returns typed unavailable/reason codes, never zero defaults |
| Ownership | Providers fetch; resolver aligns; core engines calculate; shells persist; projection renders |
| Parity | Shared contract fixtures plus exact comparator are required gates |

## Stack

| Name | Version |
| --- | --- |
| Rust | repository toolchain |
| Kotlin | repository Gradle toolchain |
| Windows | Tauri/Rust valuation core |
| Android | Kotlin `core` valuation engine |

## Structural Seed

```text
shared/contracts/
  valuation-model-family.json       # cross-platform goldens
apps/windows/src-tauri/src/
  driver_resolution.rs              # canonical Rust resolver and provenance
  dcf_model.rs                      # operating FCFF and financial RI engines
  cross_platform_parity.rs          # Windows export/goldens
apps/android/core/src/main/kotlin/
  .../engine/DriverResolution.kt    # Kotlin semantic peer
  .../engine/DcfAnalysisEngine.kt   # model facade
apps/android/app/src/main/kotlin/
  .../DefaultDashboardRepository.kt # cache/admission/downstream state
```

## Capability → Architecture Map

| Capability / Area | Lives in | Governed by |
| --- | --- | --- |
| CAP-1 | Provider adapters + canonical resolver | AD-1, AD-2, AD-4 |
| CAP-2 | Business classifier + model-family engine | AD-3 |
| CAP-3 | Repository/cache + projection/scoring | AD-5, AD-7 |
| CAP-4 | Shared contracts + Windows/Android parity tests | AD-1, AD-2, AD-6 |

## Deferred

- Desktop adoption of the resolver and exact parity; desktop remains outside this correction.
- Live multi-currency tax jurisdiction modeling beyond the versioned USD-oriented policy.
- Full traded-bond coverage provider integration if no approved market-data source is available; the resolver must still exhaust SEC/Yahoo and report unavailable honestly.
