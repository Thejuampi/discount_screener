# Scoring Evaluation Process Design

## Status

**Reconciled draft — 2026-09-20.**

This document defines a proposed contract.

It does not describe current Android behavior.

The reconciled contract is not approved as final.

Runtime work needs the release evidence defined below.

The examples live in
[the lifecycle feature](../../lab/scoring-process/scoring-evaluation-lifecycle.feature).

The executable laws live in
[the Prolog model](../../lab/scoring-process/scoring_evaluation_process.pl).

The reproducible command is:

```powershell
pwsh -NoProfile -File scripts/validate-scoring-process.ps1 -Bootstrap
```

The command fixes SWI-Prolog 10.0.2.

It executes PLUnit.

It does not execute Android or Cucumber integration tests.

See [the validation record](scoring-evaluation-validation.md) for executed evidence.

## Result

The process has three storage layers.

| Layer | Purpose | Retention boundary |
| --- | --- | --- |
| Operational cache | Accelerate current display and calculations | Only proven recoverable data can expire |
| Observation archive | Preserve immutable source evidence and occurrences | Irreplaceable evidence survives without scoring |
| Experiments and cohorts | Preserve commitments, outputs, outcomes, and reports | Committed claims and dependencies never change |

The process also has three independent work records.

| Record | Purpose | Time boundary |
| --- | --- | --- |
| Acquisition job | Improve the local input cache | No market-session expiry |
| Observation attempt | Freeze one scientific information set | Next reference opening |
| Canonical experiment | Fix one complete prospective experiment | Commitment never changes |

This separation resolves the two-month restart case.

An old uncommitted observation expires.

A committed experiment remains resumable.

Operational progress remains available.

Every cached revision receives a new eligibility decision.

## Scientific Question

The system compares V1 through V5 under one information set.

Every model must receive identical frozen inputs.

Later prices measure each model's ranking performance.

The process does not track trades, entries, or exits.

## Process Flow

```text
refresh demand
  -> durable acquisition job
    -> bounded work slices
      -> immutable archived revisions

observation request
  -> deadline before a reference opening
    -> frozen candidate manifest
      -> atomic pre-cut commitment
        -> V1, V2, V3, V4, V5
          -> PUBLISHED_ON_TIME or MATERIALIZED_LATE

later app session
  -> price backfill
    -> daily outcome trajectory update through twelve months
```

## Complete Scenario Space

The design uses finite equivalence classes.

Cucumber lists boundary examples.

Prolog checks modeled decision domains for total, single-valued results.

| Dimension | Values |
| --- | --- |
| Process | same, replaced, rebooted |
| Activity | visible, stopped, recreated |
| Acquisition progress | idle, pending, running, interrupted |
| User control | enabled, user-paused |
| Profile selection | selected, unselected |
| Observation | open, frozen, committed, computing, ready, interrupted, terminal |
| Deadline | future, reached, unknown |
| Context | same, changed |
| Ownership | same, missing, different |
| Invocation | live, stopped, lease-expired, unknown |
| Archive authority | granted, revoked, erasure-tombstoned |
| Delivery | compatible-new, compatible-duplicate, incompatible |
| Request | none, observer attach, refresh, explicit resume |
| Checkpoint | before, during, after transaction |
| Input time | before cut, at cut, after cut, unknown |
| Freshness | accepted, expired, unknown |
| Publication | absent, present, not publishable |
| External state | available, offline, rate-limited, storage-full, corrupt |
| Trajectory | pre-primary, one-to-three months, three-to-twelve months |
| Durability | mechanism, driver verification, measured cost |

Event sequences can be infinite.

Each modeled decision classifies its declared input domain.

Sequence properties cover the critical cross-event invariants.

## Record One: Acquisition Job

The acquisition job never carries a scientific observation date.

It can survive many app sessions.

It stores these fields:

