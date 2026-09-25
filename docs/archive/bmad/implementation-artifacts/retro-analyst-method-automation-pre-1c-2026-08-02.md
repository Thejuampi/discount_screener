# Retrospective: analyst-method automation foundation through pre-1C

**Date:** 2026-08-02  
**Scope:** Foundation 0A/0B, Slices 1A/1B/1B.1, implementation of the named 1B.2 fixes, and the independent publication-readiness checkpoint before Quant Lens 1C  
**Format:** Autonomous BMAD party retrospective; systems and evidence, not blame  
**Decision:** **NO-GO for a publishable 1C candidate until checkpoint 1B.3 closes**

This is a slice retrospective, not a sprint epic. The repository's `sprint-status.yaml` tracks an older Valuation Change Visibility initiative and was deliberately not rewritten to manufacture an unrelated epic status.

## Participants

- **Facilitator / independent reviewer:** Codex root
- **Mary:** Quant and domain analyst
- **Winston:** system and data architect
- **Amelia:** senior developer and QA reviewer
- **Juan:** product owner; delegated technical and Quant decisions, while retaining authority over spending, licensing, rights, and material scope changes

## Executive result

The team chose the right financial architecture and built useful foundations. Forward Earnings Multiple remains a distinct market-reference instrument, `$13 × 28 = $364` is deterministic and price-independent, evidence is typed and fingerprinted, and the normal successful lifecycle has PIT admission, one SQLite transaction, explicit supersession, and fixed-point Rust/Kotlin parity.

The process still produced successive false closure claims. Each implementation handoff was truthful about the tests it ran, but **DONE was inferred from the known counterexamples instead of proving the complete publication state machine**. The independent retrospective found additional P0 failures after the plan had already moved to `1B.2 DONE / 1C NEXT`.

The central lesson is portable: **green tests establish implementation evidence; they do not establish that every required invariant was encoded.** Domain-hard work moves through three distinct states:

1. `design-ready` — decisions and proof obligations are specified;
2. `implemented` — code satisfies the encoded tests;
3. `independently closed` — an adversarial review proves the full state transition, failure, restart, and reconstruction contract.

## Party review

Mary (Quant Analyst): “The arithmetic and method identity are correct. The remaining question is not whether `$13 × 28` works; it is whether the resulting candidate is economically eligible to remain current.”

Winston (System Architect): “Architecture approval was correctly limited to readiness. The mistake was treating green implementation suites as proof that every architecture gate—rollback, restart, reconstruction, invalidation, and lineage—had been discharged.”

Amelia (Senior Developer): “The tests demonstrate their named properties. They do not yet prove publish-or-invalidate for every refusal, role-binding identity, immutable vintage reconstruction, or crash safety.”

Facilitator (Independent Reviewer): “No individual caused this pattern. The system let a builder report promote itself to closure without a separate attack matrix and durable-status reconciliation.”

## What went well

1. **Financial method identity stayed honest.** FEM was not inserted into the FCFF router, blended into intrinsic value, used for ranking, or tuned from the subject price.
2. **Arithmetic is exact and generic.** The Amazon transcription and a synthetic issuer use the same fixed-point engine; rounding, overflow, and price/target mutation invariance are covered.
3. **Evidence foundations materially improved.** Observation V2 adds identity, clocks, lanes, lineage, units, storage disposition, canonical SHA-256, and shared evidence-set fingerprints.
4. **Known lifecycle faults were fixed.** Inputs now derive from observations; the successful flow uses one transaction; operational look-ahead refuses; exact retries no-op; occupied projections require supersession; and nominal EPS/P-E roles are validated.
5. **Scope discipline held.** No provider purchase, PDF extraction, peer-derived multiple, ranking, `Strong`, UI publication, ticker branch, or FCFF coupling entered the slice.
6. **Independent checkpoints paid for themselves.** 1B-0.1, 1B.1, 1B.2, and this retro each caught a different boundary error before UI publication.
7. **Prior valuation retro actions largely held.** Multi-name baselines, fail-closed business routing, refusal visibility rules, zero-quarantine policy, and profile-`qa` operating discipline are now durable project rules.

