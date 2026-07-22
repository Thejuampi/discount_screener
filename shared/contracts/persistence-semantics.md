# Persistence Semantics

This contract records behavior that both apps should preserve even though they use different on-disk formats.

- Tracked symbols preserve operator-selected order.
- Watchlist membership is a set at the behavior layer and must round-trip without duplicates.
- Persisted symbol state keeps the latest snapshot, external signal, fundamentals, update counters, and bounded recent price history.
- Symbol revisions are appended chronologically per symbol.
- Chart cache entries are scoped by symbol plus chart range.
- Restoring persisted state must not silently promote an unwatched symbol into the watchlist.
- Android Discovery (when present) persists **membership** (`discovery_symbol`), **scores** (`discovery_score`), **jobs** (`discovery_job`), and config keys under `meta` (`discovery.min_score`, `discovery.scoring_model`, `discovery.universe_name`). Membership recreate merges against a seed file; score refresh updates scores only. Discovery is not a profile tracked book and must not auto-run on startup. Do not treat Discovery tables as a volatile “cache”: they are durable operator state. Prefer slim score rows (no raw scrape payloads).

The Rust desktop app persists this through SQLite plus journal/state files. The Android app persists through SQLite (`SQLiteStateStore`) with JSON columns for evaluated payloads. The shared contract is the behavior above, not a byte-identical storage schema.
