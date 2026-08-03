---
title: Valuation Automation Learning Ledger
status: living
scope: analyst-method valuation automation and QuantEngine calibration
created: 2026-08-01
last_updated: 2026-08-02
review_trigger: implementation handoff, failed gate, live QA finding, provider incident, or policy promotion
---

# Valuation Automation Learning Ledger

This is durable engineering memory, not a victory log and not a substitute for tests. It records what the team learned while designing and implementing professional-method valuation automation, beginning with Amazon.

The implementation is still in progress. Therefore, this document distinguishes **planning evidence** from **implementation evidence** and does not claim that planned behavior has shipped.

## How to keep this useful years from now

Every lesson must declare:

| Field | Meaning |
| --- | --- |
| `status` | `candidate`, `confirmed`, `promoted`, `superseded`, or `rejected` |
| `scope` | The names, model families, providers, or platforms for which evidence exists |
| `evidence` | A spec, diff, fixture, test, review finding, provider sample, or live QA observation |
| `durable principle` | The portable rule, separated from the current implementation detail |
| `current expression` | How the repository implements the rule today; expected to change over time |
| `review trigger` | What new evidence would require reconsideration |
| `promotion target` | Contract, architecture, `project-context.md`, `AGENTS.md`, operator docs, or test suite |

Rules for maintaining the ledger:

1. Record observations before conclusions.
2. Do not infer a universal rule from one ticker.
3. Do not infer predictive quality from closeness to current price.
4. Preserve failed approaches and why they failed; do not rewrite history after a fix.
5. Promote only standing rules. Provider names, thresholds, and file paths usually remain versioned implementation details.
6. A chat statement is a lead, not evidence. Link the artifact or test that makes it reproducible.
7. When a lesson stops being true, mark it `superseded`; never silently delete it.

## Durable domain knowledge

### DL-001 — Valuation methods answer different questions

- **Status:** promoted
- **Scope:** all model families
- **Evidence:** Amazon FCFF versus the user-transcribed JPM forward EPS/P-E method; valuation-family architecture
- **Durable principle:** A cash-flow intrinsic, residual-income intrinsic, SOTP, and target-horizon earnings multiple are different financial instruments. Honest disagreement does not imply that one engine is broken.
- **Current expression:** Separate intrinsic and market-reference lanes; never tune FCFF until it reproduces a P/E target.
- **Review trigger:** a future selection policy proposes blending model identities.
- **Promotion target:** already represented in architecture and project rules.

### DL-002 — Accuracy must be decomposed before it can be improved

- **Status:** confirmed at design level
- **Scope:** forecast and valuation policies
- **Evidence:** BMAD Party convergence and reviewer approval
- **Durable principle:** Final-price error contains at least data error, operating/earnings forecast error, valuation/multiple error, and horizon error. A single price-distance metric cannot identify what should change.
- **Current expression:** Outcome ledger and offline champion/challenger must report driver, EPS, multiple, interval, refusal, and only secondarily price/target diagnostics.
- **Review trigger:** implementation metrics or promotion policy are defined.
- **Promotion target:** validation contract and future project-context rule after empirical use.

### DL-003 — Point-in-time integrity is part of the model

- **Status:** confirmed at architecture level
- **Scope:** consensus, revisions, market data, macro, filings, and backtests
- **Evidence:** architecture AD-2; adversarial P0 review
- **Durable principle:** Data observed today cannot be treated as known historically. Backfilled certified vintages and operationally captured evidence are different research modes and must not share production eligibility.
- **Current expression:** `Operational` versus `CertifiedBackfillResearch`, with explicit publication, availability, ingestion, and decision clocks.
- **Review trigger:** first replay implementation and first licensed historical provider.
- **Promotion target:** shared contracts and no-look-ahead tests.

### DL-004 — Provenance quality has multiple dimensions

- **Status:** confirmed at architecture level
- **Scope:** every evidence source
- **Evidence:** missing JPM PDF exposed the distinction
- **Durable principle:** Source authority, extraction accuracy, semantic compatibility, freshness, entitlement, and predictive quality are not interchangeable. A correctly transcribed number can still have unverified authority.
- **Current expression:** The Amazon arithmetic fixture is not verified JPM research; production import remains `manual_transcription_unverified` until the entitled source is admitted.
- **Review trigger:** source artifact becomes available or import UX is implemented.
- **Promotion target:** evidence schema, UI labels, admission tests.