## Verification observed during the retro

| Gate | Result |
| --- | --- |
| Rust `analyst_method` | 22 passed |
| Rust `foundation_0b` | 13 passed; schema v3 |
| Rust `valuation_evidence` | 23 passed |
| Rust FEM | 14 passed |
| Rust `evidence_sotp` | 15 passed |
| Rust `dcf_model::` | 41 passed |
| Rust `valuation_baseline::` | 10 passed, 1 live test ignored |
| Android focused evidence/FEM/import tests | `BUILD SUCCESSFUL` |

The normal Cargo command was initially blocked by the already-running long-lived Tauri Cargo process. The current compiled library test harness was then executed directly. This is operational evidence, not a substitute for the missing tests listed below.

## Timeline: why “green” kept reopening

| Reported closure | What the tests proved | What independent review later found |
| --- | --- | --- |
| 0A/0B/1A | Happy-path hashes, persistence, arithmetic | Evidence-set membership, complete identity, replay typing, overflow parity, transcription authority, persist admission |
| 1B-0 | Helpers and new schema concepts | Write API still trusted caller payload/fingerprint; identity was declarative; a shared fixture was ignored by both readers |
| 1B | Typed import and successful commit | Duplicate FEM numerics, three transactions, replay not enforced at decision time, non-idempotent retry, loose supersession |
| 1B.1 | Five named lifecycle counterexamples | Economic roles, projection ownership, and full transition identity remained incomplete |
| 1B.2 implementation | Nominal roles, canonical key, occupied projection refusal, decision-time conflict | Semantic refusal can bypass invalidation; role bindings are absent from run identity; identity vintages and run transitions are not fully reconstructible |

## Findings that block publication

### P0-1 — Semantic refusal can leave stale current truth

The application performs full semantic parsing before the service owns the supersession transition. Revenue used as EPS, a non-P/E multiple, lineage/basis/period mismatch, or a storage refusal can return before `refuse_superseding_revision` runs. The old candidate therefore remains current even though a trusted later revision was rejected.

Required invariant: after a minimal control envelope proves issuer/security/projection/supersession authority, a trusted revision ends atomically in exactly one of two states:

- replacement candidate published; or
- rejected attempt recorded and prior current candidate invalidated.

A malformed, foreign, non-current, or unauthorized envelope must refuse without invalidating legitimate state.

### P0-2 — Economic role binding is validated but not identified

Membership records which observations belong to a run, but not which observation served as `forward_eps` or `forward_pe`. The lifecycle fingerprint omits `epsObservationId` and `multipleObservationId`. With alternative equal-valued observations in the same set, a changed binding can preserve both evidence-set fingerprint and result and be accepted as an exact retry.

Required invariant: persist a typed `semantic_role → observation_id` binding and include it in canonical run identity. Evidence membership and consumed inputs are related but not interchangeable concepts.

### P0-3 — Historical run identity is not fully reconstructible

Runs retain an opaque identity hash but do not persist every coordinate required to rebuild the command. Identity seeding updates rows, while selection uses generic `ORDER BY ... LIMIT 1` instead of the exact vintage effective at `decision_at`. Supersession is encoded indirectly in an observation-edge table rather than a first-class typed run transition.

Required invariant: identity/share/corporate-action vintages are immutable and explicitly referenced by the run; role bindings, projection tuple, supersession/refusal command, method/engine/policy, replay, decision and horizon coordinates are persisted and canonicalized.

### P0-4 — Current is a pointer, not a complete eligibility decision

A run may remain current after engine/policy or split/share-basis changes. Exact retry acceptance also does not mean that a previously superseded or invalidated run is current.

Required invariant: `CurrentCandidateEligibility` is a versioned rule over operational replay, invalidation state, canonical issuer/security/method scope, current engine/policy, exact identity/share vintage, intact membership and reconstructible provenance.

### P0-5 — The promised failure and parity gates are incomplete

