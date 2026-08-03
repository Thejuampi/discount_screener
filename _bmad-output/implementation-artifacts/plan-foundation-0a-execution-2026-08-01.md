# Foundation 0A → 0B → 1A → 1B-0 execution log — 2026-08-01

## Plan source

- Party-mode architecture: `architecture-analyst-method-automation-2026-08-01`
- Learning ledger EL-011 / checkpoint reopening pre-1B import
- Decision: no price-chasing; TDD; no regressions; no import until 1B-0 green

## Status

| Task | Status |
| --- | --- |
| 0A EvidenceObservationV2 + SHA-256 dual-lock | **DONE** |
| 0B Identity + SQLite migrations + atomic ledger | **DONE** (superseded schema by 1B-0 v2) |
| 1A Pure `$13 × 28 = $364` FEM dual platform | **DONE** |
| **1B-0** Hardening before persistent evidence | **DONE — 1B-0.1 verified** |
| 1B Typed JSON import + ledger fill | **DONE** |
| **1B.1** Atomic/PIT/idempotent/supersession | **DONE** |
| **1B.2** Semantic binding + lifecycle identity | **SUPERSEDED AND CLOSED BY 1B.3** |
| **1B.3** Publication eligibility + reconstructible lifecycle | **INDEPENDENTLY CLOSED** (schema v8) |
| **1C** Quant Lens candidate (no ranking) | **IMPLEMENTED** (diagnostic-only projection) |

## 1B-0 deliverables

| # | Invariant | Implementation |
| --- | --- | --- |
| 1 | Reproducible evidence set | `evidence_set_fingerprint` (sorted unique obs FPs); `valuation_run_observation` membership; commit **computes** set FP |
| 2 | Complete identity on run | `share_basis_vintage` table; `identity_fingerprint` NOT NULL on `valuation_model_run`; seed via `upsert_identity_bundle` |
| 3 | Replay fail-closed | Typed `ReplayMode`; certified backfill **cannot** pass `projection_key` |
| 4 | Overflow parity | FEM extreme: `i64::MAX × 100 → MAX`; `× 200 → overflow`; Kotlin `BigInteger` |
| 5 | Transcription semantics | Baseline obs `metricBasis=transcription_claim` (digest `sha256:18ad8a23…f8b1`); ≠ `reported_gaap` partition |
| 6 | Persist admission V2 | `validate_for_persist`: unit↔slot, clock order, `storage_prohibited`, quality/retrieval tokens |

Schema: `user_version = 2` (v1 ledger + v2 membership/share basis/identity column).

## Gates verified (1B-0)

```text
cargo test --lib valuation_evidence          # 22 passed (incl. contract harness)
cargo test --lib foundation_0b               # 11 passed
cargo test --lib forward_earnings_multiple   # 14 passed (incl. extreme + contract)
cargo test --lib evidence_sotp               # 15 passed
cargo test --lib dcf_model::                 # 41 passed
cargo test --lib valuation_baseline::        # 10 passed (+1 ignored)
./gradlew :core:test --tests ValuationEvidenceV2* --tests ForwardEarningsMultiple*
```

## Next: Slice 1B

1. Typed JSON → `EvidenceObservationV2` with `validate_for_persist` before write
2. Quality labels: `manual_transcription_unverified` / `fixture_transcription` (quality token remains provisional/soft/solid; source claim lives in metric basis + extraction)
3. FEM compute → `commit_valuation_run` with identity vintage FP + frozen membership
4. Revision edges + projection invalidation append-only
5. Still no UI / ranking / providers / FCFF

## Checkpoint 1B-0.1 — **GO for Slice 1B**

Closed the three reopened invariants:

| Gap | Resolution |
| --- | --- |
| Caller-trusted FP/payload | `commit_valuation_run(&[EvidenceObservationV2], …)` validates, **recomputes** each FP, serializes payload itself |
| Declarative identity | Requires seeded issuer/security + share basis; rebuilds vintage FP from ledger; `identity_fingerprint_mismatch` / `issuer_not_seeded` / `security_not_seeded` |
| Evidence-set dual-lock | Contract `fixtures.evidenceSet` with `expectedSha256: sha256:0e2e8038…872de`; Rust + Kotlin harnesses assert exact digest |

