---
name: 'analyst-method-valuation-automation'
type: architecture-spine
purpose: build-substrate
altitude: feature
paradigm: 'Point-in-time evidence ledger with isolated valuation lanes and offline champion/challenger'
scope: 'Automated professional-method valuation dossiers, beginning with Amazon forward EPS times multiple'
status: final
created: '2026-08-01'
updated: '2026-08-01'
binds:
  - CAP-1
  - CAP-2
  - CAP-3
  - CAP-4
  - CAP-5
  - CAP-6
  - CAP-7
  - CAP-8
  - CAP-9
  - CAP-10
  - CAP-11
sources:
  - ../../../specs/spec-analyst-method-valuation-candidates/SPEC.md
  - ../../valuation-model-family-architecture.md
  - ../architecture-discount_screener-2026-07-30/ARCHITECTURE-SPINE.md
  - ../../../project-context.md
companions:
  - AUTOMATION-ROADMAP.md
  - ../../../specs/spec-analyst-method-valuation-candidates/valuation-method-policy.md
  - ../../../specs/spec-analyst-method-valuation-candidates/amazon-professional-valuation-playbook.md
---

# Architecture Spine — analyst-method valuation automation

## Design Paradigm

QuantEngine becomes a point-in-time valuation-dossier factory, not an algorithm trained to imitate today's market price. Provider adapters acquire evidence; one append-only ledger preserves what was knowable at each date; pure, versioned engines produce independent valuation candidates; Quant Lens explains agreement or disagreement without blending incompatible methods.

The full universe keeps its bounded screening path. Deep consensus, segment, peer, scenario, and SOTP work runs only for a selected or watched issuer through a cancellable, budgeted coordinator.

## Inherited Invariants

| Invariant | Consequence here |
| --- | --- |
| Business class routes the intrinsic model | `ForwardEarningsMultiple` is a parallel market-reference lane, not an FCFF fallback or a new intrinsic-router branch |
| Unknown or missing required evidence fails closed | Missing metric identity, horizon, currency, split basis, entitlement, or lineage cannot become zero or a default multiple |
| Dynamic market parameters carry provenance | Risk-free rate, ERP, beta, CoE, and present-equivalent transformations require dated inputs and policy versions |
| Exact fixed-point parity | Money remains cents, rates bps, and multiples hundredths; Rust/Kotlin outputs and refusals compare exactly |
| Cache identity follows policy and source identity | New evidence, revisions, corporate actions, normalizer changes, or policy changes invalidate prior runs |
| QA is bounded | Live Windows/Android QA uses profile `qa`; deep dossiers never cold-build the full universe |

## Invariants & Rules

### AD-1 — One canonical point-in-time evidence ledger

- **Binds:** CAP-1, CAP-5, CAP-11
- **Prevents:** a second evidence doctrine, destructive latest-value updates, and irreproducible valuations
- **Rule:** Preserve existing `EvidenceObservation` v1 deserialization and define a shared `EvidenceObservationV2` envelope before persistence. V2 adds stable issuer/security identity, evidence lane, provider, lineage group, accounting/metric basis, complete clocks, and an exact resolution partition key. Fix the current Rust/Kotlin equal-rank conflict mismatch and prove parity before v2 becomes ledger truth. Persist normalized observations, revision edges, valuation runs, and forecast outcomes append-only in SQLite. A latest view is only a projection over immutable vintages.

### AD-2 — Knowledge clocks and replay modes are explicit

- **Binds:** CAP-1, CAP-5, CAP-11
- **Prevents:** look-ahead bias and backfilled data masquerading as contemporaneous evidence
- **Rule:** Every observation records economic period, publication time, `source_available_at`, `ingested_at`, and `availability_basis = primary_publication | provider_certified_vintage | first_observed_capture`. Every run declares `ReplayMode = Operational | CertifiedBackfillResearch` in its fingerprint. `Operational` requires publication, source availability, and ingestion no later than `decision_at`. Certified backfill may relax only ingestion time, requires provider vintage/certification, and is barred from live projection, cache, ranking, alerts, and `Strong`. Clocks are validated UTC integer instants; date-only evidence also carries precision.

### AD-3 — Provider adapters stop at evidence

- **Binds:** CAP-1, CAP-11
- **Prevents:** vendor-specific formulas and global source precedence leaking into valuation policy
- **Rule:** `SecAdapter`, `MacroAdapter`, `ConsensusAdapter`, `PriceAdapter`, and `ManualResearchImportAdapter` emit provider-neutral observations. Normalizers own fiscal alignment, currency/unit, GAAP versus adjusted identity, share/split basis, segment identity, and reconciliation. Precedence is defined per fact, not per vendor.

