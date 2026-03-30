# SQLite Query Patterns

Use these patterns when querying the repository's persistence database.

## First Checks

```sql
PRAGMA user_version;
```

```sql
SELECT name
FROM sqlite_master
WHERE type = 'table'
ORDER BY name;
```

```sql
PRAGMA table_info(symbol_latest);
```

## Table Selection

- `symbol_latest`: current evaluated state for each symbol and warm-start hydration.
- `symbol_revision`: append-only history of evaluated symbol state.
- `raw_capture`: raw provider payload history.
- `raw_latest`: latest capture id for each symbol and capture key.
- `tracked_symbol`: tracked universe ordering.
- `watchlist`: persisted watchlist symbols.
- `issue_state`: persisted operational issues.
- `meta`: last-startup and persistence metadata.

## Ready-To-Run SQL

### Latest Evaluated State For One Symbol

```sql
SELECT
  symbol,
  revision_id,
  evaluated_at,
  payload_json
FROM symbol_latest
WHERE symbol = 'NVDA';
```

### Full Evaluated History For One Symbol

```sql
SELECT
  revision_id,
  symbol,
  evaluated_at,
  last_sequence,
  update_count,
  payload_json
FROM symbol_revision
WHERE symbol = 'NVDA'
ORDER BY evaluated_at ASC, revision_id ASC;
```

### Raw Source History For One Symbol

```sql
SELECT
  id,
  symbol,
  capture_kind,
  scope_key,
  captured_at,
  payload_json
FROM raw_capture
WHERE symbol = 'NVDA'
ORDER BY captured_at ASC, id ASC;
```

### Market Price Time Series

```sql
SELECT
  datetime(evaluated_at, 'unixepoch') AS ts,
  json_extract(payload_json, '$.snapshot.market_price_cents') AS price_cents
FROM symbol_revision
WHERE symbol = 'NVDA'
ORDER BY evaluated_at ASC, revision_id ASC;
```

### Intrinsic Value And Gap Time Series

```sql
SELECT
  datetime(evaluated_at, 'unixepoch') AS ts,
  json_extract(payload_json, '$.snapshot.intrinsic_value_cents') AS intrinsic_value_cents,
  json_extract(payload_json, '$.gap_bps') AS gap_bps
FROM symbol_revision
WHERE symbol = 'NVDA'
ORDER BY evaluated_at ASC, revision_id ASC;
```

### Watchlist Contents

```sql
SELECT symbol
FROM watchlist
ORDER BY symbol ASC;
```

### Tracked Universe Order

```sql
SELECT position, symbol
FROM tracked_symbol
ORDER BY position ASC;
```

### Active Persisted Issues

```sql
SELECT
  key,
  source,
  severity,
  title,
  detail,
  issue_count,
  first_seen_event,
  last_seen_event
FROM issue_state
WHERE active = 1
ORDER BY last_seen_event DESC, key ASC;
```

### Persistence Metadata

```sql
SELECT key, value
FROM meta
ORDER BY key ASC;
```

## Interpretation Notes

- Use `symbol_latest` for startup and warm-start questions.
- Use `symbol_revision` for trend, timing, and regression questions.
- Use `raw_capture` for source audit questions or when evaluated payloads look suspicious.
- Older revisions can legitimately miss newer metric groups.
- `chart_summaries` is stored inside revision JSON rather than normalized indicator tables.

## Repository Anchors

- Schema and migrations are defined in `src/persistence.rs`.
- Query guidance and more examples live in `docs/HISTORY_TIME_SERIES.md`.
- Restore behavior is covered by the warm-start and migration tests in `src/main.rs`.