The current shared import contract does not concentrate all new semantic refusals, Android coverage is narrower than Rust, and no test injects failure after each lifecycle write then closes/reopens the database. There is no two-writer projection race test or full per-field lifecycle mutation matrix.

Required invariant: the same shared fixtures prove cross-platform semantic policy; Windows persistence tests prove all-or-nothing, restart, migration and compare-and-swap behavior.

## Additional material findings

- Horizon and date precision remain strings with incomplete syntax/calendar validation.
- Evidence currency is reconciled between EPS and multiple, but not against the security currency.
- Revision edges lack complete typed graph guarantees for existence, partition, self/cycle and cross-issuer relationships.
- The semantic policy changed while the version label remains `fem-policy-v1`.
- A generic public DB writer can bypass analyst-method rules if a future caller uses it directly.
- `result_json` alone lacks the lineage, metric, provenance and source coordinates that 1C needs; the dossier must join current projection → run → role bindings/membership → observations.
- Rejected attempts do not yet preserve enough command/evidence identity for audit and replay.
- Most lane files are untracked and coexist with unrelated FCFF work, so the claimed red→green history and slice isolation cannot be independently reconstructed from Git.

## Root causes: systems, not people

### Five whys

1. **Why did new failures appear after every green handoff?** Tests covered the latest known examples.
2. **Why did examples not cover the class?** The slice had no complete state-transition and invariant partition matrix.
3. **Why was the matrix absent?** Lifecycle ownership was spread across parser, service, generic DB methods, mutable identity rows, and read projections instead of one typed aggregate.
4. **Why did that still become DONE?** Closure used deliverable lists and test counts rather than proof obligations, failure injection, restart reconstruction, and independent review.
5. **Root cause:** the process lacked a distinct, independently owned closure state and a canonical manifest of publication invariants.

### Terminology was accepted without proof obligations

The words `typed`, `atomic`, `idempotent`, `evidence-bound`, `reconstructible`, `dual-lock`, and `fail-closed` were used correctly in a local sense but interpreted too broadly. They now have explicit repository definitions in `AGENTS.md` and `project-context.md`.

## Previous retrospective follow-through

| Prior lesson/action | Result | Evidence |
| --- | --- | --- |
| Multi-name baseline before valuation closure | Followed | DCF and valuation baseline gates remained green throughout this separate lane |
| Closed-world routing and refusal over invented value | Followed | FEM remained separate; no FCFF fallback or ticker exception |
| UI must expose refusal/stale truth | Not yet applicable to 1C, now a critical gate | This retro caught stale-current behavior before UI publication |
| Tests must match user-visible failure modes | Partially followed | Good nominal counterexamples; missing complete transition/failure partitions |
| Policy versions are cache-invalidation contracts | Partially followed | Lifecycle hashes policy, but semantic policy changed without a new version and current eligibility is not revalidated |
| Actual work should be tracked, not fake sprint status | Followed in this retro | Content-first slice retro; unrelated sprint-status left untouched |

## Durable definition of DONE

A domain-hard Foundation/Slice is independently closed only when all apply:

1. Every required invariant is owned by a type, schema constraint, authoritative write boundary, or shared contract—not caller convention.
2. The invariant/attack matrix partitions success, refusal, retry, supersession, invalidation, migration, concurrency, crash, restart and corrupted-state behavior.
3. Run membership and typed economic input bindings are persisted and reproducible.
4. Canonical run identity contains every semantic command coordinate and has a mutation test for each field.
5. A file-backed close/reopen reconstructs inputs, roles, identity vintage, result, lineage, transition and current/refused state without defaults.
6. Failure after every material persistence phase leaves either the prior complete state or the new complete state—never an intermediate state.
7. Rust and Kotlin consume the same applicable positive and negative fixtures; ignored contract sections fail the harness.
8. Required regression suites stay green, and UI/live gates are added only when the behavior reaches those surfaces.
9. An independent reviewer maps each architecture obligation to test evidence and searches for an unencoded counterexample.
10. The plan, learning ledger and handoff status are reconciled after that review; the builder does not self-promote `implemented` to `closed`.