### AD-4 — Evidence lanes and confidence dimensions do not alias

- **Binds:** CAP-3, CAP-5, CAP-11
- **Prevents:** consensus, broker target, broker method, and market price being counted as independent confirmations
- **Rule:** Reported actuals, issuer guidance, external consensus, internal forecasts, and analyst-stated methods remain typed lanes. Source authority, extraction quality, freshness, and predictive quality are separate dimensions. Mandatory `lineage_group_id` values propagate transitively into derived candidates; evidence strength counts connected lineage groups, not rows or adapters. Uncertain independence is treated as correlated/soft.

### AD-5 — Intrinsic and market-reference valuations remain isolated

- **Binds:** CAP-2, CAP-3, CAP-8
- **Prevents:** forcing FCFF to match a P/E target or averaging incompatible values into false precision
- **Rule:** FCFF, residual income, and only a complete reconciled SOTP may be intrinsic candidates. An incomplete SOTP emits `CoveredEVOnly`, a diagnostic with no per-share intrinsic, gap, ranking, or selection role. `ForwardEarningsMultiple` is a market-reference candidate with non-aliasing variants `analyst_stated` and `peer_policy_derived`. A subject price or stated target may validate or reverse-solve a candidate but may never manufacture its EPS, multiple, or fair value.

### AD-6 — The horizon coordinate is part of the value

- **Binds:** CAP-1, CAP-3, CAP-6, CAP-10
- **Prevents:** comparing a Dec-2027 target using 2028E EPS with today's intrinsic as if they shared a date
- **Rule:** Every candidate carries `evidence_observed_at`, `candidate_computed_at`, `target_as_of` plus `DatePrecision = exact_date | month_label | fiscal_period | provider_horizon`, forecast period end, fiscal-calendar vintage, metric basis, currency, per-share/share-count basis, and corporate-action vintage. Quant Lens labels horizon mismatches. Month-only labels cannot drive day-count, annualized return, or present-equivalent. A present-equivalent is a separate, explicitly discounted transformation using dated CoE; it is not part of the first slice.

### AD-7 — Forward EPS is multiperiod and semantically typed

- **Binds:** CAP-1, CAP-2, CAP-6, CAP-7, CAP-11
- **Prevents:** treating Yahoo `+1y` or an adjusted EPS as JPM's 2028E GAAP EPS
- **Rule:** Forecast periods use fiscal dates, not relative aliases. `gaap_diluted_eps`, `normalized_diluted_eps`, and unknown provider metrics are distinct types. Unknown or unreconciled metrics remain provisional or unavailable and cannot silently satisfy a GAAP method.

### AD-8 — Internal multiples require a point-in-time peer policy

- **Binds:** CAP-4, CAP-6
- **Prevents:** `AMZN => 28x`, subject-price circularity, and underpowered regression
- **Rule:** The subject is always excluded. A frozen, versioned membership artifact is chosen without subject target/value and records exact PIT price, EPS, FX, fiscal period, metric, corporate-action, and eligibility fingerprints. Fewer than five peers refuses; five to seven permits a robust median as `soft`; eight to eleven permits a robust median, initially `soft`, subject to dispersion and leave-one-out stability. Regression requires at least twelve peers, at least five observations per fitted coefficient, robust diagnostics, temporal stability, shrinkage to the family prior, and rolling-holdout evidence; it begins `soft`.

### AD-9 — Amazon forecasts reconcile from operating drivers

- **Binds:** CAP-7, CAP-8, CAP-9
- **Prevents:** unsupported top-down EPS growth, advertising double counting, and arbitrary growth-CapEx addbacks
- **Rule:** The internal EPS bridge reconciles AWS, North America, and International revenue/margins through corporate costs, non-operating items, taxes, and diluted shares. Advertising remains embedded until allocatable profit evidence exists. CapEx productivity has typed states `Unsupported | DiagnosticProvisional | Reconciled`. Only `Reconciled` may support an adjustment and requires cash plus financed CapEx, depreciation/lease burden, timing lag, and incremental revenue/margin/ROIC evidence. Otherwise total cash outflow remains unchanged.

### AD-10 — Scenarios preserve economic dependency