### DL-005 — Correlated observations are not independent evidence

- **Status:** confirmed at architecture level
- **Scope:** analyst targets, EPS, methods, consensus aggregators, and repeated adapters
- **Evidence:** JPM target/method and possible TipRanks duplication
- **Durable principle:** Repackaging the same underlying research through multiple fields or vendors does not increase evidentiary strength.
- **Current expression:** Transitive lineage groups; uncertain independence is treated as correlated/soft.
- **Review trigger:** first multi-adapter evidence-family implementation.
- **Promotion target:** Quant Lens shared contracts and end-to-end deduplication test.

### DL-006 — Time horizon is part of valuation identity

- **Status:** confirmed at architecture level
- **Scope:** targets, intrinsic values, returns, and scenario comparisons
- **Evidence:** Dec-2027 target using FY2028 EPS versus present FCFF
- **Durable principle:** A future target and a present intrinsic are not directly comparable. Date precision must not be invented to make the arithmetic convenient.
- **Current expression:** Typed horizon coordinate and `month_label`; no present-equivalent or annualized return from an imprecise target date.
- **Review trigger:** present-equivalent design.
- **Promotion target:** shared horizon contract and UI comparison rules.

### DL-007 — Relative valuation admits market data without admitting circularity

- **Status:** confirmed at policy level
- **Scope:** peer-derived multiples
- **Evidence:** analyst and architecture convergence
- **Durable principle:** Peer prices are legitimate inputs to a relative valuation, but the subject's price/target and desired answer must not determine its peers, weights, premium, or quality.
- **Current expression:** Ex-subject frozen peer membership; fewer than five peers refuses; regression requires substantially more observations and stability evidence.
- **Review trigger:** first peer-policy implementation or empirical cohort results.
- **Promotion target:** versioned peer-policy contract, not a permanent global constant.

### DL-008 — Growth CapEx is a claim about future economics, not an add-back label

- **Status:** promoted
- **Scope:** high-reinvestment operating businesses
- **Evidence:** Amazon AI/AWS investment-wave analysis and prior valuation calibration failures
- **Durable principle:** CapEx can be treated as growth investment only when capacity, utilization, revenue, margin, depreciation/lease burden, and incremental return reconcile. Otherwise the cash outflow remains real.
- **Current expression:** `Unsupported | DiagnosticProvisional | Reconciled`; only the last state can support an adjustment.
- **Review trigger:** first CapEx-productivity implementation and multi-name holdout.
- **Promotion target:** valuation contracts/project context after empirical confirmation.

### DL-009 — Partial SOTP is not a complete equity valuation

- **Status:** confirmed at architecture level
- **Scope:** multi-segment issuers
- **Evidence:** reviewer caught accidental promotion of covered SOTP
- **Durable principle:** Missing a material component or capital bridge permits a covered enterprise-value diagnostic, not a per-share intrinsic, gap, score, or selection candidate.
- **Current expression:** Complete reconciled SOTP may be intrinsic; incomplete coverage is `CoveredEVOnly`.
- **Review trigger:** first Amazon SOTP slice.
- **Promotion target:** SOTP contract and missing-component golden.

## Durable engineering and process knowledge

### EL-001 — Inspect the brownfield before choosing the first slice

- **Status:** confirmed
- **Evidence:** the first draft assumed `EvidenceObservation` and SQLite were ready; current-reality review proved otherwise
- **Durable principle:** Architecture must start from actual DTOs, persistence semantics, runtime producers, UI read paths, and cross-platform divergences—not only from a correct domain abstraction.
- **Current expression:** Foundation 0A/0B precede feature arithmetic and UI.
- **Review trigger:** any future cross-surface valuation family.
- **Promotion target:** planning checklist.

### EL-002 — Foundations that define identity must precede persisted features

