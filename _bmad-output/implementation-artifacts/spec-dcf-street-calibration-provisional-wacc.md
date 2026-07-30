# SPEC: FCFF provisional-rate calibration toward weighted analyst mean

**Status:** implement  
**Date:** 2026-07-30  
**Evidence:** `_bmad-output/planning-artifacts/research-dcf-vs-street-gap-T-2026-07-30.md`  
**Engine:** Windows `dcf_model.rs` (primary); keep financial RI routing unchanged  

## Intent (WHAT)

1. Reduce **systematic FCFF overvaluation** when discount rates are provisional (default CoD/tax, soft structure), measured against **weighted analyst consensus**, not market price.  
2. Preserve honest dual anchors: model base remains a **model** number with provenance; analysts remain parallel; material residual → **Disputed** / unreliable point estimate (existing Quant Lens rules).  
3. Never close the gap with intrinsic/price caps, sector FCF haircuts, or forcing base = Street.

## Decisions

| ID | Decision |
| --- | --- |
| D1 | Weighted analyst mean is an **external development metric for measuring bias** (when usable). Runtime valuation compute never reads it and never emits it as provenance. |
| D2 | When CoD is policy **Default**, apply a **provisional WACC base uplift** scaled by debt weight / `PROVISIONAL_MAX_DEBT_WEIGHT` (full uplift at the structure cap). Rationale: reverse-DCF on T shows ~170 bps soft-rate understatement at high leverage. |
| D3 | FCFF **run-rate** = average of the latest contiguous positive FCF window (normalized), while diagnostics preserve the true latest fiscal FCF separately. |
| D4 | Uplift and normalization are **inputs/parameters** with reason codes; not output clamps. |
| D5 | ACGL-class financials remain residual-income primary; no FCFF-from-float path. |
| D6 | Bump `MODEL_POLICY_VERSION` so caches/UI can invalidate. |

## Anti-goals

- `assert(intrinsic ≈ price)`  
- Hard reject if `intrinsic/price > N`  
- `FCF × sector_constant`  
- Silent blend of model and Street into one absurd EV  

## Acceptance

- T-class fixture: base intrinsic **materially closer** to pinned weighted consensus (~$30) than pre-uplift soft path with same FCF, without setting base equal to consensus by assignment.  
- No new clamp patterns in valuation change set.  
- ACGL-class still RI, not FCFF.  
- Diagnostics/reason_codes surface uplift and normalized run-rate.
- Windows and Android execute the shared policy/2 T contract; runtime reasons contain the applied WACC policy, not the development metric.
- Stale engine/policy analyses are rejected before detail or Quant Lens can serve them.

## Out of scope

- Live bond CoD / live rf feed (desired later; this SPEC is provisional bias correction).  
- Lease debt bridge.  
- Full multi-provider FCF reconciliation (Yahoo vs EDGAR).  
- Desktop terminal policy/2 adoption; desktop remains explicitly deferred in the shared contract until its FCFF engine is ported and tested.