- **Binds:** CAP-10
- **Prevents:** combining bear EPS with bull P/E or presenting a Cartesian sensitivity endpoint as a coherent forecast
- **Rule:** Bear/base/bull jointly change operating drivers, EPS, risk, and multiple. Sensitivity tables may vary axes independently for inspection, but scenario candidates use named, versioned dependency policies. Reverse valuation solves required EPS or P/E and never feeds the base case.

### AD-11 — Deep work is an on-demand bounded dossier

- **Binds:** CAP-1, CAP-3, CAP-6, CAP-11
- **Prevents:** thread-per-click duplication, full-universe provider storms, and partial results becoming publishable
- **Rule:** Slice 1 uses a minimal synchronous `AnalystMethodApplicationService`: validate import, freeze observation IDs, compute, atomically commit run plus evidence fingerprint, and update the current projection only when every identity/version/horizon check passes. A later refused or incompatible revision appends invalidation and clears the current projection without deleting history. Slice 2 grows the single authoritative demand path into `ValuationDossierCoordinator::build(symbol, as_of)`. Its states include `planned`, `fetching`, `frozen`, `computed`, `refused`, `cancelled`, `timed_out`, `budget_exhausted`, and `provider_partial`; only a fully frozen/committed run publishes. Retries are idempotent and interrupted jobs are typed.

### AD-12 — Evidence and run identity is durable and versioned

- **Binds:** CAP-1, CAP-4, CAP-5, CAP-11
- **Prevents:** hash collisions, stale runs, and silent provenance rewrites
- **Rule:** A shared contract defines versioned canonical bytes before implementation: domain-separated record kind, schema/fingerprint scheme, length-prefixed UTF-8 fields, explicit null tags, fixed big-endian integer encoding, Unicode NFC, sorted-set rules, and raw attachment bytes hashed independently. SHA-256 identifies v2 observations, evidence sets, and runs. Identity includes issuer/security master and corporate-action vintages, adapter/normalizer versions, engine/method/scenario/peer policy versions, replay mode, and market vintage. Existing FNV records remain untouched and cannot satisfy this lane; there is no legacy rehash project.

### AD-13 — Refinement is offline champion/challenger

- **Binds:** CAP-4, CAP-5, CAP-11
- **Prevents:** online feedback loops that learn to copy price or promote a model from one Amazon success
- **Rule:** Policy candidates train and calibrate only on point-in-time rolling-origin cohorts, then face unseen time and issuer holdouts. Promotion evaluates driver, revenue, margin, EPS, multiple, interval coverage/pinball, stability, coverage, and refusal errors. Subsequent price and analyst target are secondary diagnostics. Runtime challengers never overwrite the champion.

### AD-14 — Human judgment is a typed admission gate

- **Binds:** CAP-1, CAP-4, CAP-6, CAP-7, CAP-8, CAP-9
- **Prevents:** OCR/LLM guesses, unknown XBRL extensions, segment recasts, or licensing violations becoming valuation facts
- **Rule:** Deterministic ingestion, approved mappings, timestamps, lineage, reconciliations, invalidations, and metrics may automate. New issuer extensions, recasts, normalized-EPS adjustments, peer taxonomy/premiums, exceptional events, and proprietary-report extraction require reviewed admission. Automation may prepare a draft with page/section evidence; it cannot promote it.

### AD-15 — Provider entitlement is part of provenance

- **Binds:** CAP-1, CAP-6, CAP-11
- **Prevents:** unauthorized scraping, redistribution, or retention of professional research
- **Rule:** An approved entitlement policy resolves `StorageDisposition = MetadataOnly | EncryptedArtifact | Prohibited` before bytes are written. Slice 1 is `MetadataOnly`: user-entered structured facts plus an optional external-file hash/reference; the reference is nullable for `manual_transcription_unverified` and it copies no proprietary report text or bytes. Future encrypted artifacts require authorized cache/derived-data/retention rules, atomic temp-write→close/fsync→rename→metadata-commit, verification, orphan recovery, storage caps, and purge tombstones. Deleted or corrupt artifacts mark dependent runs `unreplayable_due_to_rights`, never silently replayable. Secrets use the platform credential store and never logs.

### AD-16 — Quant Lens presents disagreement honestly

- **Binds:** CAP-3, CAP-6
- **Prevents:** a correlated method-plus-target becoming two evidence families or a provisional candidate crowning `Strong`
- **Rule:** Quant Lens shows source, metric, EPS, multiple, forecast period, target precision, scenario, quality, and refusal reasons. Analyst-stated target and method from one report count as one lineage-connected family even if another adapter repeats it. The first market-reference slice is diagnostic only: no ranking, no `Strong`, no intrinsic-router selection, no compatible-horizon disagreement scoring, and no blended expected value.