- Job key and selected profile.
- Progress state, user control, and profile selection.
- Process incarnation, invocation identifier, generation, and claim revision.
- `requested_revision`, `claimed_revision`, and `fulfilled_revision`.
- Persistent fairness cursor and per-item attempt sequence.
- Retry counts and next eligible times.
- Typed blockers and provider failures.
- Last atomic checkpoint time.
- Durable wake-up intent and scheduling acknowledgment.

The complete claim token is:

```text
claim(job_key, process_id, invocation_id, generation, claimed_revision)
```

A live process does not prove that its worker invocation remains live.

A stopped invocation needs fencing and a new claim.

Unknown liveness cannot attach to the old runner.

The database permits one job per `(profile, work_type)`.

Repeated refresh requests increment `requested_revision`.

They never append independent runners.

A runner captures `claimed_revision` when it claims work.

Its checkpoint can fulfill only that revision.

A later request keeps `requested_revision` ahead.

The job then remains pending after the checkpoint.

### Acquisition Dimensions

| Dimension | Value | Meaning |
| --- | --- | --- |
| Progress | `IDLE` | No requested work remains |
| Progress | `PENDING` | Durable demand awaits a runner |
| Progress | `RUNNING` | One fenced runner owns a slice |
| Progress | `INTERRUPTED` | The recorded owner process disappeared |
| Control | `ENABLED` | Work can run when selected |
| Control | `USER_PAUSED` | Only explicit resume enables work |
| Selection | `SELECTED` | This profile can claim work |
| Selection | `UNSELECTED` | This profile cannot claim work |

Only `PENDING + ENABLED + SELECTED` can become `RUNNING`.

Changing profiles never changes user control.

Reselecting a paused profile leaves it paused.

Market time never expires these states.

Formula changes do not erase valid raw inputs.

Provider or schema changes trigger per-revision compatibility checks.

Universe changes reconcile the work set.

### Acquisition Transitions

| Current | Event | Result |
| --- | --- | --- |
| `IDLE` progress | Refresh demand | `PENDING` progress |
| `PENDING + ENABLED + SELECTED` | Claim | `RUNNING` progress |
| `RUNNING` progress | Slice has remaining work | `PENDING` progress |
| `RUNNING` progress | Slice fulfills demand | `IDLE` progress |
| `RUNNING` progress | Owner disappears | `INTERRUPTED` progress |
| `RUNNING` progress | Invocation stops | `INTERRUPTED` progress |
| `RUNNING` progress | System cancels work | `INTERRUPTED` progress |
| Any progress | Explicit pause | Control becomes `USER_PAUSED` |
| Any progress | Explicit resume | Control becomes `ENABLED` |
| Any progress | Profile change | Selection changes only |
| `INTERRUPTED` progress | Reconciliation | `PENDING` or `IDLE` progress |

An Activity stop does not create a transition.

An operating-system suspension does not create a transition.

System cancellation never sets `USER_PAUSED`.

Foreground and WorkManager execution use one coordinator.

SQLite remains the business authority.

Demand and wake-up intent commit together.

Reconciliation repairs a missing enqueue or missing acknowledgment.

## Record Two: Observation Attempt

An observation attempt owns one scientific deadline.

The deadline is the next reference market opening after `opened_at`.

The market calendar determines this opening.

The attempt cannot commit at or after that opening.

### Observation States

| State | Meaning |
| --- | --- |
| `OPEN` | Waiting to freeze eligible revisions |
| `FROZEN` | The candidate manifest is immutable but not committed |
| `COMMITTED` | The complete experiment owns its canonical key |
| `COMPUTING` | Exact committed models use only committed inputs |
| `READY` | All required outputs match the committed capsule |
| `INTERRUPTED(phase)` | The owner disappeared during that phase |
| `PUBLISHED_ON_TIME` | Complete outputs became available before the cutoff |
| `MATERIALIZED_LATE` | A pre-cut commitment finished at or after the cutoff |
| `EXPIRED` | The cutoff arrived before commitment |
| `INVALIDATED` | The context changed before commitment |
| `SUPERSEDED` | Another commitment already owns this key |
| `NOT_COMMITTED` | Predeclared eligibility failed before commitment |

Terminal states never resume.

A new request creates a new attempt.

