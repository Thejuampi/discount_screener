# Android Earnings Gate

The Earnings tab helps Juan review event risk before a reported earnings date.

It does not change DCF, ranking, or portfolio position size automatically.

## Sources

- Yahoo supplies the earnings calendar and revenue trail.
- SEC 8-K item 2.02 confirms reported dates.
- Alpha Vantage supplies the SUE diagnostic.
- The imported earnings log supplies recorded events.
- `shared/contracts/earnings-gate-policy.yaml` owns live numeric policy.

## Event Cell

The cell compares the event move with the issuer's median absolute abnormal return.

- `Cheap` means price is below the available model value.
- `High` means the event-move ratio is above one.
- `Stale` means the quote width meets or exceeds the straddle.
- A hedge qualifies when its cost stays below the event move.
- A quiet total refuses the event move.

The cell does not use SUE until Juan requests that product change.

## SUE Diagnostic

Alpha Vantage SUE remains a card diagnostic.

- Join observations that belong to both the issuer SUE and abnormal-return series.
- Remove observations that `isForeignTo` rejects.
- Report the remaining sample count as `n`.
- Use `short_history` below `min_sue_quarters`.
- Show the fitted slope and `n`.
- Do not add a maximum-quarter cap.

The Alpha Vantage key stays at `filesDir/earnings/alphavantage.key`.

Blank Save changes nothing. Clear deletes the key. Never print the key.

## Revenue Trail

Use the latest `min_revenue_trail_quarters` Yahoo reports.

The latest report is the event. The earlier reports form the comparison window.

- Use `robustCentre` for the earlier window.
- Use MAD for its scale.
- Refuse a short window.
- Refuse a foreign report in the earlier window.
- Cut a Hold to half size below one scale unit.
- For zero scale, cut when the latest report is below the mode.
- Do not add a percent floor.

The output records `revenueTrailCut` and `revenueTrailCentreCents`.

## Presentation

Detail always shows an earnings card first.

- A priced log event uses `EarningsEventCard`.
- A dated event without a model uses the same card frame.
- A dated event never uses `No report on the calendar`.
- Missing or stale inputs remain visible.
- The card opens in Simple mode on Earnings and ticker Detail.
- Simple mode explains the saved report move, past moves, and neutral choices without options.
- A held ticker shows ways to keep shares, hold fewer shares, or wait before adding.
- An unheld ticker shows the tradeoffs of waiting or buying before the report.
- A settled report shows its recorded outcome instead of future choices.
- Options mode keeps the model data, put example, saved expiry, and source limits.
- A saved option example is not a live order. It lacks contract count, live price, and fees.
- Options mode flags saved action and put fields that disagree.

Dates come from these sources:

1. The earnings log.
2. The shared Yahoo calendar cache.
3. A scored row.

Dates do not depend on the active universe profile.

## Import Book Boundary

Import book appears on Earnings, System, and Positions.

Restore log is a separate Earnings action. It never plans a lot write.

Read [Advisor CSV import](advisor-csv-import.md) for book semantics.