## Consistency Conventions

| Concern | Convention |
| --- | --- |
| Numeric representation | Money in cents, rates in bps, multiples in hundredths, shares as explicit integer scale; no public floats |
| Issuer identity | CIK/stable issuer ID plus security/ticker history, split and diluted-share vintage |
| Metric identity | Canonical dictionary distinguishes reported/GAAP, adjusted/normalized, consensus, guidance, and unknown |
| Time | UTC integer instants plus explicit date precision; economic period, publication, source availability, ingestion, decision, valuation, and target are distinct |
| Persistence | Append-only observations/revisions/runs; idempotent exact duplicates; conflicting identity refuses |
| Failure | `Unavailable`, `Provisional`, `HorizonMismatch`, or `CoveredEVOnly` with reason codes; never zero/default fallback |
| Quality | Authority, extraction, freshness, reconciliation, and predictive quality are scored separately |
| Ownership | Adapters acquire; normalizers admit; core engines compute; coordinator orchestrates; SQLite persists; UI projects |
| Observability | Selected/rejected evidence, latency, quota/cost, freshness, parser version, cache decision, refusal, and recompute cause |
| Parity | Shared goldens and exact Rust/Kotlin comparison, including refusals and fingerprints |

## Stack

| Name | Role |
| --- | --- |
| Rust/Tauri | Windows provider shell, dossier coordinator, SQLite persistence, and pure valuation implementation |
| Kotlin `core` | Pure Android semantic peer for accepted valuation methods |
| SQLite | Local append-only evidence, revision, run, and outcome ledger |
| Shared JSON contracts | Cross-platform goldens, schemas, refusals, and parity fixtures |
| External-file reference (v1) | User-entered facts plus hash/reference only; no proprietary bytes copied into app storage |
| Authorized content-addressed vault (future) | Only after entitlement, encryption, retention, atomic-write, and purge policy approval |
| SEC EDGAR | Primary structured entity facts; filing/segment/guidance artifacts remain admission candidates when mappings are not approved |
| Treasury + FRED/ALFRED | Dated rates and macro vintages |
| Pluggable consensus provider | Current/provisional snapshot now; licensed PIT segment/consensus feed when authorized |

## Structural Seed

```text
shared/contracts/
  valuation-evidence-observation-v2.json        # clocks, replay mode, identity, lineage, canonical bytes
  valuation-forward-earnings-multiple-v1.json  # AMZN + synthetic exact goldens/refusals
  valuation-evidence-sotp.json                  # existing PIT/SOTP evidence contract

apps/windows/src-tauri/src/
  evidence_sotp.rs                    # v1 preserved; conflict parity fixed before v2 envelope
  valuation_evidence.rs               # V2 identity/clocks/lineage/replay resolver
  forward_earnings_multiple.rs        # pure market-reference arithmetic and refusals
  analyst_method_service.rs            # Slice 1 atomic import/freeze/compute/project
  analyst_valuation_coordinator.rs     # Slice 2 bounded network dossier orchestration
  valuation_dossier_view.rs            # additive read model; never legacy intrinsic scalar
  db.rs                                # ordered migrations + append-only evidence/runs
  state.rs / commands.rs / engine.rs   # one authoritative producer and command boundary
  quote_summary.rs                    # current soft consensus adapter; metric stays explicit
  yahoo_session.rs                    # reuse existing Yahoo cooldown/session
  analyst_forecasts.rs                # TipRanks remains explicit-load; not automatic dossier spend
  edgar.rs / sec_normalization.rs     # primary actuals and approved normalization
  quant_lens.rs                       # parallel diagnostic projection, no blending

apps/windows/src/
  api.ts                              # additive ValuationDossierView contract
  detailValuationPresentation.ts      # typed market-reference projection/refusal
  QuantLensPanel.tsx                  # source/horizon-scoped diagnostic element

apps/android/core/
  .../ForwardEarningsMultiple.kt      # exact pure semantic peer
  .../EvidenceObservation.kt          # existing PIT evidence peer extended in parity

_bmad-output/planning-artifacts/architecture/
  architecture-analyst-method-automation-2026-08-01/
    ARCHITECTURE-SPINE.md
    AUTOMATION-ROADMAP.md
    reviews/
```