### Observation Transitions

| Current | Event | Next |
| --- | --- | --- |
| `OPEN` | Freeze manifest | `FROZEN` |
| `FROZEN` | Commit complete capsule | `COMMITTED` |
| `COMMITTED` | Start calculation | `COMPUTING` |
| `COMPUTING` | Complete all models | `READY` |
| `READY` | Publish before cutoff | `PUBLISHED_ON_TIME` |
| `READY` | Publish at or after cutoff | `MATERIALIZED_LATE` |
| Active phase | Owner disappears | `INTERRUPTED(phase)` |
| `INTERRUPTED(phase)` | Valid reclaim | Saved phase |
| Pre-commit phase | Cutoff arrives | `EXPIRED` |
| Pre-commit phase | Context changes | `INVALIDATED` |
| Committed phase | Cutoff or live context changes | Preserve committed capsule |
| `FROZEN` | Canonical key exists | `SUPERSEDED` |
| Pre-commit phase | Eligibility fails | `NOT_COMMITTED` |

Context invalidation has priority over cutoff expiry before commitment.

The record preserves both detected reasons.

`FROZEN` never authorizes late calculation.

`COMMITTED` fixes all determining data before the cutoff.

Commitment also reserves the canonical key.

Calculation failure cannot release that key.

## Scientific Context

The context identifier hashes these versions:

- Profile membership and universe order.
- V1 through V5 formulas.
- Valuation policy.
- Regime policy.
- Input schema.
- Evaluation contract.
- Publishability policy.
- Market calendar version.
- Exact model implementations, parameters, seeds, and digests.
- Daily outcome protocol and comparison protocol.
- Minimum coverage and uncertainty settings.

A context change invalidates an unfinished candidate.

It never changes a committed experiment.

It does not automatically invalidate compatible cached inputs.

## Input Revision Contract

Every provider value becomes an immutable revision.

Each family defines its required temporal fields.

| Field | Meaning |
| --- | --- |
| `source_event_at` | Economic event or revision time |
| `source_period_end` | Accounting period represented by the value |
| `forecast_horizon_end` | Future horizon represented by an estimate |
| `source_published_at` | Source publication time, when available |
| `observed_at` | First time this app received the revision |
| `recorded_at` | SQLite commit time |

These fields are not interchangeable.

The contract uses three separate temporal decisions.

| Decision | Question |
| --- | --- |
| Prospective availability | Did this app observe the value before the cut? |
| Source age | Does the value satisfy its family freshness policy? |
| Historical reconstruction | Can evidence prove availability at an earlier unobserved time? |

A family can declare `LOCAL_OBSERVATION_SUFFICIENT`.

Its pre-cut observation can support prospective availability.

Unknown original publication time remains unknown.

Another family can require source publication evidence.

Unknown publication then makes that input unavailable.

An old accounting period can remain current.

A future forecast horizon does not imply future knowledge.

A correction creates a new revision.

It never edits a committed experiment.

### Input Eligibility

An input enters a prospective manifest only when every applicable rule passes.

1. `observed_at` is not after the cut.
2. The family availability policy accepts its publication evidence.
3. A known source publication time is not after the cut.
4. The independent source-age policy accepts the revision.
5. The source contract remains compatible.
6. The schema remains compatible.
7. The symbol belongs to the frozen universe.

Equal wall-clock times use the durable archive sequence.

Only revisions visible before the manifest sequence are accepted.

Every failing rule remains in the reason list.

A fetch timestamp never refreshes an old source event or source age.

Prospective eligibility never proves historical reconstruction.

Reconstruction still requires point-in-time publication and context evidence.

### Late Response Ingestion

Mutable writes require the current complete claim token.

Immutable archive ingestion uses a narrower authorization.

That authorization references the original durable dispatch.

It does not restore runner ownership.

An accepted late response can only append or reuse archival content.

It cannot update these records:

- Acquisition jobs or fulfillment.
- Operational cache or current projections.
- Frozen or committed manifests.
- Score outputs or cohort state.

The archive records the actual response and receipt times.

