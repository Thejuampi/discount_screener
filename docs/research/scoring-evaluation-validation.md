# Scoring Evaluation Validation

## Result

The reconciled decision model passes its executable PLUnit suite.

This result validates the formal decision rules only.

It does not validate Android runtime behavior.

The external V2 ZIP was not copied into the repository.

This reconciliation does not modify Android runtime files.

## Reconciled Difference

The repository version does not adopt the external V2 contract unchanged.

| Area | Reconciled change |
| --- | --- |
| Approval | Keep the contract as a draft |
| Publication | Remove the competing single-step `sealed` route |
| Commitment | Reserve the experiment before calculation and cutoff |
| Late responses | Use separate archive-only authority |
| Temporal evidence | Separate availability, age, and reconstruction |
| Outcomes | Store daily return, realization, and revision series |
| Comparison | Make V5–V2 primary on paired common samples |
| Superiority | Require the predeclared interval to exclude zero |
| Durability | Keep requirements separate from SQLite mechanisms |
| Runtime | Mark Android integration and device evidence as pending |

## Reproducible Environment

| Item | Recorded value |
| --- | --- |
| Validation date | 2026-09-20 |
| Operating system | Microsoft Windows NT 10.0.26200.0 |
| PowerShell | 7.6.6 |
| SWI-Prolog | 10.0.2 for x64-win64 |
| Runner | `scripts/validate-scoring-process.ps1` |
| Model | `lab/scoring-process/scoring_evaluation_process.pl` |

Use this command after the dependency exists:

```powershell
pwsh -NoProfile -File scripts/validate-scoring-process.ps1
```

Use this command for pinned installation through `winget`:

```powershell
pwsh -NoProfile -File scripts/validate-scoring-process.ps1 -Bootstrap
```

The runner rejects every SWI-Prolog version except 10.0.2.

## Executed PLUnit Evidence

The final run executed 94 tests and passed every test.

The run covered these decision boundaries:

- Process, invocation, generation, and revision fencing.
- Archive-only late ingestion and explicit revocation.
- Prospective availability, source age, and historical reconstruction.
- Pre-cut commitment and late materialization.
- Daily outcome classification and primary comparison eligibility.
- Confidence-interval rules for V5 and V2 superiority.
- Durability requirements and retention protection.

The test count alone is not validation.

The named behaviors and their executed assertions provide the evidence.

## Regression Trace

Tests failed before each related implementation changed.

| Regression | Initial result | Corrected rule |
| --- | --- | --- |
| Missing current claim | No decision existed | Return `invalid_result_claim` |
| Irreplaceable archive without cohort | Evidence could expire | Retain independent archive evidence |
| Recovery status unknown | Pressure could delete evidence | Retain and block new publication |
| Incompatible late delivery | Actual time was lost | Quarantine preserves actual time |
| Missing outcome price | Realization was not marked unavailable | Mark return and realization unavailable |
| Expired candidate with matching key | It appeared committed | Only committed states can read a commitment |
| Superiority classification | No executable rule existed | Require the primary interval to exclude zero |
| Legacy `sealed` replay state | New publication states were rejected | Use both materialization classifications |

## Integration Matrix

| Surface | Status | Evidence or blocker |
| --- | --- | --- |
| PLUnit decision model | Executed: pass | SWI-Prolog 10.0.2 ran 94 tests |
| Pinned validation runner | Executed: pass | Version check and suite completed |
| Cucumber behavior execution | Pending | Android step definitions do not exist |
| Late response SQLite transaction | Pending | Runtime archive tables do not exist |
| Equal-time sequence ordering | Pending | Needs one SQLite transaction boundary |
| Job and archive authority separation | Pending | Runtime coordinator does not implement this contract |
| Revocation and erasure race | Pending | Runtime tombstone path does not exist |
| Activity recreation and worker stop | Pending | Needs Android lifecycle instrumentation |
| Process death during each transaction | Pending | Needs Android database integration tests |
| Device power loss | Pending | Needs controlled device testing |
| WAL `FULL` driver verification | Pending | Needs reopen checks on the Android driver |
| Startup and commit cost | Pending | Needs measurements on supported phones |
| Battery and storage growth | Pending | Needs repeated bounded device runs |
| Export, restore, and migration | Pending | Runtime mechanisms do not exist |
| Provider response shapes | Pending | Needs at least five real upstream samples |
| Daily outcomes and corporate actions | Pending | Needs market-calendar integration |
| Empirical V5–V2 comparison | Pending | Prospective cohorts need one-to-three-month outcomes |

Static structure checks do not change any pending status.

No live QA ran.

## Release Boundary

Do not approve this draft as the final runtime contract.

First set the remaining policies in the process design.

Then implement the Android integration behind reversible migrations.

The integration matrix must pass before runtime approval.
