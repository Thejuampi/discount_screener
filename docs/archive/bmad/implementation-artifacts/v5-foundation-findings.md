# V5 Foundation Findings — outcome measurement baseline

Status: **awaiting first data** — the score journal only began accumulating when the feature
shipped, so the first runs of `Run Outcome Report` will read mostly `insufficient`. That is the
honest state, not a failure. This document becomes the V5 gate once enough journal history exists
under horizons 21 / 63 / 126 trading bars.

## How to read a filled report

- `top-minus-bottom` per model × metric × horizon: the robust-centre forward return of the top
  score tenth minus the bottom tenth. Positive and stable across horizons = the metric ranks.
- `compositeBase` beside `composite`: if base spreads match final spreads, the market dimension
  adds nothing; if they diverge, the dimension is earning or costing its seat.
- Street median upside appears as `[DIAGNOSTIC ONLY]`. It must never be used to pick terms.

## Verdict table (fill from `outcome-$profile.txt`)

| Model | Metric | Horizon | Spread bps | n held | Sufficient |
| --- | --- | --- | --- | --- | --- |
| AggressiveV4 | composite | 21 | | | |
| AggressiveV4 | composite | 63 | | | |
| AggressiveV4 | composite | 126 | | | |
| AggressiveV4 | fundamentals | 63 | | | |
| AggressiveV4 | technical | 63 | | | |
| AggressiveV4 | forecast | 63 | | | |
| AggressiveV4 | regime | 63 | | | |
| AggressiveV3 | composite | 63 | | | |

## Gate decision (to be written by Juan)

- [ ] Continue to V5 term design on this evidence
- [ ] Extend measurement first (factor persistence in the journal for term-level court)
- [ ] Stop — current scoring is good enough; revisit with more history

## Follow-ups already recorded

See `_bmad-output/implementation-artifacts/deferred-work.md` (2026-08-21 entries): Pulse
corroboration policy, fail-open unknown sector keys, float-driven FCFy on insurers.
