# Identity examples

Each outline is one automated test. Each row is one Case. Add a Case to a table before you write a new outline.

Dropped YAML keys (must not own the cell):

```
high_risk_ratio_bps
low_risk_ratio_bps
cheap_price_to_fair_bps
hedge_cost_cap_bps
protective_put_cost_cap_bps
max_quote_spread_bps
revenue_override_z_bps
```

Live YAML keys (`earnings-gate-policy/2` only):

```
min_sue_quarters
min_revenue_trail_quarters
av_daily_limit
av_per_minute
av_cache_fresh_days
sue_match_days
```

JSONL dual-read:

| Old key | New key |
|---|---|
| `revenueTrailMedianCents` | `revenueTrailCentreCents` |
| `sectorOverrideApplied` | `revenueTrailCut` |

When both keys exist on one object, the new name wins.

Undecided reasons the card must name:

| Code | When |
|---|---|
| `chain_unavailable` | no priced move |
| `quiet_dominates_implied` | quiet-day drift ≥ total implied move |
| `option_width_ge_straddle` | `quoteSpreadBps >= 10000` |
| `price_unavailable` | last price missing or ≤ 0 |
| `dcf_unavailable` | fair missing or non-positive |
| `ar_unavailable` | median \|AR\| missing or zero, so no ratio |

Order is the table order. The card prints the winning reason. A log-only reason fails.

Quiet ≥ total with a stored `impliedMoveBps` still uses `quiet_dominates_implied`. It does not become High.

## Cheap vs fair

```gherkin
Scenario Outline: price against DCF fair
  Given a priced event with price_cents <price> and dcf_fair_cents <fair>
  And riskRatioBps 15000 and a tight quote
  When the matrix decides
  Then the valuation side is <side>
  And the cell is <cell>

  Examples:
    | Case            | price | fair  | side      | cell               |
    | below fair      | 9000  | 10000 | Cheap     | CheapHighRisk      |
    | equal fair      | 10000 | 10000 | Expensive | ExpensiveHighRisk  |
    | above fair      | 11000 | 10000 | Expensive | ExpensiveHighRisk  |
    | missing fair    | 9000  |       | Undecided | Undecided          |
    | halted price 0  | 0     | 10000 | Undecided | Undecided          |
```

The missing-fair Case reason is `dcf_unavailable`. Halted price 0 reason is `price_unavailable`. `riskRatioBps=15000` means 1.5. High threshold is `riskRatioBps > 10000`. Equal-fair and above-fair action is Exit when High. Cover stays on CheapHighRisk only.

## High vs own reaction

```gherkin
Scenario Outline: event move against median absolute abnormal return
  Given a cheap priced event with event_move_bps <event> and median_abs_ar_bps <ar>
  And a tight quote
  When the matrix decides
  Then the risk side is <risk>
  And the cell is <cell>

  Examples:
    | Case         | event | ar    | risk      | cell              |
    | above own    | 12000 | 10000 | High      | CheapHighRisk     |
    | equal own    | 10000 | 10000 | Normal    | CheapNormalRisk   |
    | below own    | 8000  | 10000 | Normal    | CheapNormalRisk   |
    | missing AR   | 12000 |       | Undecided | Undecided         |
    | zero AR      | 12000 | 0     | Undecided | Undecided         |
```

The missing-AR and zero-AR Cases reason is `ar_unavailable`.

## Stale quote

```gherkin
Scenario Outline: option width against the straddle
  Given a cheap high-risk event whose quoteSpreadBps is width / straddle times 10000
  And that value is <spread>
  When the matrix decides
  Then the cell is <cell>

  Examples:
    | Case            | spread | cell          |
    | width below     | 9999   | CheapHighRisk |
    | width equal     | 10000  | Undecided     |
    | width above     | 15402  | Undecided     |
```

Equal and above reason is `option_width_ge_straddle`. LVS live 2026-08-27 printed 15402.

## Hedge cost against the event

```gherkin
Scenario Outline: cheap high-risk hedge
  Given a cheap high-risk event with eventImpliedMoveBps <event>
  And putSpreadCostBps <spread> and protectivePutCostBps <put>
  When the matrix decides
  Then action is <action> and hedge is <hedge>

  Examples:
    | Case              | event | spread | put  | action | hedge         |
    | cheap spread      | 800   | 400    | 600  | Hedge  | PutSpread     |
    | cheap put only    | 800   |        | 500  | Hedge  | ProtectivePut |
    | spread at event   | 800   | 800    | 500  | Hedge  | ProtectivePut |
    | both at event     | 800   | 800    | 800  | Reduce | None          |
    | both over event   | 800   | 900    | 1000 | Reduce | None          |
    | no quotes         | 800   |        |      | Reduce | None          |
```

`spread at event` still buys the put when the put costs less than the event. YAML `hedge_cost_cap_bps: 100` must not flip any Case.

## Revenue trail cut

