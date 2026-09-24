# Aggressive V5 Evaluation

## Result

V5 has no measured forward-return advantage over V2. Aggressive V2 remains the neutral baseline and product default.

V5 fixes two V4 refusal defects. It keeps every other V4 term, weight, band, and composite rule.

This makes V5 cleaner than V4. It does not prove that V5 ranks opportunities better than V2.

## Why V2 Can Look Better

| Cause | Evidence | Meaning |
|---|---|---|
| V2 uses three buckets and a mean. | The engine adds a coverage bonus. | Its ranking is simpler and more stable. |
| V5 uses a median and agreement bonus. | V5 also keeps V3's beta haircut. | Several mechanisms changed together. |
| V5 can add a market bucket. | The captured cohort moved broadly. | Market context can dominate rank movement. |
| V2's D/E input saturates. | Real inputs exceed V2's incorrect ramp. | One leverage vote becomes almost constant. |

The last effect can act like accidental regularization. This statement is an inference, not outcome evidence.

## Captured Structural Baseline

The captured file contains 497 S&P 500 candidates. It compares V5 with and without its market bucket.

| Measure | Result |
|---|---:|
| Score raised | 399 |
| Score lowered | 86 |
| Score unchanged | 12 |
| Median score change | +7 |
| Median absolute rank move | 43 |
| Rank moves of at least 50 | 212 |
| Rank moves of at least 100 | 89 |

This snapshot measures perturbation, not accuracy. It contains no future returns.

Run `python lab/scoring-v5/snapshot.py` to reproduce it.

Sources:

- [`score-export-sp500-aggressivev5.csv`](../../lab/data/score-export-sp500-aggressivev5.csv)
- [`scoring-v5-snapshot-2026-09-19.txt`](../../lab/data/scoring-v5-snapshot-2026-09-19.txt)

## Paired Outcome Protocol

Each completed refresh now scores V1 through V5 from one final input state.

The atomic snapshot stores the full universe, exact inputs, exact ranks, and unavailable reasons.

The snapshot gives every model the same timestamp. Model selection does not change the sample.

The V2/V5 experiment accepts only exact symbol and timestamp pairs. Older unpaired rows stay outside this experiment.

Repeated refreshes can target the same next trading bar. The evaluator keeps the newest score for that symbol and bar.

The report counts every removed duplicate as `same-entry-bar`. This prevents refresh frequency from weighting one trading day.

The report samples 21, 63, and 126 trading bars.

Stored daily prices support later continuous checks through one year.

Current returns use raw closes. They exclude dividend-adjusted total return.

Street target upside remains diagnostic context. It never enters a forward-return spread.

## Controlled Series

| Series | Exact construction | Question |
|---|---|---|
| `paired-v2-composite` | Stored V2 composite. | Does the current control rank future returns? |
| `paired-v5-composite` | Stored V5 composite. | Does the candidate beat the control? |
| `v5-no-market` | Stored V5 three-bucket base. | Did the market bucket help? |
| `v5-mean-no-bonus-no-beta` | Mean of present V5 buckets. | Did the complex composite help? |
| `v5-fundamentals-v2-shell` | V5 fundamentals with V2 technicals, forecast, and composite. | Did V5 fundamentals help V2? |

Missing buckets remain missing. The experiment never converts an unavailable bucket to zero.

## Decision Rule

Do not change V5 weights from this snapshot. Do not promote V5 from subjective list inspection.

Use exact paired rows and identical held counts. Read all drop counts before comparing spreads.

Require a stable direction across more than one collection window. Treat overlapping horizons as dependent evidence.

Keep V2 as default until paired future returns support another model. A weak or mixed result means no change.

This process evaluates ranking behavior. It does not provide investment advice.
