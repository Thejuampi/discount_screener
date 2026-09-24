# Scoring Evaluation Snapshots

**Reconciled draft — 2026-09-20.**

This document separates current runtime behavior from the proposed target.

See [the proposed process design](../research/scoring-evaluation-process-design.md).

See [the validation record](../research/scoring-evaluation-validation.md).

The target is not implemented by this documentation change.

## Reconciled Target

The target uses three storage layers in one local database.

| Layer | Content | Retention boundary |
| --- | --- | --- |
| Operational cache | Replaceable projections and proven recoverable data | Eviction cannot remove archived evidence |
| Observation archive | Immutable revisions, occurrences, provenance, and sanitized evidence | Irreplaceable evidence survives without a cohort |
| Experiments and cohorts | Commitments, manifests, exact models, receipts, outcomes, and reports | Committed claims and dependencies never change |

### Late responses

A fenced runner cannot update jobs, cache, manifests, scores, or fulfillment.

A separate archive grant can accept its response.

The grant references the original durable dispatch.

The write is immutable and idempotent.

It preserves the actual receipt and observation times.

An incompatible delivery enters quarantine.

Quarantine preserves actual times and restores no runner authority.

An explicit revocation or erasure tombstone prevents reinsertion.

A late response never enters an existing frozen or committed manifest.

### Temporal evidence

Local observation, source publication, source age, and historical reconstruction are separate facts.

A family policy can accept local pre-cut observation for prospective use.

Equal wall-clock times use durable archive sequence order.

A later sequence never enters the earlier manifest.

Unknown source publication remains unknown.

A later fetch cannot refresh the source age.

Historical reconstruction still requires point-in-time evidence.

### Commitment and interruption

`FROZEN` identifies a candidate manifest.

`COMMITTED` reserves the canonical experiment before the cutoff.

The commitment fixes inputs, models, parameters, universe, policies, and evaluation rules.

Only an uncommitted candidate expires at the cutoff.

A committed experiment can resume after interruption.

Late completion becomes `MATERIALIZED_LATE`.

It never becomes `PUBLISHED_ON_TIME` retrospectively.

Every mutable write checks this claim token:

```text
job_key + process_id + invocation_id + generation + claimed_revision
```

Process liveness does not prove invocation liveness.

### Outcome trajectory

The outcome store tracks each regular market session through twelve calendar months.

The primary window covers one through three calendar months.

The committed calendar resolves non-trading boundary dates.

The system stores three separate daily series:

1. Price return from the frozen reference opening.
2. Realization of the frozen original target potential.
3. Later target revision change.

The original target never moves.

Later target changes never rewrite its denominator.

Later target revisions never backfill earlier trajectory points.

Scores do not become model-owned target forecasts.

The primary comparison is V5 minus V2 daily rank correlation.

Both models use the same instruments, dates, and price-return outcomes.

V5 wins only when the predeclared confidence interval stays above zero.

V2 wins only when that interval stays below zero.

An interval containing zero is inconclusive.

Other metrics and model pairs are complementary.

### Durability

The durability requirement is separate from its storage mechanism.

WAL with `synchronous=FULL` remains the reference for irreplaceable evidence.

The Android driver and supported phones require measurement.

Measurements include startup, commit latency, throughput, storage growth, and battery cost.

A faster alternative needs equal durability evidence.

Performance alone cannot justify a weaker guarantee.

### Migration boundary

Existing rows do not gain missing provenance during migration.

Legacy rows keep their original bytes and known timestamps.

Unknown fields remain unknown.

Existing scores do not become complete prospective commitments.

Runtime migration needs staging, validation, rollback, export, and restore tests.

## Current Runtime Baseline

The following sections describe the current documented baseline.

They do not claim the reconciled target already exists.

## Purpose

The app must preserve each scoring claim before future prices exist.

A later formula cannot recreate changing analyst targets, fundamentals, or market context.

The stored cohort answers one question.

Did higher scores precede stronger future price returns than lower scores?

This process evaluates ranking behavior. It does not provide investment advice.

## Capture Boundary

The Android repository waits for enrichment and the market read.

It then builds one immutable snapshot from the shared in-memory state.

SQLite stores the full JSON payload in one row and one transaction.

The latest completed refresh replaces the earlier snapshot for that profile and UTC day.

Cancelled refreshes store no evaluation snapshot.

## Stored Evidence

| Area | Stored values |
| --- | --- |
| Cohort | Profile, UTC date, capture time, complete symbol universe |
| Formula | V1 through V5 identities and policy version |
| Company input | Normalized detail, fundamentals, targets, recommendations, and freshness |
| History input | Weekly summary, daily regime summary, DCF analysis, and annual timeseries |
| Cross-section | Exact sector benchmarks used by V4 and V5 |
| Market | Exact market regime and scoring-control state |
| Output | All-universe rank, visible rank, bucket scores, composite, and factors |
| Failure | Missing symbols, provider issues, stale state, and unavailable reasons |
| Integrity | SHA-256 input fingerprint and snapshot schema version |

The snapshot stores all scored symbols, including unqualified companies.

This avoids measuring only the already-selected opportunity tail.

## Outcome Prices

The market reader stores tracked-symbol daily candles after every successful symbol fetch.

Index or sentiment failures no longer discard valid company prices.

The candle series grows beyond Yahoo's rolling one-year response.

The score journal remains as a compatibility record.

Its retention is 460 calendar days.

The atomic snapshot has no automatic retention limit.

Current candles use raw Yahoo closes.

Therefore, the current report measures price return, not dividend-adjusted total return.

## Operation

The database table is `scoring_evaluation_snapshot`.

The compatibility table is `score_journal`.

Future prices remain in `pricing_candle` under `BacktestDaily`.

The System page reports row counts and oldest and newest timestamps.

The Outcome action reads only the selected profile's atomic snapshots.

It writes the report to app-private storage.

No historical snapshot exists before this feature starts collecting data.

The app cannot reconstruct missing point-in-time inputs retrospectively.

## Current Limits

The current snapshot can retrieve a captured score and its stored factors.

It cannot always prove every composite arithmetic step.

The factor lists omit some normalization, agreement, haircut, and rounding details.

The snapshot stores formula names and versions.

It does not store an evaluator build identifier or formula digest.

It has no registry for replaying older engine implementations.

Input rows lack immutable source revision identifiers and complete source times.

Therefore, current replay cannot prove complete point-in-time provenance.

The same profile and UTC day use one replaceable database key.

A later same-day capture can replace an earlier capture.

Capture begins only after the current refresh dependencies finish.

Process termination before that point can leave no cohort.

The score journal retains 460 days.

Atomic evaluation snapshots have no automatic retention limit.

General symbol revisions keep at most 240 changes per symbol.

Manual age pruning can also delete unpinned revisions.

Backtest candles normally merge new windows with older dates.

A detected split rebase currently replaces that symbol's stored candle series.

These limits prevent guaranteed replay for every historical date.

The proposed process contract defines the required replacement behavior.