It never backdates them to the dispatch time.

Duplicate delivery is idempotent.

An incompatible delivery enters quarantine with no mutable effects.

Unknown dispatch identity causes rejection.

Explicit archive revocation causes rejection.

An erasure tombstone prevents later reinsertion.

A late revision can enter only a later prospective manifest.

## Frozen Manifest

The manifest lists one decision for every required input.

Each entry contains a revision identifier or unavailable reasons.

The manifest has an immutable identifier and content hash.

Its transaction records a database revision sequence and the cut time.

Concurrent acquisition commits fall fully before or after that sequence.

Equal timestamps cannot add revisions after the manifest transaction.

All five scoring models use that identifier.

Partial outputs may checkpoint against the manifest.

They remain invisible to scientific reporting.

The final transaction rejects missing or mixed manifest identifiers.

New provider revisions cannot enter an existing manifest.

The frozen manifest is only a candidate.

Before the cutoff, one transaction commits the complete experiment capsule.

The capsule fixes these items:

- Manifest, unavailable decisions, universe, and deterministic order.
- Exact V1 through V5 implementations, parameters, seeds, and digests.
- Scoring, normalization, valuation, regime, rounding, and arithmetic policies.
- Source contracts, schemas, calendar, time evidence, and capture policy.
- Outcome anchors, daily windows, return basis, and target treatment.
- Primary V5–V2 comparison, common sample, coverage, and uncertainty rules.
- Canonical experiment key and every replay dependency.

No committed field can remain `TBD`.

The commitment reserves the canonical key before calculation finishes.

An interrupted calculation resumes from this capsule.

It cannot read current providers, current profiles, mutable caches, or outcomes.

## Score Observability

Each model result stores a deterministic score receipt.

The receipt answers three different questions.

| Question | Required evidence |
| --- | --- |
| Why is the score 5? | One complete receipt |
| Why was it 8 yesterday? | Two complete receipts |
| Why is V5 5 while V2 is 8? | Both model receipts and formula identities |

The receipt contains these fields:

- Cohort, manifest, formula, policy, and evaluator identities.
- Final score and every bucket score.
- Every included factor and its source revision.
- Observed value, reference value, weight, ramp, and unrounded contribution.
- Every excluded, missing, stale, or incompatible factor.
- Normalization denominator and coverage state.
- Composite centre, spread, agreement adjustment, and coverage adjustment.
- Regime contribution and beta haircut.
- Bounds, rounding steps, and final integer.

The explanation must reconcile exactly to the stored score.

It must never infer a missing contribution.

A contrast explanation classifies each difference.

| Difference class | Example |
| --- | --- |
| Input changed | Analyst target moved |
| Availability changed | A missing bucket became present |
| Reference changed | Sector median moved |
| Context changed | Market regime changed |
| Formula changed | V5 differs from V2 |
| Arithmetic changed | Rounding or normalization differs |

The explanation states calculation causes only.

It does not claim market causality.

Explanation loading is demand-driven.

Dashboard startup never scans receipts.

## Historical Reproduction

Historical access has three distinct levels.

| Level | Question | Result |
| --- | --- | --- |
| Retrieval | What score did the app publish? | Read the stored receipt |
| Replay | Does the same engine reproduce it? | Recompute the frozen manifest |
| Reconstruction | What would a model score on an unobserved date? | Requires historical source data |

Retrieval needs only an intact materialized cohort.

Replay needs all following items:

1. A complete immutable manifest.
2. The exact formula implementation.
3. The exact policy and schema versions.
4. The exact universe, benchmarks, and market context.
5. Deterministic ordering, fixed-point arithmetic, and rounding.

Every formula version becomes immutable after publication.

A semantic change creates another formula version.

The app keeps a replay engine for every retained formula version.

Each cohort stores an evaluator build identifier and formula digest.

Replay compares its output with the stored receipt.

A mismatch preserves both results and reports `REPLAY_MISMATCH`.

It never overwrites the original score.

If the replay engine is missing, the stored receipt remains exact evidence.

