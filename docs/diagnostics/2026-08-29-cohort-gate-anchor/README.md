# The cohort gate charged the model a year it never claimed

**2026-08-29. Windows. `operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`.**

## What the gate said

Mean error over the fifteen reported names had to stay under 11%. It read 15.27%, and the assertion
was a bare `assert!`, so the failure named no symbol and no direction.

## What the numbers said once the gate printed them

| Reading | Before |
| --- | --- |
| Reported mean error | 15.27% |
| Reported worst | WYNN 35.3% |
| Names below the anchor | 13 of 15 |

Thirteen of fifteen on the same side is not scatter. A driver that leans one way produces that
shape, and so does an anchor that sits in the wrong place.

## The anchor sat one year ahead

`analystTargetCents` is a Street price target twelve months out. The model states a value today.
Comparing the two charges the model one year of required return on every name, whatever the model
does.

The correction uses each row's own `resolvedCostOfEquityBps`, which the contract already carries:

```
present value of target = target / (1 + cost of equity)
```

No driver moves. No threshold moves.

| Reading | Before | After |
| --- | --- | --- |
| Reported mean error | 15.27% | 11.51% |
| Reported worst | 35.3% | 29.6% |
| Reported names below the anchor | 13 of 15 | 11 of 15 |
| Holdout mean error | 11.90% | 11.53% |
| Holdout names below the anchor | 10 of 11 | 4 of 11 |

The holdout line is the one that settles it. Its one-sided miss was almost entirely the anchor, and
it went away without touching the model.

## What is left is the model

The gate is still red: 11.51% against 11.0%, and 11.53% against 11.5%. Eleven of the fifteen
reported names now sit under 11%. Four carry the failure:

| Name | Error | Model | Target at present value |
| --- | --- | --- | --- |
| WYNN | 29.6% | 8 631 | 12 258 |
| AMZN | 19.7% | 23 092 | 28 775 |
| MU | 18.1% | 158 235 | 133 942 |
| CEG | 14.4% | 27 689 | 32 334 |

MU is the only one of the four that reads high. Start with WYNN: it is a quarter of the reported
cohort's remaining error on its own.

## The hold-years rule is not the cause. Measured, not argued.

`derive_hold_years` returns 0 for any name growing faster than 12% a year unless it is a
semiconductor. AMZN takes that branch with a 22.2% return on capital and `durable_excess_return_evidence`
on the row, which looks like the fastest compounders getting the least credit. It looked like the
cause of the reported cohort's error.

`cohort_hold_years_sweep` says otherwise. Value against the present-value anchor, per hold length:

| Name | Return on capital | 0y (today) | 3y | 5y | 7y |
| --- | --- | --- | --- | --- | --- |
| GOOGL | 41.6% | **0.2%** | 43.1% | 78.9% | 122.0% |
| NVDA | 107.5% | **2.5%** | 2.5% | 2.5% | 2.5% |
| HPE | 5.3% | **3.6%** | 26.5% | 48.2% | 71.5% |
| ORCL | 14.1% | **6.9%** | 31.2% | 61.7% | 97.5% |
| AVGO | 23.1% | **9.8%** | 27.6% | 58.2% | 94.3% |
| MSFT | 27.3% | **12.3%** | 56.5% | 92.6% | 134.8% |
| AMZN | 22.2% | 19.7% | **1.1%** | 16.5% | 33.1% |
| META | 22.1% | **21.6%** | 73.0% | 115.6% | 166.4% |

Seven of the eight high-growth names are best at zero hold, most of them by a wide margin. Granting
an explicit hold to names with durable excess returns would fix AMZN and break the other six. The
fade-only shortcut earns its place.

What the sweep does say about the four names that carry the error:

- **AMZN** wants a 3-year hold and nothing else. One name, not a rule.
- **WYNN** reports no return on equity, so the ladder drops it to zero. A 7-year hold lands it at
  3.2%. The missing input is the problem, not the hold length.
- **CEG** takes the utilities branch at 5 years and still reads 14.4%. It wants 10.
- **MU** moves 1.3 points across the whole sweep. Its 18.1% error lives in the earnings or the
  terminal, not in the hold.

Two of the four are missing or thin inputs. Chase the inputs before the policy.

## WYNN: the number was reported and thrown away

The sweep said WYNN's problem is a missing input. It is. `durable_cohort_return_on_capital_capture`
went and asked Yahoo:

| Field | WYNN |
| --- | --- |
| Reported book value per share | `-166` cents |
| Book value the snapshot kept | none |
| Return on equity | none |
| Debt to equity | none |
| Shares outstanding | 102 973 891 |
| Total debt | $12 342 463 488 |

Yahoo reports the book value. `resolve_book_value_per_share_cents` dropped it for being negative,
because every ratio built on equity alone - price to book, residual income, return on equity -
breaks on a deficit. That filter is right for those consumers and wrong for one: return on
**invested** capital adds debt back, and WYNN's capital base is `-171 M + 12.34 B = 12.17 B`, plainly
positive.

Both of WYNN's routes to a return on capital were closed by the same sign:

- `through_cycle_return_on_capital_bps` refused the row on `book_value_per_share_cents > 0`, two
  lines above an `invested <= 0` guard that already protects the division.
- `unlevered_return_on_equity_bps` needs a return on equity, and Yahoo reports none. On a deficit it
  never will. That route is closed for this issuer permanently.

So the snapshot now carries `book_value_per_share_cents_with_deficit` beside the positive-only
field. One consumer reads it. Nothing else changes, and `fund-runtime/2` became `/3` so the
fingerprint change is declared instead of silent.

GDDY is the same shape from the other side: five cents of book value against 573x gearing, which
`MAX_MEANINGFUL_GEARING_HUNDREDTHS` refuses on the equity route. Its invested capital is $3.85 B.

This does not move the gate. The cohort rows are frozen inputs, and WYNN's still needs a normalized
FCFF before the through-cycle route can produce a number for it. What it does is reopen the route.
Before this, no amount of correct data could have reached WYNN, because the sign of its equity
closed both doors.

Do not close this gate by moving the threshold. It measures the model against the bar, so green
comes from a driver or it does not come. See [operational anti-patterns](../../operational-anti-patterns.md).
