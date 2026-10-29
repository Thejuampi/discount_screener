# V3.1 shadow evaluation — design

Question: for each shadow config, do the official-Act rows it would **demote** (shadow Watch)
subsequently underperform the official-Act rows it **keeps**? If yes, by how much and how
consistently; that is the evidence for choosing a default (or none).

## Data

`history.sqlite` (headless, #67 + this PR): `score_runs` (profile, model, captured_at),
`score_history` (official decision, `price_cents` per symbol per run), `score_shadow`
(per run × ticker × config_id: m, s_adj, shadow_decision). Shadow rows are written in the same
transaction as the official run, so every shadow row has its official row and price.

## Method (`vantage-headless history-shadow-report`)

1. Per profile, the **trading-day calendar** is the set of distinct America/New_York market days
   with at least one recorded `v3` run (the after-close run guarantees one per weekday while the
   daemon is up). Horizons are counted in these recorded days: 5, 20, 60.
2. **Entry** per (profile, symbol, day, config) = the day's last run. Only rows whose official
   decision is Act are evaluated; they are split into demoted (shadow Watch) and kept (shadow Act).
3. **Forward return** at h = price_cents on calendar[i + h] (that day's last run) / entry price − 1.
   Missing exit prices are skipped, never imputed. Recorded prices are the screener's quote at
   run time (not adjusted for dividends/splits) — acceptable for relative demoted-vs-kept spreads.
4. Per config × horizon: n, mean, median, hit rate (> 0) for each group, and
   kept − demoted mean in percentage points. Status `insufficient data` while either group has
   fewer than `--min-n` (default 20) observations.

Overlapping windows (one symbol contributes on consecutive days) make observations correlated;
treat n as an upper bound and look for spreads that are stable across horizons and configs
before acting. Survivorship: symbols leaving a universe stop being priced and drop out.

## Decision criteria (proposal)

Pick a config only when, at 20 and 60 days, both groups have n ≥ 20 distinct entries in each
universe, kept − demoted is positive in both universes, and it does not reverse between
neighbouring configs. Otherwise keep V3.

## Commands

```
vantage-headless history-shadow-report --data-dir DIR [--profile sp500] [--min-n 20] [--json]
```

Equivalent SQL for the demoted/kept entries (forward prices are joined per calendar offset in code):

```sql
SELECT r.profile, s.config_id, h.symbol, h.captured_at, h.price_cents,
       h.decision AS official, s.shadow_decision, s.m, s.s_adj
FROM score_shadow s
JOIN score_history h ON h.run_id = s.run_id AND h.symbol = s.ticker
JOIN score_runs   r ON r.run_id = s.run_id
WHERE r.model = 'v3' AND h.decision = 'Act'
ORDER BY r.profile, s.config_id, h.symbol, h.captured_at;
```