The app then reports `ENGINE_UNAVAILABLE`.

Reconstruction has a stricter boundary.

The app cannot reconstruct a date it never observed without point-in-time history.

Present provider values cannot substitute historical values.

Possible failure reasons include:

- No cohort was materialized on that date.
- The provider offers only current values.
- Historical revisions have gaps.
- Required publication times are unknown.
- The historical universe is unknown.
- The formula engine is unavailable.
- The replay capsule is incomplete or corrupt.

These cases return typed unavailable states.

They never return estimated historical scores as facts.

### Future Model Versions

A future model can use an old capsule only when all required inputs exist there.

For example, V6 may require a field that V5 never stored.

That old cohort cannot produce a comparable V6 score automatically.

The system reports `MODEL_INPUT_HISTORY_MISSING`.

Historical reconstruction is allowed only with true point-in-time source history.

Such results carry a reconstructed marker.

Current-only values cannot fill the historical gap.

Otherwise, V6 evaluation starts prospectively.

Model comparisons use only shared eligible cohorts and shared outcome horizons.

Reports always show cohort counts and coverage differences.

Historical replay can still be in-sample evidence.

If old outcomes influenced V6, those cohorts cannot prove V6 superiority.

The primary claim needs untouched holdout cohorts or prospective cohorts.

The formula must freeze before those outcomes become available.

## Historical Coverage And Merge

Each source family stores a coverage map.

The map contains intervals, gaps, source, basis, last fetch, and last revalidation.

A range request asks only for missing intervals.

A separate revalidation request checks covered but revisable intervals.

Coverage alone cannot detect a correction.

Assume the app has the latest 15 days.

A 30-day request fetches the missing earlier 15 days.

The write merges both intervals.

A later 15-day response cannot delete the earlier interval.

Historical writes follow these rules:

1. A new date appends.
2. An identical revision becomes a no-op.
3. A correction appends a new immutable revision.
4. An overlapping window merges without deleting older dates.
5. A corporate-action basis change creates another series edition.
6. A committed manifest keeps its original series edition.

Different families have different reconstruction limits.

| Family | Historical recovery |
| --- | --- |
| Daily market bars | Usually backfillable and mergeable |
| SEC filings | Backfillable by publication time |
| Analyst targets | Often unavailable unless previously observed |
| Recommendations | Often unavailable unless previously observed |
| Sector benchmarks | Derived from a complete frozen universe |
| Market regime | Derived only from its historical inputs |
| DCF analysis | Replayable only from its frozen valuation inputs |

## Evidence Retention

A committed experiment owns a self-contained replay capsule.

The capsule embeds inputs or pins content-addressed input revisions.

Manual pruning cannot remove pinned evidence.

Formula engines remain available while their cohorts remain retained.

Irreplaceable archive revisions remain protected without a cohort.

Revisions with unknown recoverability also remain protected.

Only proven reconstructible and unpinned data can expire.

Storage pressure never silently deletes protected evidence.

It blocks new evidence writes and reports the required space.

The database indexes lightweight cohort metadata separately.

Full receipts and manifests load only after a user request.

## Fencing And Atomicity

Each claim assigns a process, invocation, generation, and claimed revision.

Every write compares these values:

- Job or attempt key.
- Process incarnation.
- Invocation identifier.
- Generation.
- Scientific context.
- Allowed state.
- Claimed request revision.
- Manifest identifier, when applicable.

A stale response cannot perform a mutable write.

Its separate archive-only path follows the late-ingestion contract.

The job stores requested, claimed, and fulfilled revisions separately.

A checkpoint must match the durable claimed revision.

Write authorization does not imply demand fulfillment.

Only the acquisition completion predicate can advance `fulfilled_revision`.

That predicate checks every required item for the claimed revision.

Each item must be current or have a recorded bounded attempt result.

A partial checkpoint keeps `fulfilled_revision` unchanged.

A complete checkpoint advances it monotonically.

A newer `requested_revision` keeps the job pending.

A database transaction stores each checkpoint atomically.

Readers see either the earlier checkpoint or the complete new checkpoint.