- **Status:** confirmed
- **Evidence:** initial roadmap persisted a run before stable issuer/security identity, migration runner, or canonical bytes existed
- **Durable principle:** If replay, deduplication, invalidation, or cache identity depends on a concept, that concept is not “later infrastructure”; it is a prerequisite.
- **Current expression:** Observation V2, canonical SHA-256, minimal security identity, and transactional migrations are Foundation 0.
- **Review trigger:** implementation attempts to bypass Foundation 0.
- **Promotion target:** implementation readiness gate.

### EL-003 — Append-only storage does not guarantee correct publication

- **Status:** confirmed at architecture level
- **Evidence:** adversarial review found stale `$364` could remain visible after a refused revision
- **Durable principle:** Evidence freeze, model run, current projection, invalidation, and crash recovery need an atomic application boundary. History preservation alone does not prevent stale UI truth.
- **Current expression:** Minimal Slice-1 application service atomically commits the run and projection; incompatible revisions clear current state without deleting history.
- **Review trigger:** SQLite and restart tests.
- **Promotion target:** persistence contract and UI stale-state tests.

### EL-004 — The smallest vertical slice should prove semantics, not infrastructure ambition

- **Status:** confirmed
- **Evidence:** review removed CSV, generic PDF extraction, full network coordinator, raw vault, regression, SOTP, and present-equivalent from Slice 1
- **Durable principle:** The first slice should establish one typed input, pure calculation, persistence boundary, and honest presentation. Optional formats and general platforms follow demonstrated need.
- **Current expression:** JSON only; arithmetic → metadata-only import → additive Quant Lens projection.
- **Review trigger:** scope expansion before Slice 1 gates are green.
- **Promotion target:** future feature slicing guidance.

### EL-005 — Independent review lenses find different classes of failure

- **Status:** confirmed
- **Evidence:** rubric review found CAP/SOTP/peer contradictions; reality review found DTO/migration/runtime gaps; adversarial review found replay/provenance/stale-publication hazards
- **Durable principle:** Domain coverage, brownfield reality, and adversarial divergence are complementary. A single generic review is unlikely to catch all three.
- **Current expression:** Three-lens architecture gate before `status: final`.
- **Review trigger:** measure whether implementation review finds issues that architecture lenses should have caught.
- **Promotion target:** BMAD review practice for domain-hard changes.

### EL-006 — Traceability must preserve the source contract's meanings

- **Status:** confirmed
- **Evidence:** first capability map reused CAP numbers with different labels
- **Durable principle:** A traceability table is harmful if identifiers are present but semantics drift. Copy the exact intent and attach an owner plus executable acceptance artifact.
- **Current expression:** CAP-1 through CAP-11 now map to exact SPEC intents and named gates.
- **Review trigger:** spec or architecture amendment.
- **Promotion target:** spec/architecture lint or review checklist.

### EL-007 — “Final” is an earned state

- **Status:** confirmed
- **Evidence:** initial spine was marked final before reviewers found blocking contradictions
- **Durable principle:** Drafting confidence is not approval. A domain-hard architecture becomes final only after lint, independent review, correction, and re-review.
- **Current expression:** Gate moved from `CHANGES REQUIRED` to unanimous `APPROVE` before finalization.
- **Review trigger:** any artifact marked final without review evidence.
- **Promotion target:** BMAD artifact governance.

### EL-008 — External authority and quant authority are separate

- **Status:** confirmed
- **Evidence:** team could decide model semantics but could not authorize vendor spend, report rights, retention, or encryption obligations
- **Durable principle:** Delegating technical and quant judgment does not delegate contractual, spending, privacy, or licensing authority.
- **Current expression:** Commercial providers remain behind an adapter and are absent from code until authorized.
- **Review trigger:** vendor trial or proprietary artifact storage.
- **Promotion target:** provider integration checklist.

### EL-009 — User-visible failure modes define the strongest regression tests

- **Status:** promoted from prior retrospective
- **Evidence:** T-only calibration broke AMZN/CI; weak absurd checks and quarantine initially passed
- **Durable principle:** Tests should reproduce the failure a user saw: wrong model family, penny mega-cap, inverted scenarios, stale hero value, silent refusal, or double-counted evidence. Testing constants alone is insufficient.
- **Current expression:** Multi-name baselines, closed-world routing, scoped DOM assertions, stale-projection tests, and exact parity.
- **Review trigger:** any production/live QA failure not representable in a fixture.
- **Promotion target:** already in `AGENTS.md` and project context; append new failure modes there.