Negatives green: invalid obs (`storage_prohibited`), unseeded identity, wrong identity FP, certified projection refuse.

### Gates (1B-0.1)

```text
cargo test --lib foundation_0b        # 13 passed
cargo test --lib valuation_evidence   # 23 passed (incl. evidence_set contract)
cargo test --lib evidence_sotp / dcf_model:: / valuation_baseline::  # no regression
./gradlew :core:test --tests ValuationEvidenceV2*
```

**Decision:** GO to Slice 1B JSON import on this boundary. No further foundation planning required for this gate.

## Slice 1B deliverables (2026-08-01)

| Piece | Module |
| --- | --- |
| Contract | `shared/contracts/valuation-forward-earnings-import-v1.json` |
| Pure parse/admit | `analyst_method_import.rs` + Kotlin `AnalystMethodImport` |
| Application service | `analyst_method_service::commit_analyst_method_import` |
| Revision edges | `Db::append_revision_edges` |
| Projection invalidation | `Db::invalidate_current_projection` (append-only + clear current) |
| Golden | fixture `$13×28=$364` end-to-end commit + membership reconstructible |

### Gates (1B)

```text
cargo test --lib analyst_method     # 7 passed (import + service)
cargo test --lib foundation_0b      # 13 passed
cargo test --lib evidence_sotp / dcf_model:: / valuation_baseline::
./gradlew :core:test --tests AnalystMethodImport* --tests ForwardEarningsMultiple*
```

**Stop:** no UI, ranking, providers, FCFF. Checkpoint 1B.1 below must close before **1C**.

## Checkpoint 1B.1 — reopened before Quant Lens projection (2026-08-02)

The declared suites are green, but review found that the tested happy path does not yet satisfy the Slice-1 atomic/replay contract:

1. **Evidence does not own FEM inputs.** `fem.epsCents` and `fem.multipleHundredths` are copied into the engine independently from the frozen observations. A document can persist one EPS/multiple evidence set while computing another value. Bind the computation to explicit observation IDs and derive/validate metric, unit, value, currency, basis and horizon from those observations.
2. **The application operation is not atomic.** Projection invalidation commits first, the model run/membership commits second, and revision edges commit afterward. A failure or crash can leave a cleared projection without the replacement run, or a published run without its revision lineage. Move the whole lifecycle behind one SQLite transaction.
3. **Replay is typed but not enforced at the service boundary.** `created_at_unix_ms` is not used as `decision_at` for `admit_observation`; an operational test currently succeeds with observation clocks far later than the run time. Refuse look-ahead before live publication and derive the FEM observation time from admitted evidence.
4. **Retry/idempotency is incomplete.** Re-submitting the exact same import/run hits the run primary key instead of returning the existing identical result as a no-op. Add a canonical run identity/fingerprint and refuse only when the same run ID has different content.
5. **Supersession is not scoped fail-closed.** The service does not prove that `supersedesRunId` is the current run for that exact projection/issuer/security/method before clearing it; refused/incompatible typed revisions also return before append-only invalidation. Model successful replacement and refused-revision invalidation explicitly and atomically.

### 1B.1 closed (2026-08-02)

| Gap | Resolution |
| --- | --- |
| FEM duplicated numerics | `fem.epsObservationId` / `multipleObservationId` → derive cents/hundredths from frozen obs |
| Multi-tx lifecycle | `Db::commit_analyst_method_lifecycle` one SQLite transaction |
| Replay not enforced | `admit_observations_for_decision(decision_at=created_at)` before publish |
| Retry collide | identical content → `idempotent_replay`; different → `run_id_content_conflict` |
| Supersession unscoped | must be current projection + same issuer/security/method; refused supersede invalidates without new run |

```text
cargo test --lib analyst_method   # 15 passed
```

No Tauri live QA until 1C UI/read surface.

## Checkpoint 1B.2 — residual pre-1C boundary (2026-08-02)

1B.1 correctly closed its five named gaps and its independent gates are green. The pre-UI detail pass found three remaining ways for a technically valid import to publish the wrong current candidate:

