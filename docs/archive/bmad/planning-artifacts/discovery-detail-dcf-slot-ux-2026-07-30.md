# Detail panel — DCF valuation slot (UX note)

**Status:** implemented (lean capture; not a full DESIGN/EXPERIENCE spine)  
**Date:** 2026-07-30  
**Surface:** Windows workstation · `DetailPanel` price summary  
**Languages:** es / en (i18n keys)

## Problem

DCF/valuation is computed **asynchronously** (EDGAR FCF + model family worker). The UI only rendered the DCF column when `dcfValue > 0`, so the block **appeared and disappeared** without explanation — felt random.

## Decision (state pattern)

For equities/stocks only (`!technicalOnly`), always reserve a slot:

| State | When | Presentation |
| --- | --- | --- |
| **loading** | No value yet, wait &lt; 20s | Skeleton `···` + “Valoración…” only (no meta “slot reserved” copy) |
| **ready** | `dcf_analysis` or `dcf_value_cents` present | Range if unreliable; else point + diagnostics |
| **unavailable** | Timeout / no model result | “—” + unavailable copy |
| **hidden** | ETF / crypto / no row | No slot |

## Anti-patterns avoided

- Do not leave empty whitespace with no affordance.
- Do not flash a large point estimate then remove it mid-session without a state transition.
- Do not treat provisional WACC base as high-confidence “truth” (badge + range).

## Related product rules

- CapEx multi-tag merge + last-resort interpolation (`edgar.rs`).
- Soft WACC: CoD = max(policy, rf+spread); debt-weight cap when CoD default (`dcf_model.rs`) — not a hard intrinsic/price cap.
- Header gap remains **analyst vs market** (unchanged).

## Journeys (climax)

1. Investor opens **T** → sees market + analyst immediately; DCF slot shows **loading**.
2. Worker finishes → slot transitions **loading → ready** (range if provisional).
3. If no FCF after timeout → **unavailable** (honest empty, not a flash).

## Follow-ups

- Backend `valuation_status: pending|ready|unavailable` would remove the 20s client timeout heuristic.
- Live rf / ERP / bond CoD would allow solid point estimates and tighter scenario bands.

## 2026-07-30 implementation notes

- Loading copy: label only (`Valoración…`) — no meta “slot reserved” prose.
- **Demand-driven valuation** on `get_symbol_detail` when DCF missing for equities (priority EDGAR thread) so the reserved slot can transition to **ready** without waiting for the full SP500 EDGAR sweep.
- Failures log to `feed.log` as `demand-valuation {sym}: …`.
- **Header = overview only** (market · analyst · model range/value + one reliability mark). WACC/FCF/shares diagnostics live in **Quant Lens → Expected value range**, not the price summary.