### EL-010 — Demand-driven is both a performance and epistemic boundary

- **Status:** promoted
- **Evidence:** provider limits, single-user workstation architecture, prior full-universe QA incidents
- **Durable principle:** Expensive deep valuation should run only where the user expresses intent. This controls cost and rate limits while making the evidence snapshot and recomputation cause observable.
- **Current expression:** Selected symbol or explicit dossier pin; global work limited to bounded macro/filing discovery.
- **Review trigger:** background automation or watchlist implementation.
- **Promotion target:** coordinator tests and provider-budget policy.

### EL-011 — A green contract harness proves only the properties encoded in it

- **Status:** confirmed (1B-0 implemented against this lesson)
- **Observed:** 2026-08-01 checkpoint after Foundation 0A/0B and Slice 1A; reopened via 1B-0
- **Scope:** shared fixed-point contracts and SQLite ledger foundations
- **Observation:** The declared Rust gates passed (`ForwardEarningsMultiple` 12/12 and Foundation 0B 8/8), while review still found an untested Rust/Kotlin rounding-overflow divergence and missing run-to-observation/replay/identity constraints needed by Slice 1B.
- **Evidence:** pre-1B-0 review; post-1B-0 tests in `valuation_evidence`, `foundation_0b_tests`, FEM extreme goldens, Android dual-lock.
- **Durable principle:** Cross-platform and persistence gates must include adversarial boundary fixtures derived from the architecture invariants, not only representative happy-path fixtures. A passing harness cannot close a foundation whose required relationships are not expressible in the schema.
- **Current expression (after 1B-0):** Canonical `evidence_set_fingerprint` + `valuation_run_observation` membership; `share_basis_vintage` + required `identity_fingerprint` on runs; typed `ReplayMode` with certified-backfill projection refusal; FEM extreme `i64::MAX×100` parity via i128/BigInteger; baseline `transcription_claim`; `validate_for_persist` (unit/clocks/storage/quality/retrieval).
- **Counterexample search:** reconstruct set FP from membership; refuse certified projection; overflow goldens on both platforms.
- **Review trigger:** first JSON import (1B) that bypasses any of the above invariants.
- **Promotion target:** shared contracts and implementation-readiness checklist.

## Checkpoint — Foundation 0A / 0B / Slice 1A (2026-08-01)

### Confirmed strengths

- The method is a separate pure market-reference lane; FCFF router and UI/ranking remain untouched.
- The `$13.00 × 28.00 = $364.00` arithmetic and subject-price/target mutation invariance are explicit shared fixtures.
- Observation SHA-256 has one exact Rust/Kotlin golden with NFC and null-versus-empty coverage.
- Legacy SQLite snapshots and TipRanks budget survive migration in the tested fixture.
- Duplicate observation content is idempotent and conflicting IDs refuse in the tested path.

### Reopened foundation gaps (pre-1B-0) — closed 2026-08-01

| # | Gap | 1B-0 resolution |
| --- | --- | --- |
| 1 | Opaque `evidence_set_fp` | `evidence_set_fingerprint()` + `valuation_run_observation` membership; FP computed on commit, not trusted from caller |
| 2 | Incomplete identity | `share_basis_vintage` table; `identity_fingerprint` required on every model run |
| 3 | Free-text replay + projection | Typed `ReplayMode`; `certified_backfill_cannot_update_projection` |
| 4 | FEM overflow parity | Shared extreme goldens; Kotlin BigInteger half-up matches Rust i128 |
| 5 | Unverified as `reported_gaap` | Baseline + dual-lock use `transcription_claim`; partition differs from `reported_gaap` |
| 6 | Thin persist admission | `validate_for_persist`: unit↔slot, clock order, `Prohibited`, quality/retrieval tokens |

### Decision (updated by 1B-0.1 implementation)

**1B-0.1 green → GO for Slice 1B.** The write boundary now owns typed V2 validation, recomputed fingerprints/payload, seeded identity vintage match, and dual-locked evidence-set digests. Still no UI, ranking, providers, or FCFF router changes.