## Work Slices And Fairness

Each slice has request, time, and concurrency budgets.

Real-device measurements must set those values.

The scheduler keeps fairness state across app sessions.

It never resets fairness at a market opening.

The next selection uses these rules:

1. Select required items without a completed current attempt.
2. Select the least recently attempted eligible item.
3. Respect each item's retry time.
4. Advance after success or a recorded terminal failure.
5. Keep transient retries bounded.

Frequent refresh requests do not reset the order.

Therefore, repeated short sessions cannot starve the final symbols.

Before each network call, SQLite stores a durable dispatch record.

The dispatch contains item, sequence, job, process, invocation, generation, and claimed revision.

Reconciliation compares that complete claim token.

A new generation fences dispatches from the same process.

Process death converts an unfinished dispatch into an interrupted attempt.

A stopped invocation does the same, even when its process remains alive.

The scheduler then serves another eligible item.

The interrupted item returns after the fair rotation and retry delay.

Fairness requires opportunities to commit dispatch records.

No algorithm progresses if Android always kills before the first transaction.

## Android Lifecycle Contract

The dashboard startup reads only small job rows and current projections.

It does not scan or decompress historical cohorts.

Heavy acquisition starts after the first usable dashboard state.

The foreground and WorkManager call the same coordinator.

WorkManager provides execution opportunities only.

SQLite stores demand, ownership, progress, and fulfillment.

The coordinator reconciles demand before claiming work.

The process can continue after the Activity stops.

No service guarantees that continuation.

If Android suspends the process, no database state changes.

If Android kills the process, no callback is required.

The next invocation detects an absent or stopped previous invocation.

It fences the old generation before new network work.

A reboot always makes the prior process owner invalid.

Cancellation callbacks are best effort.

System cancellation never changes user pause intent.

## Exact Two-Month Trace

| Step | Acquisition job | Observation attempt |
| --- | --- | --- |
| January start | `RUNNING + ENABLED + SELECTED` | `OPEN` or later |
| Two minutes later | Last checkpoint is durable | Manifest may exist |
| Android suspends | No durable transition | No durable transition |
| Android kills | Owner becomes unreachable | Owner becomes unreachable |
| March reopen without commitment | Progress becomes `INTERRUPTED` | January becomes `EXPIRED` |
| March reopen with commitment | Progress becomes `INTERRUPTED` | January remains resumable |
| Reconcile | Keep cursor and archive | Fence old invocation |
| New demand | Claim a new invocation and generation | Create a separate March attempt |
| Old commitment | Continue exact saved capsule | Materialize as `MATERIALIZED_LATE` |
| March freeze | Revalidate every revision | Build a new manifest |

January completion marks cannot prove March completeness.

The persistent fairness cursor can still choose the next item.

Stale January values become unavailable and return to acquisition.

Valid filings keep their original source dates.

No January value receives a March source date.

An explicit user pause changes only one step.

The acquisition control stays `USER_PAUSED` until explicit resume.

Profile changes cannot remove that control.

An uncommitted January observation still expires.

A committed January experiment does not expire.

## Canonical Publication

The canonical key contains these values:

- Profile.
- Reference session.
- Evaluation contract version.

The first eligible commitment wins.

A failed pre-commit provider pass does not consume the key.

It creates an operational failure record instead.

Commit eligibility uses a predeclared policy.

It cannot use future outcome results.

The commitment transaction verifies these conditions:

1. The attempt remains `FROZEN`.
2. Process, invocation, generation, context, and revision still match.
3. The capsule contains every determining input and rule.
4. Commitment occurs strictly before the cutoff.
5. The canonical key remains absent.

The transaction writes the capsule, pins, reservation, and `COMMITTED` state together.

The materialization transaction verifies these conditions:

1. A valid pre-cut commitment exists.
2. All five outputs use its exact models and manifest.
3. Complete receipts reconcile every output.
4. No result already conflicts with the candidate.

Materialization before the cutoff becomes `PUBLISHED_ON_TIME`.

