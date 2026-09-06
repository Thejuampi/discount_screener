---
id: SPEC-earnings-gate-identities
status: final
policyVersion: earnings-gate-policy/2
companions:
  - identities.md
  - ../../project-context.md
  - ../../../shared/contracts/earnings-gate-policy.yaml
sources:
  - ../../planning-artifacts/prd-pre-earnings-risk-gate-2026-08-27.md
---

> **Canonical contract.** This SPEC and `identities.md` freeze the Android pre-earnings cell. The PRD is why. YAML `/2` holds counts and Alpha Vantage budget only.

# Earnings gate identities

## Why

YAML percents still own the cell after PR #50: cheap at 0.9× DCF, high at 1.3× own reaction, hedge caps at 1% / 1.5% of stock, stale at 50% of the straddle, trail z at 10000 bps. A YAML percent is a frozen value. The gate must use identities. The 1% / 1.5% cap almost never buys a real earnings hedge, because an ATM put costs about half the straddle.

## Capabilities

- **CAP-1**
  - **intent:** The left column of the matrix is price against DCF fair.
  - **success:** Price < fair is Cheap. Price = fair is Expensive. Price > fair is Expensive. Missing or non-positive fair is `Undecided` with reason `dcf_unavailable`. Non-positive price is `Undecided` with reason `price_unavailable`. No 0.9× band. Ratio and price-to-fair are bps: High is `riskRatioBps > 10000`. Cheap is `priceToFairBps < 10000`.

- **CAP-2**
  - **intent:** The right column of the matrix is event-move against this ticker's own median |AR|.
  - **success:** `riskRatioBps > 10000` is High. `riskRatioBps = 10000` is Normal. `riskRatioBps < 10000` is Normal. Missing ratio, missing median |AR|, or median |AR| = 0 is `Undecided` with reason `ar_unavailable`. No 1.3 / 0.8 band. No Low band.

- **CAP-3**
  - **intent:** A quote wider than the straddle does not price the report.
  - **success:** `quoteSpreadBps` is `width / straddle × 10000`. `quoteSpreadBps >= 10000` leaves `Undecided` with reason `option_width_ge_straddle`. The event stays in the log. The raw spread stays. Width < straddle does not stale.

- **CAP-4**
  - **intent:** Cheap + High covers when the hedge costs less than the event.
  - **success:** Compare hedge cost bps (premium / forward) to `eventImpliedMoveBps` only. No fallback to `impliedMoveBps`. Cost < event move buys the hedge at half size. Cost = event move, or cost > event move, cuts size and buys no hedge. Prefer a quoted put spread. Else the protective put. Missing hedge quotes cut size and buy no hedge. No 1% / 1.5% cap of stock. Trail cut does not run on this cell.

- **CAP-5**
  - **intent:** A cheap + normal Hold cuts to half when the latest revenue print sits more than one scale below the trail centre.
  - **success:** Cut when `latest < centre - scale`. Equal to `centre - scale` does not cut. Scale 0 cuts a latest print strictly below the mode. Centre is `robustCentre` of the prints before the latest. The window length is `min_revenue_trail_quarters` from YAML (`/2` value 4, so three prior). Fewer prints than that min refuses the trail. A foreign print in the prior window refuses the trail: `revenueTrailCut=false`, size stays full, cell stays `CheapNormalRisk`. Field `revenueTrailCentreCents`. Flag `revenueTrailCut`. No `revenue_override_z_bps`. No `sectorOverrideApplied` on a new write.

- **CAP-6**
  - **intent:** Quiet-day drift that eats the whole implied move leaves no event numerator.
  - **success:** Event move is `sqrt(total² − quiet²)` in bps when quiet < total. Quiet ≥ total leaves `eventImpliedMoveBps` unset, reason `quiet_dominates_implied`. The cell is `Undecided`. No 30% floor. Do not fill the event move from `impliedMoveBps` on that path.

- **CAP-7**
  - **intent:** The short put of the spread is the first cheaper quoted put below ATM.
  - **success:** Walk quoted puts with strike < ATM, nearest strike first. Take the first mid strictly below the ATM put mid. A 5% OTM target does not choose the strike.

- **CAP-8**
  - **intent:** YAML `/2` cannot own the cell. The six live keys still load from YAML.
  - **success:** Committed `earnings-gate-policy.yaml` version is `earnings-gate-policy/2`. It holds only `min_sue_quarters`, `min_revenue_trail_quarters`, `av_daily_limit`, `av_per_minute`, `av_cache_fresh_days`, `sue_match_days`. The loader reads those six at runtime. Kotlin holds no literals for them. Dropped percent keys have no reader and cannot own the cell, even if a fixture still lists them.

- **CAP-9**
  - **intent:** A policy bump does not leave an upcoming card on the old percents, and it does not refetch the chain.
  - **success:** An unsettled priced event re-runs the matrix from stored pre-report fields. Zero option-chain calls. Append last-wins only when cell, action, hedge, size, or justification differs. Identical output writes nothing. A settled event keeps the cell that was decided. No live-price rewrite of stored `priceCents`.

- **CAP-10**
  - **intent:** Old JSONL rows stay readable. New writes use honest names. The card reads the new fields.
  - **success:** Reader accepts `revenueTrailMedianCents` as centre and `sectorOverrideApplied` as trail cut. New writes use `revenueTrailCentreCents` and `revenueTrailCut`. When both keys exist on one object, the new name wins. `EarningsGatePresentation` prints the centre, the size-cut mark, and the Undecided reason from those fields.

## Constraints

- Identities live in the engine. YAML is not a back-door for percents.
- SUE stays off the cell.
- Median is not the trail level. `robustCentre` + MAD stay.
- Fail closed with a reason the card shows. Empty `Undecided` without a reason is a product bug. Reason order: `chain_unavailable`, then `quiet_dominates_implied`, then `option_width_ge_straddle`, then `price_unavailable`, then `dcf_unavailable`, then `ar_unavailable`.
- JSONL is append-only last-wins.
- Android `:core` owns the matrix. This spike does not port Windows or desktop.
- ATM grid refuse `MAX_STRIKE_OFFSET` 2.5% stays a data-quality bound.
- Half-size is the named Reduce action, not a YAML percent.
- A field the engine writes needs a reader.
- Specification by example: each identity is a Scenario Outline with at least two Cases. Tables live in `identities.md`.

## Non-goals

- SUE on the cell.
- Paper trading harness.
- Sector bands.
- Alpha Vantage revenue surprise.
- Windows or desktop port.
- Coverage-width hedge (short strike at or below forward × (1 − event move)). Sensei raised it. Advisor kept cost < event-move. Later spike.
- Collar.
- Rewrite of settled cells.

## Success signal

A cheap name with ratio 1.05 and a put that costs less than the event move gets a hedge. The same name at 0.95× DCF is Cheap, not Expensive. A YAML file that still lists `cheap_price_to_fair_bps: 9000` cannot put it back.

## Assumptions

- `quoteSpreadBps` already stores width / straddle in bps. CAP-3 pins that unit. It does not invent a second field.
- Hedge cost bps already stores premium / forward. CAP-4 compares that number to event-move bps.
- `EarningsEventRecorder` already skips a second chain fetch on a priced event. CAP-9 adds a matrix re-run on that stored pre block for unsettled rows.

## Open Questions

None. Spec review 1 revised. This text closes Advisor S-P0-01 and the P1 patches.
