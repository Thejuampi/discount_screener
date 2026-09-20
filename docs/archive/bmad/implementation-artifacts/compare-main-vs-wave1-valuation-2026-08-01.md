# Compare `origin/main` vs `valuation/wave1-integration`

Repo default branch is **`main`** (no `master` remote).

## Commits on feature not on main

```
f60fdca docs(valuation): capture P0 live QA baseline post-PR #27
05d2975 feat(valuation): P4 financial driver surface for residual income
c10aef1 feat(valuation): P2 source-continuity gate (SNDK-class)
3b25cf7 feat(valuation): P3 versioned through-cycle industry beta policy
+ merges into wave1-integration
```

Uncommitted on working tree: AMZN CapEx-trough **nonneg base-margin** fix (policy **/14**), ds-ui tooling.

## Apples-to-apples engine probe (same fixtures)

Unit-test harness `branch_compare_valuation_snapshot` run on:

| Tree | Commit / state | Policy |
| --- | --- | --- |
| MAIN | `origin/main` @ 32b5c96 | `/12-robust-fcff-growth-evidence` |
| WAVE1_CLEAN | `7957c90` (wave1 tip, no dirty) | `/13-industry-beta-policy-v1` |
| WAVE1_FIX | working tree (+ nonneg margin) | `/14-fcff-nonneg-base-margin` |

| Case | MAIN base | WAVE1_CLEAN | WAVE1_FIX | Notes |
| --- | ---: | ---: | ---: | --- |
| **AMZN full trough** (2020–25 drivers, live-shaped) | **$7.18** | **$6.87** | **$55.11** | Collapse exists on **main too**; wave1 ~same; fix recovers |
| **AMZN contract window** (2022–25 only) | $31.73 | $30.88 | $55.02 | P3 +14 bps WACC → slightly lower; fix raises run-rate |
| **T simple** | $61.75 | $63.30 | $63.30 | Wave1 slightly **higher** (telecom beta prior) |
| **AAPL simple** | $108.55 | $108.55 | $108.55 | Identical |
| **CI residual** | $143.41 | $143.41 | $143.41 | Identical |
| **JPM residual** | $111.00 | $111.00 | $111.00 | Identical |

WACC sample (AMZN contract): main **1002** bps → wave1 **1017** bps (industry-beta table), run-rate identical until /14.

## What wave1 actually changed for numbers

1. **P3 industry-beta policy** — mapped priors + through-cycle commodity pull; small CoE/WACC shifts (AMZN slightly lower, T slightly higher).
2. **P2 source continuity** — refuses/gates continuity failures (SNDK-class); does not reprice healthy FCFF paths.
3. **P4 financial driver surface** — residual income driver plumbing; CI/JPM fixtures above **unchanged**.
4. **AMZN $5 live** — **not unique to wave1**: main also produces **~$7** on live-shaped full-trough history. Offline greens used a short contract window that hid the trough median collapse.

## Live feature tree (after /14 fix, qa profile)

| Symbol | Model | Base (live) | Status |
| --- | --- | --- | --- |
| T | FCFF | ~$60.55 | selected |
| AMZN | FCFF | ~$50.30 | disputed vs forward |
| CI | residual | ~$191 | ok |
| JPM | residual | ~$156 | ok |
| ACGL | residual | ~$87 | ok |
| AAPL | FCFF | ~$117 | disputed |
| COF | residual | ~$169 | ok |

## Verdict

| Claim | Evidence |
| --- | --- |
| “Wave1 alone destroyed AMZN” | **False** — main is equally collapsed on live-shaped trough history |
| “Wave1 P3 hurts some FCFF names a bit” | **True** — ~10–15 bps WACC / few % of base on AMZN-class |
| “Residual income path diverged” | **False** on fixed RI fixtures |
| “Numbers still far from market on AMZN/AAPL” | **True and pre-existing** on main contract/main live paths; /14 fixes trough collapse, not “model = street price” |

## If Juan wants main-like or better levels

Options (need explicit product choice):

1. **Keep /14 nonneg base margin** (already in working tree) — mandatory anti-collapse.
2. **Revisit P3 priors** only if through-cycle entries over-penalize non-commodity names (data above: effect is small vs trough bug).
3. **Do not silent-merge wave1** until /14 is committed and live checklist re-run with multi-name bar.

## Method

Worktrees: `discount_screener-wt-main-compare`, `discount_screener-wt-wave1-clean`; temporary unit probe (removed from active tree after run).