### EL-012 — Validation helpers are not persistence invariants until the write boundary owns them

- **Status:** confirmed (closed by 1B-0.1)
- **Observed:** 2026-08-01 checkpoint after reported closure of 1B-0; fixed in 1B-0.1
- **Scope:** typed evidence admission, canonical fingerprints, identity lineage, cross-platform evidence-set parity
- **Observation:** `validate_for_persist` existed and its unit tests passed, while `commit_valuation_run` still accepted raw tuples and stored caller-supplied fingerprint/payload. Identity was non-empty-only; `evidenceSet` fixtures were omitted from harness DTOs.
- **Durable principle:** Put validation, canonical hashing, and referential identity checks inside the narrow atomic write API. A helper invoked by convention is not an invariant; a contract field ignored by both readers is not a dual lock.
- **Current expression (1B-0.1):** `commit_valuation_run(&[EvidenceObservationV2], …)` validates + rehashes + serializes; identity rebuilt from seeded ledger must match; contract harness dual-locks `expectedSha256: sha256:0e2e8038…872de`.
- **Counterexample search (green):** invalid obs refuse; unseeded issuer refuse; wrong identity FP refuse; exact evidence-set digest on Rust and Kotlin.
- **Promotion target:** persistence API types, SQLite integrity tests, shared Rust/Kotlin contract harnesses, and implementation-readiness gates.

### EL-013 — An atomic inner commit does not make a multi-step application workflow atomic

- **Status:** confirmed (closed by 1B.1)
- **Observed:** 2026-08-02 checkpoint before Quant Lens; fixed in 1B.1
- **Scope:** evidence-to-model binding, replay, revision lifecycle, idempotent retries, current projection
- **Observation:** 1B suites passed while FEM used duplicate numerics, multi-tx lifecycle, no PIT at decision time, and no exact-import retry no-op.
- **Durable principle:** The transaction boundary must match the business invariant boundary. Inputs must be derived from the frozen evidence set; replay admission must gate publication; and retry/supersession/refusal outcomes must be one idempotent state transition, not a sequence of individually atomic writes.
- **Current expression (1B.1):** `derive_fem_input` from obs IDs; `commit_analyst_method_lifecycle` single TX; `admit_observations_for_decision`; idempotent run_id / content conflict; supersession must be current projection + same issuer/security/method; refused supersede invalidates without new run.
- **Counterexample search (green):** observation-driven EPS; operational look-ahead; exact retry no-op; stale supersedes refuse; refused revision invalidates.
- **Promotion target:** service/lifecycle API, shared import contract, pre-1C readiness gate.

### EL-014 — IDs and units bind storage; semantic roles bind valuation meaning

- **Status:** confirmed; implementation present, broader closure reopened by 1B.3
- **Observed:** 2026-08-02 after 1B.1; nominal guards implemented in 1B.2
- **Scope:** FEM evidence roles, current-projection ownership, lifecycle idempotency
- **Observation:** Observation IDs bound storage values but not economic roles; projection upsert allowed silent overwrite; idempotency omitted replay/decision/projection/supersedes.
- **Durable principle:** Reproducibility requires both byte identity and economic-role identity. A current projection is a guarded state transition, not a generic upsert; an idempotent retry must compare the full semantic command, not only the resulting number and evidence hash.
- **Current expression (1B.2):** EPS/PE metric families + lane/lineage/period; canonical projection key; `projection_occupied_requires_supersedes`; `lifecycle_fingerprint` on model run (schema v3). The explicit `role → observation_id` binding is not yet persisted or fingerprinted.
- **Counterexample search:** nominal role/refusal tests are green; still open: equal-valued alternative role bindings, per-field lifecycle mutations, and reconstruction after restart.
- **Promotion target:** import-role policy, lifecycle fingerprint, projection ownership, pre-1C readiness.

### EL-015 — A trusted rejected revision is a state transition, not a parser error

