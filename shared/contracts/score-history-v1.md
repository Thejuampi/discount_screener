# score-history-v1 (contract, doc only)

Source: `vantage-headless serve` → `GET /history?symbol=TICKER[&days=N][&changes=1][&profile=P]`
(CLI: `vantage-headless history TICKER [--days N] [--changes] [--profile P] [--tz IANA] [--json]`).

- `symbol` must match `^[A-Z0-9.\-^=]{1,16}$` after upper-casing (percent-encoded in the query); else HTTP 400.
- `profile` defaults to the server's current profile. Points and changes are always one profile and one model.
- All API times are UTC: `captured_at` (epoch seconds) and `captured_at_utc` (ISO-8601, `Z`).
  Only the CLI renders local time (`--tz`, default `America/New_York`, with zone label such as `EDT`/`EST`);
  CLI `--json` adds `tz` and `captured_at_local` / `from_local` / `to_local` next to the UTC fields.

## Response
```json
{
  "symbol": "ADSK", "profile": "sp500", "model": "v3", "days": null,
  "points": [
    {"run_id": "sp500-1793298023-v3", "captured_at": 1793298023, "captured_at_utc": "2026-10-29T18:20:23Z",
     "profile": "sp500", "model": "v3", "rule_version": "V3", "rule_change": false, "trigger": "interval",
     "market_context": false, "rank": 8, "decision": "Watch", "composite": 26,
     "fundamentals": 13, "technical": 9, "forecast": 45, "regime": null,
     "confidence": "high", "trust_signal": null,
     "price_cents": 24175, "target_cents": 30250, "upside_bps": 2512}
  ],
  "changes": [
    {"from_at": 1793200000, "to_at": 1793298023, "from_utc": "...", "to_utc": "...",
     "from": "Act", "to": "Watch", "composite_delta": -12,
     "fundamentals_delta": 0, "technical_delta": -20, "forecast_delta": null,
     "point": {"...": "HistoryPoint of the new row"}}
  ]
}
```
- `points` are ordered by (`captured_at`, `run_id`). `changes` is present only with `changes=1`.
- Non-null: `run_id`, `captured_at`, `captured_at_utc`, `profile`, `model`, `rule_version`, `rule_change`,
  `trigger` (`interval` | `close` | `snapshot`), `market_context`, `rank`, `decision`, `composite`, `price_cents`.
  Nullable: `fundamentals`, `technical`, `forecast`, `regime`, `confidence`, `trust_signal`, `target_cents`, `upside_bps`.
  Rows whose price is not finite are never stored (so `price_cents` is never 0 for "unknown").
- `rule_version` comes from `SCORE_RULE_VERSION` (`apps/windows/src-tauri/src/score_history.rs`).
  `rule_change: true` marks the first point after a version boundary: a new series starts there and it is
  never reported as a decision change.
- Unknown symbol / no runs → `points: []` (HTTP 200).

## Semantics
- One row per symbol per recorded run (android V3 ranking, as served by `/ranking`): every
  `--history-interval-hours` (default 6) and once after each US weekday close (~16:10 America/New_York).
- History is **append-only and kept forever**: no retention, pruning or TTL.
- Additive fields may appear; clients must ignore unknown keys. Breaking changes → `score-history-v2`.

## Storage: `score_shadow` (schema v2, V3.1 shadow mode)

Not exposed by `/history`. Additive, append-only (DELETE/UPDATE abort triggers), written in the same
transaction as the official `score_runs`/`score_history` rows.

| column | type | notes |
|---|---|---|
| run_id | TEXT NOT NULL | → score_runs.run_id |
| ticker | TEXT NOT NULL | = score_history.symbol |
| rule_version | TEXT NOT NULL | `V3.1-shadow` |
| config_id | TEXT NOT NULL | e.g. `b0200_lm030` (see docs/architecture/decision-rule-v3-1.md) |
| bound | REAL NOT NULL | |
| lambda_t | REAL NOT NULL | |
| lambda_m | REAL NOT NULL | |
| m | REAL | null when the raw histogram/close is missing or during warm-up |
| warmup | INTEGER NOT NULL | 1 when the Year series has < 35 weekly bars (m null, no penalty) |
| s_adj | REAL NOT NULL | |
| shadow_decision | TEXT | Act/Watch/Avoid; only ever differs from the official decision as Act → Watch |

PRIMARY KEY (run_id, ticker, config_id). Read with `vantage-headless history-shadow-report`.
