---
name: database-querying
description: 'Query the SQLite persistence database used by this repository. Use when inspecting state.sqlite3, checking schema version or tables, looking up symbol history, reading watchlist or issue_state rows, debugging payload_json fields, or validating warm-start persistence behavior.'
argument-hint: 'Describe the database question, symbol, or table to inspect'
user-invocable: true
---

# Database Querying

Use this skill for read-heavy investigation of the repository's SQLite persistence store.

## When to Use

- Inspect the current persisted state for one symbol.
- Trace a symbol over time through canonical evaluated revisions.
- Audit raw provider payloads captured before evaluation.
- Check tracked symbols, watchlists, persisted issues, or metadata.
- Validate that persistence behavior matches code paths, docs, or tests.

## Repository Context

- The persistence store is SQLite and defaults to `state.sqlite3`.
- On Windows, the default path is `%LOCALAPPDATA%\discount_screener\state.sqlite3`.
- Startup can override the path with `--state-db PATH`.
- The schema version is managed with `PRAGMA user_version` and the current version is `3`.
- Schema creation and migrations live in `src/persistence.rs`.

## Procedure

1. Define the question before querying.
   Match the question to the narrowest table that can answer it.

2. Pick the right table.
   Use `symbol_latest` for current evaluated state and warm-start checks.
   Use `symbol_revision` for historical timelines and product-level state changes.
   Use `raw_capture` or `raw_latest` for source-payload auditing.
   Use `tracked_symbol`, `watchlist`, `issue_state`, or `meta` for operator state.

3. Locate the database file.
   Prefer the explicit `--state-db` path if the session was started with one.
   Otherwise use the default path logic from `src/persistence.rs`.
   If the database file does not exist, report that clearly and fall back to journal or watchlist files only if that still answers the user's question.

4. Inspect the schema in read-only mode.
   Start with `PRAGMA user_version;`, `SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name;`, and `PRAGMA table_info(<table_name>);` before writing more specific SQL.

5. Run the smallest useful query.
   Prefer `SELECT` and `PRAGMA` statements only unless the user explicitly asks for mutation.
   Filter by `symbol`, time range, or `capture_kind` early to avoid noisy output.
   Use the query examples in [sqlite-queries.md](./references/sqlite-queries.md) as the starting point.

6. Decode JSON columns deliberately.
   Many persistence tables store JSON blobs such as `payload_json`, `snapshot_json`, `external_json`, `fundamentals_json`, and `price_history_json`.
   Use SQLite JSON functions when available. If JSON functions are unavailable, return the raw JSON and say that extraction could not be done in-place.

7. Cross-check unexpected results.
   Verify the schema version and table definitions against `src/persistence.rs`.
   Verify behavioral expectations against `docs/HISTORY_TIME_SERIES.md` and the persistence restore tests in `src/main.rs`.

8. Summarize the answer in repository terms.
   Report the database path, schema version, tables queried, filters used, and any caveats about missing or stale data.

## Decision Points

- If the user asks for the latest known state, query `symbol_latest` first.
- If the user asks when a value changed, query `symbol_revision` ordered by `evaluated_at` and `revision_id`.
- If the user asks what Yahoo or external data was actually fetched, query `raw_capture`.
- If the user asks why startup restored a certain state, inspect `tracked_symbol`, `watchlist`, `issue_state`, `meta`, and `symbol_latest` together.
- If the requested data is absent, state whether the likely cause is an empty database, a different `--state-db` path, or a session that never persisted that data.

## Quality Bar

- Stay read-only by default.
- State exactly which database file was queried.
- Report the schema version when persistence format matters.
- Use the narrowest table that answers the question.
- Distinguish current state from historical state.
- Call out when JSON extraction depends on SQLite JSON support.

## References

- Query examples: [sqlite-queries.md](./references/sqlite-queries.md)
- Repository docs: `docs/HISTORY_TIME_SERIES.md`
- Schema and migrations: `src/persistence.rs`
- Warm-start and restore tests: `src/main.rs`