## Action plan

| ID | Owner | Action | Closure evidence |
| --- | --- | --- | --- |
| R1 | Winston + Mary | Define `AnalystMethodRunCommand`, typed input bindings, `CurrentCandidateEligibility`, horizon/currency policy and ordered refusal semantics | Versioned decision contract maps every field and state transition to reason codes and tests |
| R2 | Amelia | Split trusted control-envelope parsing from semantic admission and implement atomic publish-or-auditable-invalidate | Superseding semantic refusals clear prior current; malformed/foreign commands cannot invalidate it; survives reopen |
| R3 | Amelia + Winston | Persist `run_input_binding`, immutable identity/share vintages, typed run supersession/refusal and explicit command coordinates | Historical and new vintages coexist; both runs reconstruct exactly after restart |
| R4 | Amelia | Move or duplicate canonical projection ownership and semantic binding enforcement into the authoritative write boundary; restrict generic bypass APIs | No caller can publish FEM through a less strict path |
| R5 | Mary + Amelia | Promote metric families, horizon/currency checks and semantic refusals into shared Rust/Kotlin contracts | Same fixtures, hashes and ordered reason codes pass on both platforms |
| R6 | Amelia | Add lifecycle fingerprint fields for role bindings and every transition coordinate; bump the applicable policy/schema versions | Each single-field mutation conflicts; exact retry remains a no-op |
| R7 | Amelia | Add populated migration, failure-injection, close/reopen, corruption and two-writer tests | All checkpoints are all-or-nothing; integrity/FK checks pass; one writer wins deterministically |
| R8 | Independent reviewer | Run the closure matrix after R1–R7 and reconcile plan/ledger/status | 1B.3 changes to closed only with evidence links and no open P0/P1 publication gap |
| R9 | Amelia, after R8 | Implement 1C dossier read path from frozen joins; keep it separate from legacy intrinsic/ranking state | Provenance is reconstructed; one correlated analyst family; no `Strong`, ranking, blend or legacy scalar write |
| R10 | Independent reviewer + Amelia | Execute scoped native/UI and one long-lived Windows profile-`qa` session once 1C reaches UI | Stale/refused state stays absent after restart; labels/horizon/source quality are visible |

No action above carries a calendar estimate. Ordering is governed by dependency and proof, not a guessed duration.

## Critical path to 1C

```mermaid
flowchart LR
    A["R1: command and eligibility contract"] --> B["R2-R6: authoritative lifecycle and identity"]
    B --> C["R7: failure, restart and concurrency proof"]
    C --> D["R8: independent closure checkpoint"]
    D --> E["R9: publishable 1C read path"]
    E --> F["R10: scoped UI and profile-qa validation"]
```

Scaffolding for a non-publishing 1C DTO/query may be prepared, but no current candidate may be exposed before R8 closes.

## Readiness assessment

| Dimension | Assessment |
| --- | --- |
| Financial method and arithmetic | Ready |
| Observation V2 and basic evidence hashing | Ready for this slice |
| Successful-path persistence | Implemented, not failure-complete |
| Revision/refusal publication semantics | Blocked |
| Run and identity reconstruction | Blocked |
| Cross-platform semantic parity | Partial |
| Quant Lens publication | NO-GO |
| Providers, ranking, FCFF changes | Correctly out of scope |

## Commitments

- **Action items:** 10
- **Preparation tasks before publication:** R1–R8
- **Critical product gate:** no publishable 1C until independent closure
- **Durable promotions completed by this retro:** analyst-method proof obligations in `AGENTS.md`; lifecycle rules in `project-context.md`; EL-015 through EL-017 in the learning ledger; execution plan reopened as 1B.3

## Final takeaway

The strongest outcome is not another checklist. It is a change in what `DONE` means: **a visible valuation candidate must be reproducible by its complete economic identity and must transition correctly under success, refusal, crash, restart, policy change and supersession.** If that has not been independently demonstrated, the slice may be implemented and green, but it is not closed.