## Capability → Architecture Map

| Capability / Area | Lives in | Governed by |
| --- | --- | --- |
| CAP-1 dated typed analyst-method evidence | EvidenceObservationV2 + import/ledger | AD-1–AD-4, AD-12, AD-15; v2 clock/hash/refusal goldens |
| CAP-2 separate earnings-multiple candidate | `forward_earnings_multiple` pure core | AD-5–AD-7; AMZN + synthetic parity/mutation goldens |
| CAP-3 compare anchors without blending | Dossier read model + Quant Lens presenter | AD-4–AD-6, AD-16; scoped UI/correlation/stale tests |
| CAP-4 expand by versioned method/peer policy | Evidence-driven peer policy registry | AD-8, AD-12–AD-14; 4/5/7/8/11/12 peer boundaries |
| CAP-5 method fidelity vs out-of-sample usefulness | Outcome ledger + validation lab | AD-13; frozen cohort/holdout promotion report |
| CAP-6 refuse stale/incomplete/circular/incompatible | Versioned admission/refusal policy | AD-2, AD-6–AD-8, AD-11, AD-16; exact reason-code goldens |
| CAP-7 operating-driver EPS + GAAP bridge | Amazon segment EPS bridge | AD-7, AD-9, AD-10; consolidated reconciliation goldens |
| CAP-8 Amazon SOTP cross-check | Complete SOTP or `CoveredEVOnly` | AD-5, AD-9; missing-component/capital-bridge refusal golden |
| CAP-9 investment-wave CapEx productivity | Typed CapEx reconciliation | AD-9; unsupported/provisional/reconciled fixtures |
| CAP-10 coherent scenarios + reverse valuation | Versioned joint-scenario policy | AD-6, AD-10; ordering/dependency/reverse goldens |
| CAP-11 PIT dispersion and revisions | Replay resolver + revision/outcome ledger | AD-1, AD-2, AD-11–AD-13; no-look-ahead/revision tests |

## First Shippable Slice

Build the first result in dependency order:

1. **Foundation 0A:** fix Rust/Kotlin v1 conflict parity; define `EvidenceObservationV2`, resolution keys, replay modes, horizon precision, lineage, and canonical SHA-256 bytes in shared contracts.
2. **Foundation 0B:** add ordered transactional SQLite migrations and a minimal stable identity substrate (issuer/CIK, security, effective ticker, currency, share/split basis, identity vintage) for AMZN and a synthetic issuer.
3. **Slice 1A:** pure exact arithmetic/refusals. The `$13.00 × 28.00 = $364.00` golden is `fixture_transcription`, not proof of report provenance.
4. **Slice 1B:** canonical JSON import only. Until the report is attached/authorized, production evidence is `manual_transcription_unverified`, omits page/section, permits a null external-file reference, treats GAAP/JPM/December-2027 as sourced claims, stays provisional, and stores no proprietary bytes. A verified report later appends a new observation/revision; it never rewrites the transcription.
5. **Slice 1C:** minimal application service atomically freezes, computes, persists, invalidates, and projects through an additive `ValuationDossierView`/Tauri/TypeScript/Quant Lens path. It never writes legacy FCFF/selected intrinsic scalars.

Required gates include migration rollback/reopen, crash/retry idempotence, restart/stale-projection clearing, policy/split/revision invalidation, operational-vs-certified replay isolation, mutation invariance to subject price/target/implied P/E, one-cent parity, typed refusals, transitive lineage deduplication, shared Rust/Android core contracts, `scripts/validate-android.ps1`, `dcf_model::`, `valuation_baseline::`, `quant_lens::`, a DOM-scoped native assertion, and one long-lived Windows live QA process under profile `qa`. Desktop explicitly does not support this lane and cannot deserialize it as FCFF or consensus.

## Deferred

- Purchasing or integrating Visible Alpha, FactSet, or LSEG I/B/E/S until budget, entitlement, caching, derived-data, retention, and encryption authority exists.
- Proprietary raw-artifact vault and generic PDF/OCR/LLM promotion; Slice 1 is metadata-only and draft extraction remains behind reviewed admission.
- `peer_policy_derived`, regression, automated present-equivalent, ranking participation, and `Strong` eligibility until PIT validation gates pass.
- Full Amazon segment forecast/SOTP until segment/KPI estimates, capital bridge, and all material component evidence reconcile.
- Automatic policy promotion or online learning.
- Cloud data platforms and monitoring stacks; this remains a single-user local workstation.
