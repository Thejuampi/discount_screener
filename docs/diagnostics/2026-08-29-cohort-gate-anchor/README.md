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

Do not close this gate by moving the threshold. It measures the model against the bar, so green
comes from a driver or it does not come. See [operational anti-patterns](../../operational-anti-patterns.md).