1. **Semantic roles are not validated.** `epsObservationId` accepts any `money_cents` observation and `multipleObservationId` accepts any `multiple_hundredths` observation. For `analyst_stated`, require the EPS metric family, forward-P/E metric, compatible forecast period/basis, and the same analyst-method lineage; a revenue-per-share or unrelated multiple must refuse.
2. **A non-superseding run can overwrite an occupied projection.** The lifecycle upsert permits a new `runId` with no `supersedesRunId` to replace any existing `projectionKey`, producing no invalidation edge. Projection keys must be derived/scoped to issuer/security/method, and an occupied projection requires an explicit valid supersession.
3. **Idempotent content identity is incomplete.** Existing-run equality compares evidence set, result, identity and issuer/security, but omits method/engine/policy, replay mode, decision instant, projection key and supersedes command. Persist and compare a canonical lifecycle/run fingerprint so only the exact same transition is a no-op; any semantic mutation with the same `runId` conflicts.

Required red→green counterexamples: non-EPS money observation; non-P/E or different-lineage multiple; period mismatch; new run without supersedes cannot replace an occupied projection; cross-issuer/arbitrary projection key refuses; same `runId` with changed replay/decision/projection/supersedes/engine-policy conflicts while the exact retry remains a no-op.

### 1B.2 implementation present (2026-08-02)

| Gap | Resolution |
| --- | --- |
| Any money_cents as EPS | EPS metric family + analyst lane; PE metric family; period + lineage + basis match |
| Silent projection overwrite | Canonical key `proj:{issuer}:{security}:{method}`; occupied requires supersedes; INSERT not upsert |
| Incomplete idempotency | `lifecycle_fingerprint` includes evidence set, result, identity, method/policy/engine, replay, decision_at, projection key, supersedes |

```text
cargo test --lib analyst_method   # 22 passed
cargo test --lib foundation_0b    # schema v3
```

The three named fixes and their targeted tests are green. This establishes **implemented**, not **independently closed**. The autonomous retrospective found a broader publication boundary still open, so the earlier GO is superseded by checkpoint 1B.3 below.

## Checkpoint 1B.3 — publication eligibility and reconstructible lifecycle (2026-08-02)

1. **Semantic refusal can bypass invalidation.** `parse_analyst_method_import_json` performs economic-role and period validation before the service owns the supersession transition. A trusted superseding revision that fails there returns without `refuse_superseding_revision`, leaving the stale prior projection current. Split control-envelope admission from semantic admission so a trusted revision ends atomically in replacement or invalidation, while malformed/foreign envelopes cannot invalidate another projection.
2. **Lifecycle identity omits the economic binding.** The fingerprint includes the evidence set and result, but not `epsObservationId` / `multipleObservationId` or an equivalent persisted role map. Alternative observations with equal values can change the economic binding while producing the same set/result and be accepted as an idempotent retry. Persist and fingerprint explicit role bindings.
3. **Run identity is not fully reconstructible or vintage-addressed.** Runs store an opaque identity fingerprint while identity rows are mutable; `load_identity_fingerprint_for_security` selects ticker/share basis with `ORDER BY ... LIMIT 1` rather than an explicit as-of vintage. Projection/supersession intent is hashed but not stored as first-class run-command data. Persist immutable identity/share-basis references and the command coordinates required to rebuild the lifecycle.
4. **Current eligibility is incomplete.** A pointer can remain current after an engine/policy bump or split/share-basis revision. Define a versioned `CurrentCandidateEligibility` rule and refuse/clear stale candidates on read or invalidation.
5. **Horizon and currency remain under-typed.** Non-empty date strings can be nonsensical, and evidence currency is not reconciled to the security currency. Add exact Rust/Kotlin refusals for invalid precision/date/calendar relationships and currency mismatch.
6. **Proof coverage is narrower than the claim.** Add shared Rust/Android semantic negatives; one test per lifecycle field mutation; retry-after-supersession state; mid-transaction failure/rollback/reopen; corrupted membership/result/fingerprint reconstruction; and proof that the generic DB writer cannot bypass the analyst-method service.