Materialization at or after the cutoff becomes `MATERIALIZED_LATE`.

An idempotent retry returns the original timestamp and classification.

## Clock And Calendar Failure

An unknown market opening blocks scientific publication.

It does not block raw acquisition.

A detected clock rollback marks time as uncertain.

An uncertain clock blocks commitment and timing classification.

It does not block raw archival capture.

A timezone change does not alter stored UTC times.

The app never replaces the market calendar with a fixed 24-hour rule.

## External Failure Rules

| Failure | Acquisition result | Observation result |
| --- | --- | --- |
| Offline | Store typed blocker and retry time | Missing inputs remain explicit |
| Rate limit | Store provider retry time | No busy retry loop |
| Storage full | Stop new writes safely | Do not delete immature evidence |
| Corrupt cache row | Mark revision unusable | Record unavailable reason |
| Corrupt manifest | Reacquire when needed | Never commit that attempt |
| App update | Migrate compatible rows | Invalidate incompatible context |
| App data cleared | Start empty | Historical inputs stay unrecoverable |

## Outcome Updates

Closed-app days create no scoring cohorts.

Later app sessions can backfill daily price bars.

Those bars extend existing outcome trajectories.

They never create past inputs or past scores.

Missing provider history remains an explicit gap.

### Daily Timeline

Let `O` be the committed reference session opening.

The initial price `P0` is that official regular-session opening price.

Each trajectory point uses the official regular-session close `Pt`.

The app tracks every eligible session through twelve calendar months.

The primary window begins one calendar month after `O`.

Its first point is the first session on or after that date.

The primary window ends three calendar months after `O`.

Its final point is the last session on or before that date.

Follow-up ends twelve calendar months after `O`.

Its final point is the last session on or before that date.

The committed market calendar resolves each boundary.

A missing session price stays missing.

The system never forward-fills a missing outcome.

### Separate Outcome Series

The primary outcome series is price return:

```text
R(t) = Pt / P0 - 1
```

The original target `T0` freezes with the experiment.

Original potential realization is:

```text
Q(t) = (Pt - P0) / (T0 - P0)
```

`Q(t)` remains continuous and unclamped.

It is unavailable when `T0 = P0`.

It is also unavailable when required prices or target evidence are invalid.

The current target `Tt` creates a separate revision series:

```text
U(t) = Tt / T0 - 1
```

A target increase makes `U(t)` positive.

`Tt` uses only target evidence observed by session `t`.

A later target revision never fills an earlier trajectory point.

Missing point-in-time target evidence makes `U(t)` unavailable.

It never increases the apparent failure against `T0`.

The current target never replaces `T0` inside `Q(t)`.

These targets belong to company evidence.

V1 through V5 produce scores, not proprietary target forecasts.

### Primary V5–V2 Comparison

The primary metric uses price returns during the complete one-to-three-month window.

For each cohort and eligible session, use one common instrument sample.

Compute Spearman rank correlation for V5 scores and `R(t)`.

Compute the same correlation for V2 scores and `R(t)`.

Define the paired daily difference as:

```text
D(c,t) = IC_V5(c,t) - IC_V2(c,t)
```

The cohort statistic is the equal-session mean of `D(c,t)`.

The neutral baseline is zero.

Different samples cannot support the primary comparison.

Insufficient common coverage returns `INFERENCE_UNAVAILABLE`.

Potential realization and target revisions are complementary metrics.

The pre-primary and three-to-twelve-month windows are also complementary.

V1, V3, and V4 comparisons remain complementary in this contract.

### Predeclared Uncertainty

The commitment fixes the uncertainty protocol before outcomes exist.

It fixes minimum common instruments and minimum eligible cohorts.

It also fixes bootstrap block length, repetitions, and confidence level.

Use a moving-block bootstrap over ordered cohort reference sessions.

Keep each sampled cohort's instruments and daily horizons together.

This preserves within-cohort dependence during resampling.

An interval containing zero produces an inconclusive result.

An interval entirely above zero supports `V5_SUPERIOR`.

An interval entirely below zero supports `V2_SUPERIOR`.