```gherkin
Scenario Outline: latest print against centre minus scale
  Given a cheap normal Hold with latest <latest>, centre <centre>, scale <scale>
  When the matrix decides
  Then revenueTrailCut is <cut> and positionSizeBps is <size>

  Examples:
    | Case            | latest | centre | scale | foreign | prints | cut   | size  |
    | more than one   | 80     | 100    | 10    | false   | 4      | true  | 5000  |
    | equal one       | 90     | 100    | 10    | false   | 4      | false | 10000 |
    | above centre    | 110    | 100    | 10    | false   | 4      | false | 10000 |
    | flat below      | 90     | 100    | 0     | false   | 4      | true  | 5000  |
    | flat equal      | 100    | 100    | 0     | false   | 4      | false | 10000 |
    | foreign prior   | 80     | 100    | 10    | true    | 4      | false | 10000 |
    | short window    | 80     | 100    | 10    | false   | 3      | false | 10000 |
```

`foreign prior`: one of the prior prints is `isForeignTo`. Trail refuses. No cut from a contaminated window.

`short window`: fewer prints than YAML `min_revenue_trail_quarters`. Trail refuses.

Cell stays `CheapNormalRisk` on every Case. YAML `revenue_override_z_bps` must not change a Case.

Cheap + High with the same revenue dip stays `CheapHighRisk` and does not take the trail cut. Trail runs only on CheapNormal Hold.

## Event move refuse

```gherkin
Scenario Outline: quiet-day drift against total implied move
  Given total implied move 1000 bps and quiet drift <quiet>
  When event move is computed
  Then eventImpliedMoveBps is <event> and reason is <reason>

  Examples:
    | Case         | quiet | event | reason                  |
    | quiet below  | 600   | 800   |                         |
    | quiet equal  | 1000  |       | quiet_dominates_implied |
    | quiet above  | 1200  |       | quiet_dominates_implied |
```

`quiet below` is `sqrt(1000² − 600²) = 800`. No 30% floor fills the refused Cases.

A Case with quiet ≥ total and a stored `impliedMoveBps=1000` plus usable AR still leaves the cell `Undecided` with reason `quiet_dominates_implied`. No High. No Cover.

## Short put

```gherkin
Scenario Outline: first cheaper quoted put below ATM
  Given ATM put mid 4.00 at strike 100
  And quoted puts <puts>
  When the hedge quote is built
  Then short strike is <short>

  Examples:
    | Case            | puts                                      | short |
    | adjacent cheaper| 99@3.80, 95@2.00                          | 99    |
    | skip equal mid  | 99@4.00, 97@3.50                          | 97    |
    | skip unquoted   | 99@bid0, 95@2.00                          | 95    |
```

`adjacent cheaper` is the falsifier for a 5% OTM chooser: that chooser would pick 95.

## YAML leftover cannot own the cell

```gherkin
Scenario Outline: dropped keys do not move the cell
  Given policy file still lists cheap_price_to_fair_bps 9000 and high_risk_ratio_bps 13000
  And a priced event with price/fair <ratio_pf> and event/AR <ratio_risk>
  When the matrix decides
  Then the cell is <cell>

  Examples:
    | Case              | ratio_pf | ratio_risk | cell              |
    | 0.95 times fair   | 9500     | 11000      | CheapHighRisk     |
    | 1.10 times own AR | 8000     | 11000      | CheapHighRisk     |
```

Under `/1` those rows were Expensive and Normal. Under `/2` they are Cheap High.

## Upcoming re-run, settled freeze

```gherkin
Scenario Outline: policy bump against settlement
  Given a priced event whose stored cell was Expensive because price/fair was 9500
  And settlement is <settled>
  When the recorder passes with identities
  Then the stored cell is <cell> and option-chain calls are <calls>

  Examples:
    | Case      | settled | cell          | calls |
    | upcoming  | false   | CheapHighRisk | 0     |
    | settled   | true    | ExpensiveHighRisk | 0  |
```

The upcoming Case needs High risk on the stored pre block so Cheap High is the identity cell.

Two recorder passes over an upcoming event whose identity cell is already stored write zero extra JSONL lines and zero chain calls.

## Reason order

```gherkin
Scenario Outline: one Undecided reason wins
  Given price_cents <price> and dcf_fair_cents <fair>
  And quiet_dominates <quiet> and median_abs_ar_bps <ar>
  When the matrix decides
  Then the cell is Undecided and the card reason is <reason>

  Examples:
    | Case            | price | fair  | quiet | ar    | reason                  |
    | quiet beats AR  | 9000  | 10000 | true  |       | quiet_dominates_implied |
    | halted beats DCF| 0     |       | false | 10000 | price_unavailable       |
    | chain beats quiet | 9000 | 10000 | true  |       | chain_unavailable       |
    | stale beats price | 0   | 10000 | false | 10000 | option_width_ge_straddle |
    | dcf beats AR    | 9000  |       | false |       | dcf_unavailable         |
```

The card text carries the reason. A blank Undecided fails. `halted beats DCF` does not set quiet. `chain beats quiet` leaves the implied move unset. `stale beats price` sets `quoteSpreadBps` to 10000.

## Dual-read

```gherkin
Scenario Outline: old JSONL keys still feed the trail
  Given a cheap normal Hold JSONL object with keys <keys>
  When the matrix decides
  Then revenueTrailCut is <cut>

  Examples:
    | Case        | keys                                              | cut   |
    | old only    | revenueTrailMedianCents=100, latest=80, scale=10  | true  |
    | new only    | revenueTrailCentreCents=100, latest=80, scale=10  | true  |
    | both, new wins | median=50, centre=100, latest=80, scale=10     | true  |
```

`both, new wins`: old median 50 would not cut (80 is above 50 − 10). New centre 100 does cut.