**Exit rule:** 1B.3 is closed only after an independent reviewer walks the invariant/attack matrix and the durable status is reconciled. 1C may be prepared as a non-publishing DTO/query skeleton, but no current candidate may reach Quant Lens before this gate.

### 1B.3 implementation present (2026-08-02) — not independently closed

| P0 | Implementation |
| --- | --- |
| Semantic refuse bypasses invalidate | `parse_control_envelope` first; trusted supersede authority asserted; semantic fail → `refuse_superseding_revision`; foreign envelope no-op |
| Role binding not identified | `valuation_run_role_binding` + `eps_observation_id`/`multiple_observation_id` on run; in `lifecycle_fingerprint` v2 |
| Reconstructible identity | Exact share_basis/ticker/identity_vintage selection; command coords columns schema **v4** |
| Eligibility helper | `evaluate_current_candidate_eligibility` pure rule |
| Horizon/currency | ISO date / month_label checks; currency vs security master |

```text
cargo test --lib analyst_method   # 19 passed (incl. semantic supersede invalidate + role binding conflict)
cargo test --lib foundation_0b    # schema v4
```

Status: **implemented**. Independent reviewer must still mark **independently closed** before 1C publish.

### Independent adversarial review findings (2026-08-02)

Review mode: full BMAD code review. Layers completed: blind hunter, edge-case hunter, verification-gap audit, and acceptance audit. Builder gates were independently reproduced, but green tests did not establish the publication invariants below. Triage retained 8 patches and dismissed 7 duplicate, unreachable, or already-refused reports.

- [x] [Review][Patch][High] Make current-candidate eligibility the only publication read boundary; the pure helper is currently uncalled and the public reader returns the raw pointer without engine, policy, replay, identity-vintage, or invalidation admission. [`apps/windows/src-tauri/src/analyst_method_import.rs`; `apps/windows/src-tauri/src/db.rs`]
- [x] [Review][Patch][High] Make identity vintages immutable and complete: `upsert_identity_bundle` mutates rows referenced by historical runs, while the identity digest omits ticker `effective_from`. [`apps/windows/src-tauri/src/db.rs`; `apps/windows/src-tauri/src/issuer_identity.rs`]
- [x] [Review][Patch][High] Quarantine/invalidate legacy current projections during migration when role bindings and exact vintage coordinates cannot be reconstructed; empty defaults must not remain publishable. [`apps/windows/src-tauri/src/db.rs`]
- [x] [Review][Patch][High] Remove the generic run writer as a bypass around the authoritative analyst lifecycle boundary, and validate that persisted EPS/P-E role IDs are distinct members with the required economic roles. [`apps/windows/src-tauri/src/db.rs`]
- [x] [Review][Patch][High] Persist refused trusted revisions as reconstructible append-only attempts (run/envelope/payload digest/reason), atomically with clearing the stale current projection. [`apps/windows/src-tauri/src/db.rs`; `apps/windows/src-tauri/src/analyst_method_service.rs`]
- [x] [Review][Patch][High] Complete trusted-supersede fail-closed behavior: quality/replay validation occurs after authority; deterministic refusals invalidate, while infrastructure/race failures remain non-destructive. [`apps/windows/src-tauri/src/analyst_method_import.rs`; `apps/windows/src-tauri/src/analyst_method_service.rs`]
- [x] [Review][Patch][Medium] Separate the stable semantic `decisionAtUnixMs` from processing time so identical later transport retries no-op while a decision-only mutation conflicts. [`apps/windows/src-tauri/src/analyst_method_import.rs`]
- [x] [Review][Patch][Medium] Finish Rust/Kotlin horizon parity and shared counterexamples, including real calendar dates and date precision validation. [`apps/windows/src-tauri/src/analyst_method_import.rs`; `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/AnalystMethodImport.kt`]

Independent verdict: **NOT CLOSED**. 1C remains **NO-GO** until every high/medium review item is patched, mutation-resistant counterexamples pass, and an independent re-review closes this checkpoint.

### 1B.3 independent closure (2026-08-02)

The initial eight patches were applied automatically, then four independent acceptance passes found and closed further reachable counterexamples instead of accepting builder self-certification:

