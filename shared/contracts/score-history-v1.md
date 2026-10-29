# score-history-v1 (contract, doc only)

Source: `vantage-headless serve` → `GET /history?symbol=TICKER[&days=N][&changes=1]`
(CLI: `vantage-headless history TICKER [--days N] [--changes]`).

## Response
```json
{
  "symbol": "ADSK",
  "days": 30,
  "points": [
    {"run_id": "sp500-1761750000-v3", "captured_at": 1761750000, "date": "2025-10-29 15:00",
     "profile": "sp500", "model": "v3", "rule_version": "V3", "market_context": false,
     "rank": 12, "decision": "Act", "composite": 71, "fundamentals": 68, "technical": 74,
     "forecast": 70, "regime": null, "confidence": "High", "trust_signal": "ok",
     "price_cents": 23150, "target_cents": 27800, "upside_bps": 2008}
  ],
  "changes": [
    {"from_date": "2025-10-28 15:00", "to_date": "2025-10-29 15:00", "from": "Watch", "to": "Act",
     "composite_delta": 4, "fundamentals_delta": 1, "technical_delta": 6, "forecast_delta": null,
     "point": { "...": "HistoryPoint of the new row" }}
  ]
}
```
- `points` ascending by `captured_at` (UTC epoch seconds). `changes` only when `changes=1`.
- `rule_version` comes from `SCORE_RULE_VERSION` (`apps/windows/src-tauri/src/score_history.rs`).
  Clients must not compare decisions across different `rule_version` values as "flips" without labelling them.
- Nullable: score sub-components, `regime`, `price_cents`, `target_cents`, `upside_bps`.
- Unknown symbol / no runs → `points: []` (HTTP 200). Missing `symbol` → HTTP 400.

## Semantics
- One row per symbol per finished feed run (android V3 ranking, as served by `/ranking`).
- Retention: every run ≤30 days; 30d–2y one per UTC day (last of day); decision flips and each symbol's first row kept forever.
- Additive fields may appear; Android must ignore unknown keys. Breaking changes → `score-history-v2`.