- **Status:** confirmed; promoted to project rules; **implementation present in 1B.3**
- **Observed:** 2026-08-02 autonomous retrospective after 1B.2 implementation
- **Scope:** analyst-method import, supersession, current projection, any revision-driven published view
- **Observation:** Semantic admission occurred inside full parse; errors returned before the service could invalidate a trusted superseded projection.
- **Durable principle:** Parse a minimal trusted control envelope before semantic admission. Once issuer/security/projection/supersession authority is established, the command must atomically publish the replacement or record refusal and invalidate the stale prior state. Malformed or foreign envelopes must not invalidate anything.
- **Current expression (1B.3 implemented):** `parse_control_envelope` → `assert_supersession_authority` → semantic parse; on semantic fail + trusted supersede → `refuse_superseding_revision`; foreign envelope does not invalidate.
- **Counterexample search (green builder suite):** `semantic_refusal_on_trusted_supersede_invalidates`, `foreign_envelope_does_not_invalidate`. Independent close still required.
- **Promotion target:** `AGENTS.md`, project context, application-service state machine, shared refusal contract.

### EL-016 — A fingerprint is not reconstructibility

- **Status:** confirmed; promoted to project rules
- **Observed:** 2026-08-02 autonomous retrospective
- **Scope:** run identity, input bindings, identity/share vintages, projection and supersession history
- **Observation:** The lifecycle hash covers many transition coordinates but omits explicit EPS/P-E observation bindings; run rows do not persist those roles or first-class supersession command fields. Identity rows can be updated, and the loader selects ticker/share basis through generic `ORDER BY ... LIMIT 1` instead of an exact effective vintage.
- **Durable principle:** Persist the typed components that make a decision reproducible, then hash them. An opaque digest over mutable or ambiguously selected inputs can detect some change but cannot reconstruct historical truth.
- **Counterexample search:** equal-valued alternative bindings; second ticker/share vintage; policy/split bump; restart reconstruction; typed supersession/refusal query without inspecting an arbitrary observation.
- **Promotion target:** schema, shared `RunIdentity`/binding contract, `AGENTS.md`, project context.

### EL-017 — DONE is an independent state, not a builder report

- **Status:** confirmed; promoted to project rules
- **Observed:** 2026-08-02 after successive 1B-0.1, 1B.1, and 1B.2 reopenings
- **Scope:** domain-hard valuation, ranking, evidence, persistence, and Quant Lens slices
- **Observation:** Every handoff accurately reported green tests for its named fixes, yet each independent checkpoint found a wider invariant not represented by those tests. During this retrospective, the plan briefly moved to `1B.2 DONE / 1C NEXT` while the independent review was still finding publication P0s.
- **Durable principle:** Separate `design-ready`, `implemented`, and `independently closed`. Green tests prove encoded properties; closure requires an adversarial invariant/state-transition matrix, failure/restart evidence, and reconciled durable status.
- **Counterexample search:** every semantic fingerprint field mutated individually; failpoints after each persistence phase; two-writer projection race; status artifact checked against code and executed gates.
- **Promotion target:** `AGENTS.md`, project context, implementation checkpoint template, BMAD retrospective.

### EL-018 — A correct helper is not an enforced invariant

- **Status:** confirmed; remediated and independently closed in 1B.3
- **Observed:** 2026-08-02 independent four-layer code review
- **Scope:** publication eligibility, policy/version invalidation, read models
- **Observation:** `evaluate_current_candidate_eligibility` correctly described replay, engine, policy, identity and invalidation rules, but had no production caller; the available read API returned the raw current pointer.
- **Durable principle:** A rule exists only when every state-changing or publishing path must cross it. Pure helpers without a mandatory boundary are documentation with executable examples, not enforcement.
- **Counterexample search:** repository-wide caller search; attempt to publish through every public/read API; mutate policy, engine, replay and identity independently.
- **Current expression:** `eligible_current_projection_run_id` is the production read boundary and reconstructs command, evidence, roles, identity, result, lifecycle, supersession and revision lineage; raw pointer access is test-only.
- **Promotion target:** authoritative read boundary, architecture proof matrix, code-review checklist.

### EL-019 — A row named “vintage” is not immutable history