The interval and point estimate must agree.

Post-hoc uncertainty rules cannot support a confirmatory superiority claim.

## Durability And Recovery

Irreplaceable evidence needs a declared durability requirement.

The requirement is independent from SQLite configuration names.

WAL with `synchronous=FULL` is the reference mechanism.

The selected Android driver must prove its effective setting after reopen.

Supported phones need these measurements:

- Database open and dashboard startup latency.
- Archive commit latency and throughput.
- WAL growth, checkpoint duration, and storage growth.
- Battery cost during bounded acquisition slices.
- Process-death behavior and separate power-loss behavior.

An unmeasured configuration cannot enable irreplaceable evidence writes.

An unacceptable FULL result requires design work.

It does not permit an automatic downgrade to `NORMAL`.

Another mechanism can qualify only with equal durability evidence.

Export and restore remain separate requirements.

Export uses a transactionally consistent snapshot.

It includes hashes, versions, dependencies, receipts, outcomes, and provenance.

Restore validates a staging artifact before activation.

Failed restore leaves the current archive unchanged.

Restored claims receive a new process and invocation epoch.

## Laws

1. Market time never expires acquisition work.
2. A user pause requires an explicit resume.
3. Profile selection cannot change user pause intent.
4. One job key has at most one authorized runner.
5. Every mutable write checks process, invocation, generation, and revision.
6. A fenced runner never regains mutable authority.
7. Archive-only ingestion requires its original dispatch grant.
8. Archive-only ingestion never changes mutable scientific state.
9. Explicit erasure prevents late reinsertion.
10. Partial progress cannot fulfill acquisition demand.
11. A checkpoint cannot fulfill a newer request revision.
12. Local observation and source publication remain separate.
13. Local observation never rejuvenates source age.
14. Prospective availability never proves historical reconstruction.
15. One experiment uses one immutable manifest.
16. All five outputs use one committed capsule.
17. Commitment occurs strictly before the cutoff.
18. Only an uncommitted candidate expires at the cutoff.
19. A committed experiment can materialize late.
20. Late materialization never claims on-time publication.
21. A committed experiment never changes.
22. A failed pre-commit pass never consumes a canonical key.
23. Every unavailable input has explicit reasons.
24. Fairness state survives process death and market openings.
25. An interrupted durable dispatch rotates before retry.
26. Every displayed score has one reconciling receipt.
27. Replay never replaces its stored result.
28. Present values never reconstruct an unobserved past.
29. A shorter response never deletes older covered dates.
30. Revalidation detects corrections separately from backfill.
31. Pruning never removes committed or irreplaceable evidence.
32. A formula version never changes after commitment.
33. A future model never receives invented historical inputs.
34. The original target remains frozen.
35. Price return, target realization, and target revisions remain separate.
36. V5–V2 uses common instruments and daily paired outcomes.
37. Confirmatory uncertainty rules freeze before outcomes.
38. Outcomes used during development cannot become holdout evidence.
39. Durability requirements do not weaken for performance convenience.
40. Model superiority requires the primary confidence interval to exclude zero.

## Measurements And Configuration Required Before Runtime Work

These policies remain unset:

- Freshness rules for each input family.
- Prospective availability policy for each input family.
- Publication evidence requirements for each input family.
- Source-age rules for each input family.
- Slice request and time budgets.
- Maximum request concurrency.
- Trusted market calendar source.
- Clock uncertainty tolerance.
- Observation start and freeze triggers.
- Maximum acquisition wait before freezing.
- Minimum publishable coverage.
- Minimum common instruments and eligible cohorts.
- Bootstrap block length, repetitions, and confidence level.
- Target evidence eligibility rules.
- Storage quota and retention.
- Replay-engine retention policy.
- Historical coverage policy for each family.
- Corporate-action series version policy.
- Effective SQLite driver durability after reopen.
- WAL `FULL` startup, write, battery, and storage costs.
- Export, restore, migration, and power-loss acceptance limits.

Recorded provider and device measurements must set these values.

Convenient test timings must not set them.
