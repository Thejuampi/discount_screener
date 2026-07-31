# Technical decision — acquisition-aware FCFF growth evidence

Status: corrected after multi-name QA on 2026-07-31; implemented in policy/12.

## Trigger

CRGY's SEC-normalized recurring development CapEx was correct, but its FCFF
projection still treated reported revenue growth as organic despite material
oil-and-gas property acquisitions. That produced an implausibly high model
anchor. Analyst targets are an external comparison only and must not calibrate
or cap intrinsic value.

## Approved policy change

For an `OperatingNonFinancial` FCFF driver window, aligned property or business
acquisition cash at least 10% of same-period revenue contaminates only the
reported revenue-growth transition from the prior fiscal year into that year.
Exclude that transition. Use the remaining recent growth evidence only when at
least two clean transitions exist and the latest transition is clean; otherwise
set near-term growth to zero and record `acquisition_normalized` provenance.
The model does not subtract acquisition cash as recurring CapEx and does not
invent an organic-growth adjustment.

The threshold is versioned in `shared/contracts/sec-driver-normalization.json`
and projected into Windows and Android. Its policy fingerprint and the DCF
model-policy version invalidate stale intrinsic-value caches.

## Consequences

- Detail shows either the clean-transition provenance or the conservative
  acquisition-normalized zero-growth fallback.
- Quant Lens retains a model anchor, while preserving any disagreement with
  the analyst range instead of calibrating to it.
- A historical acquisition no longer erases clean evidence that follows it.

## Verification

- Windows: DCF, SEC normalization, EDGAR, multi-name valuation baseline, and
  Quant Lens tests.
- Android: core and SEC-provider unit tests.
- Cross-platform QA cohort parity remains exact.