1. semantic decision instant, canonical full-command identity, and explicit EPS share-basis attestation;
2. complete admitted-read reconstruction plus real two-connection SQLite CAS/reopen proof;
3. exact candidate revision-edge reconstruction and append-only guards;
4. recursive ancestor-lineage validation for missing ancestors, upstream cycles, conflicting predecessor intent, and cross-issuer/security partitions.

Final state:

| Gate | Result |
| --- | --- |
| SQLite schema | `user_version = 8` |
| Rust `foundation_0b` | 23/23 |
| Rust `analyst_method` | 32/32 |
| Rust evidence / FEM / SOTP | 23/23 · 14/14 · 15/15 |
| Rust DCF / multi-name baseline / Quant Lens | 41/41 · 10/10 (+1 ignored live) · 8/8 |
| Android import/FEM/shared contract | `BUILD SUCCESSFUL` |
| Formatting | `rustfmt --check` and `git diff --check` clean |
| Independent acceptance audit | **CLOSED — no high/medium publication gap in 1B.3 scope** |

The full Android core run executed 332 tests with one pre-existing/concurrent unrelated failure in `ContractFixtureTest.valuation_model_family_policy2_fixtures_execute_against_core`; the focused analyst-method contract gate is green. No Tauri/UI QA was run because 1C was not started and nothing is published to Quant Lens.

Status: **INDEPENDENTLY CLOSED**.

## Slice 1C — additive dossier projection (2026-08-02)

Diagnostic-only market-reference lane projected into Quant Lens. No ranking, Strong, FCFF coupling, or legacy intrinsic scalar writes.

| Piece | Module |
| --- | --- |
| Read model | `valuation_dossier_view.rs` — `ValuationDossierView` / `AnalystMethodCandidateView` |
| Publication read | `Db::load_analyst_method_publication` (eligibility + reconstruction) |
| Tauri | `get_valuation_dossier`, `get_quant_lens` attaches diagnostic section without rewriting `primary_status` |
| Seed (native E2E only) | `debug_seed_amzn_analyst_method_e2e` under `DS_NATIVE_E2E=1` |
| TS | `api.ts` types, `analystMethodPresentation` presenter, Quant Lens metric labels + `data-ql-section` |
| Native E2E | `npm run test:e2e:native:amzn-fem` |

Guarantees:

- UI labels **manual analyst method**; surfaces metric claim, forecast period, month-precision target horizon, source verification, refusal.
- `ranking_eligible = false`, `strong_eligible = false`, `diagnostic_only = true`.
- Run never enters `dcf_values` / `selected_valuation_values` / `snapshots.intrinsic_value_cents`.
- Quant Lens `attach_diagnostic_sections` freezes `primary_status` before the FEM section is appended.
- Stale/ineligible pointer → `Unavailable` with reason; invalidated projection → `Absent` (no section noise).

### Gates (1C)

```text
cargo test --lib valuation_dossier_view   # 4 passed
cargo test --lib quant_lens               # 9 passed (incl. diagnostic primary freeze)
cargo test --lib analyst_method / foundation_0b / dcf_model:: / valuation_baseline::
node --test tests/analystMethodPresentation.test.ts
# optional after debug build:
# npm run test:e2e:native:amzn-fem
```

Status: **IN PROGRESS — adversarial re-review (BMAD code-review)**. Not independently closed until open high/medium items below are resolved and gates re-run.

### Review Findings — BMAD code-review re-triage (2026-08-02, current tree)

Layers: blind-hunter, edge-case-hunter, verification-gap, acceptance-auditor. Several prior High items were **already remediated** in the working tree (Quant Lens uses `publication_read_failure_dossier` on Err; string cents; presenter pins `source_not_verified`; dossier poll + attach path). Findings below are the residual set after reading call sites.

#### Decision-needed

- [ ] [Review][Decision] **1C independent-close gate bar** — Architecture First Shippable Slice still lists shared Android dual-lock, `scripts/validate-android.ps1`, **mandatory** native DOM E2E, and live `qa` QA. Plan still marks native E2E optional. Choose: (A) full architecture bar before closed, (B) Windows-only close with documented Android defer, (C) leave implemented not closed until operator QA. [AUTOMATION-ROADMAP Slice 1C; plan Gates]