- **Status:** confirmed; remediated and independently closed in 1B.3
- **Observed:** 2026-08-02 independent four-layer code review
- **Scope:** issuer/security identity, share basis, corporate actions, historical reconstruction
- **Observation:** run rows stored vintage identifiers, while conflict handlers updated the underlying ticker and share-basis rows in place; the identity digest also omitted ticker `effective_from`.
- **Durable principle:** Historical coordinates must be immutable by schema/write boundary and complete in canonical identity. Naming, IDs and hashes do not preserve history when referenced content can be overwritten.
- **Counterexample search:** seed two vintages, mutate every referenced field, close/reopen, and reconstruct both historical runs byte-for-byte.
- **Current expression:** identity/share vintages are insert-or-identical-no-op with immutable DB guards; identity fingerprint v2 includes ticker `effective_from`; legacy unreconstructible current rows are quarantined.
- **Promotion target:** identity schema, migration tests, project context.

### EL-020 — A mutation test must isolate the claimed variable

- **Status:** confirmed; replacement test green and independently reviewed
- **Observed:** 2026-08-02 independent four-layer code review
- **Scope:** fingerprints, idempotency, role bindings, mutation testing
- **Observation:** the role-binding conflict test changed both the role binding and evidence membership. It stayed green even if role IDs were removed from the lifecycle fingerprint, because the evidence-set fingerprint changed first.
- **Durable principle:** A counterexample proves a field is identity-bearing only when all other semantic inputs and persisted membership remain constant. Tests that vary multiple causes can pass for the wrong reason.
- **Counterexample search:** deliberately remove the claimed field from the canonical hash; the focused mutation test must turn red while unrelated tests remain green.
- **Current expression:** the role-only test holds the complete three-observation membership constant and changes only the EPS binding; lifecycle identity conflicts.
- **Promotion target:** mutation-test guidance, review checklist, lifecycle fingerprint matrix.

### EL-021 — Reconstructible lineage means the reachable graph, not the current edge

- **Status:** confirmed; remediated and independently closed in schema v8
- **Observed:** 2026-08-02 repeated independent acceptance audits of 1B.3
- **Scope:** revision ledgers, supersession graphs, append-only evidence histories
- **Observation:** Exact current-run edge checks still admitted a candidate when an upstream ancestor contained a cycle, conflicting predecessor intent, or another issuer/security partition.
- **Durable principle:** Graph integrity is transitive. A publishable descendant must validate the complete reachable ancestor subgraph: existence, one consistent predecessor intent, partition preservation, and no revisited node. Immediate-parent validation is not reconstructibility.
- **Current expression:** schema v8 guards new inconsistent inserts; the admitted read recursively revalidates the full ancestor chain and fails closed after raw corruption/reopen.
- **Counterexample search:** upstream cycle excluding the candidate, deep cross-partition ancestor, conflicting ancestor rows, missing ancestor, and extra current-edge row.
- **Promotion target:** evidence graph schema, admitted-read boundary, corruption/reopen test matrix.

### EL-022 — Diagnostic lanes must attach after primary status is frozen

- **Status:** confirmed; encoded in Slice 1C
- **Observed:** 2026-08-02 while projecting ForwardEarningsMultiple into Quant Lens
- **Scope:** Quant Lens sections, ranking, Strong, market-reference candidates
- **Observation:** A useful parallel lane (manual analyst method $364) can still poison SNR if its Provisional/Unavailable status is folded into `worst_status` primary computation, or if its value is written into legacy intrinsic/selected maps.
- **Durable principle:** Additive diagnostic candidates attach **after** `primary_status` is frozen, carry explicit `ranking_eligible=false` / `strong_eligible=false` / `diagnostic_only=true`, and never write `dcf_values`, `selected_valuation_values`, or `snapshots.intrinsic_value_cents`.
- **Counterexample search:** attach a Provisional FEM section to a Sparse report and assert primary unchanged; assert dossier load never mutates intrinsic maps; presenter refuses intrinsic selection.
- **Current expression:** `quant_lens::attach_diagnostic_sections`; `valuation_dossier_view` + `get_valuation_dossier`; Quant Lens section `manual_analyst_method`.
- **Promotion target:** Quant Lens rules, 1C read model, presenter tests.

## What went well in the current planning cycle

