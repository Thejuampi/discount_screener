# score-history-v1 (contract, doc only)

Source: `vantage-headless serve` → `GET /history?symbol=TICKER[&days=N][&changes=1]`
(CLI: `vantage-headless history TICKER [--days N] [--changes]`).

## Response
```json
{
  "symbol": "ADSK",
  "days": 30,
  "points": [
    {"run_id": "sp500-1761750000-v3", "captured_at": 1761750000, "profile": "sp500",
     "model": "v3", "rule_version": "V3", "market_context": false,
     "rank": 12, "decision": "Act", "composite": 71.2, "fundamentals": 68.0,
     "technical": 74.1, "forecast": 70.3, "regime": null, "confidence": 0.82,
     "trust_signal": "ok", "price_cents": 23150, "target_cents": 27800, "upside_bps": 2008}
  ],
  "changes": [
    {"captured_at": 1761750000, "from": "Watch", "to": "Act", "run_id": "...", "rule_version": "V3"}
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