- [ ] [Review][Decision] **Publication signal: event vs poll-only** — Roadmap asks for event/poll; product currently uses 15s poll only. Choose: (A) add Tauri event on publish/invalidate, (B) accept poll-only for 1C with explicit architecture note. [`QuantLensPanel.tsx` interval; roadmap 1C]

#### Patch

- [ ] [Review][Patch][High] Sanitize `get_valuation_dossier` failures with `publication_read_failure_dossier` (mirror `get_quant_lens`); never return raw SQLite/infrastructure strings over IPC. [`commands.rs:1369-1373`]
- [ ] [Review][Patch][High] Fix native DOM E2E token mismatch: assert `fixture_transcription` / `source_not_verified` as rendered (not `/fixture transcription/` with spaces); keep string cents. [`amzn-analyst-method.native.e2e.mjs:168-169`]
- [ ] [Review][Patch][Medium] Align dual FEM section builders: frontend `analystMethodQuantLensSection` summary must retain diagnostic-only / not-ranking wording consistent with backend, or make one builder authoritative end-to-end. [`detailValuationPresentation.ts:180-184`; `valuation_dossier_view.rs:477-486`]
- [ ] [Review][Patch][Medium] Strengthen primary freeze proof: unit test with Strong (or better-than-Provisional) base + FEM Provisional via `attach_diagnostic_sections` / command-shaped path; current quant_lens test uses Sparse/Insufficient so a mistaken recompute of `worst_status` would still pass. [`quant_lens.rs:1369-1389`]
- [ ] [Review][Patch][Medium] Prove no legacy intrinsic mutation beyond `snapshot_count==0`: before/after seed or dossier/quant-lens read, assert screener/detail `dcf`/`selected`/`intrinsic` maps unchanged. [`valuation_dossier_view.rs` restart test; `commands.rs` seed]
- [ ] [Review][Patch][Medium] When `get_quant_lens` fails, do not blank the whole panel if dossier FEM is Available — surface diagnostic lane or fail per-section. [`QuantLensPanel.tsx:111-112`]
- [ ] [Review][Patch][Medium] Clear or re-fetch dossier when quant-lens refresh no longer includes the lane / after invalidation so a stale Available presentation cannot reattach. [`QuantLensPanel.tsx:29-31`, dossier poll]
- [ ] [Review][Patch][Medium] Refuse Available quant-section construction when money fields are missing (no `unwrap_or("0")` / zero multiple). [`valuation_dossier_view.rs:461-464`]
- [ ] [Review][Patch][Medium] Stop inventing `scenario: "base_reference"` unless persisted on the run/result envelope. [`valuation_dossier_view.rs:356`]
- [ ] [Review][Patch][Low] Humanize provenance with full underscore replace (`source_not_verified` → `source not verified`). [`valuation_dossier_view.rs:486`]
- [ ] [Review][Patch][Low] Seed: fail if fixture `decisionAtUnixMs` missing instead of silent default. [`commands.rs:1416-1418`]

#### Deferred (pre-existing / out of 1C residual)

- [x] [Review][Defer] Quant Lens still runs demand FCFF/model routing for the core report — pre-existing Detail/QL path, not introduced as FEM ranking write. [`commands.rs:1327-1328`] — deferred, pre-existing
- [x] [Review][Defer] Untyped `QuantLensSection` allows a future caller to re-run `worst_status` over diagnostic sections — structural risk; address with typed diagnostic flag when section model is next revised. [`quant_lens.rs:78-83`] — deferred, structural

#### Dismissed this pass (already fixed or false positive on current tree)

- Quant Lens `Err` → empty extras (now uses `publication_read_failure_dossier`)
- E2E numeric `36400` vs string (now asserts `"36400"`)
- Presenter test using `fixture_transcription` as sourceVerification (now `source_not_verified`)
- Missing dossier poll / presenter wiring (poll + `attachAnalystMethodPresentation` present)
- Incomplete TS dossier surface for clocks/identity (fields present on `api.ts` contract)

## Non-goals still respected

- No provider purchase
- No `if AMZN`
- No Quant Lens ranking / Strong from FEM
- No FCFF router coupling
