# Persistence Semantics

This contract records behavior that both apps should preserve even though they use different on-disk formats.

- Tracked symbols preserve operator-selected order.
- Watchlist membership is a set at the behavior layer and must round-trip without duplicates.
- Persisted symbol state keeps the latest snapshot, external signal, fundamentals, update counters, and bounded recent price history.
- Symbol revisions are appended chronologically per symbol.
- Chart cache entries are scoped by symbol plus chart range.
- Restoring persisted state must not silently promote an unwatched symbol into the watchlist.

The Rust desktop app persists this through SQLite plus journal/state files. The Android app persists through JSON state. The shared contract is the behavior above, not a byte-identical storage schema.