1. The team challenged the initial objective and replaced “match Street” with a measurable evidence/forecast/valuation architecture.
2. The brownfield audit found that much of the needed pure PIT/SOTP and demand-fetch infrastructure already exists, preventing a redundant platform build.
3. Disagreements were resolved explicitly: peer sample policy, provider trial order, manual-before-live adapter, hash identity, and automation boundaries.
4. The reviewer gate was allowed to fail. Findings were incorporated rather than rationalized away.
5. The second review reached unanimous approval with no unresolved blocker.

## What did not go well, and the saved correction

| Initial mistake | Why it was costly/risky | Saved correction |
| --- | --- | --- |
| Treated evidence v1 as ledger-ready | Missing issuer/lane/clocks and Rust/Kotlin conflict divergence could corrupt persisted truth | Define V2 and fix parity first |
| Called user transcription verified JPM evidence | Typed metadata would have manufactured authority | `fixture_transcription` / `manual_transcription_unverified` |
| Put identity and migrations after first persisted run | Run could not satisfy its own reproducibility contract | Foundation 0A/0B precede Slice 1 |
| Declared covered SOTP an intrinsic candidate | Partial EV could leak into per-share value/ranking | Incomplete means `CoveredEVOnly` only |
| Used conflicting peer minimums | Platforms could accept different candidates | One versioned boundary policy, with regression requiring larger samples |
| First slice included JSON+CSV, raw artifacts, and full coordinator | More parsers and lifecycle risk before semantics were proven | JSON-only metadata import and minimal atomic service |
| Omitted real integration seams | Risk of parallel producers and stale UI | Explicit Rust/Tauri/TypeScript/read-model ownership table |
| Marked architecture final before gate | Downstream implementation could start from contradictions | `review` until correction and unanimous re-review |
| Published malformed source links | Reduced reproducibility of data-source decisions | Verify primary-source links during review |

## Implementation observation checklist

When the implementation agent hands off work, append evidence for:

- [ ] First failing test and smallest green change for each Foundation/Slice.
- [ ] Any deviation from the architecture, with reason and whether the spec was updated.
- [ ] Rust/Kotlin conflict-parity behavior before and after the fix.
- [ ] SQLite migration on a populated legacy database, rollback, reopen, and crash/retry behavior.
- [ ] Whether a refused revision actually clears the current Quant Lens candidate after restart.
- [ ] Exact subject-price/target mutation invariance.
- [ ] Evidence-family deduplication across two adapters.
- [ ] Android core parity and Windows DOM-scoped/native/live QA under profile `qa`.
- [ ] Tests that failed unexpectedly and what hidden assumption they exposed.
- [ ] Any temporary shortcut, its containment boundary, and removal trigger.

## New-entry template

```markdown
### XX-NNN — Short lesson

- **Status:** candidate
- **Observed:** YYYY-MM-DD
- **Scope:** names/models/providers/platforms
- **Observation:** what happened, without interpretation
- **Evidence:** paths, tests, run IDs, screenshots, or provider samples
- **Interpretation:** why it happened
- **Durable principle:** portable rule
- **Current expression:** implementation/policy today
- **Counterexample search:** what would disprove or narrow the lesson
- **Review trigger:** event that requires reconsideration
- **Promotion target:** contract / architecture / project-context / AGENTS / operator docs / tests
```

## Next formal review

This is a partial retrospective because implementation is active. Do not mark any epic retrospective complete from this document. Run the formal implementation review when the agent hands off code and verification evidence; then:

1. confirm, narrow, reject, or supersede the candidate lessons;
2. compare planned versus actual architecture;
3. promote only standing rules into `project-context.md`, `AGENTS.md`, contracts, or operator docs;
4. leave implementation-specific discoveries here with their evidence and review triggers.

## Source artifacts

- [Analyst-method automation architecture](../planning-artifacts/architecture/architecture-analyst-method-automation-2026-08-01/ARCHITECTURE-SPINE.md)
- [Automation roadmap](../planning-artifacts/architecture/architecture-analyst-method-automation-2026-08-01/AUTOMATION-ROADMAP.md)
- [Prior valuation calibration retrospective](retro-valuation-calibration-session-2026-07-30.md)
- [Project context](../project-context.md)
