# Score history (headless)

Where: inside the app database, `<data-dir>/history.sqlite` (the same file the desktop app uses), in the
additive tables `score_runs` and `score_history` (plus the `score_history_migrations` log), and from
migration v3 the V3.1 shadow table `score_shadow` (see `shared/contracts/score-history-v1.md`).
Headless default data-dir: `$XDG_STATE_HOME/vantage-headless` (or `~/.local/state/vantage-headless`);
production on the box: `/workspace/vh-data/history.sqlite`.

## When a run is written (`serve`)
- Every `--history-interval-hours H` (default 6; `0` disables history and its backups). The interval is
  measured only from the last run stored in `score_runs`, so restarts do not cause an early write.
- Once after each US weekday close: ~16:10 America/New_York (DST-aware via chrono-tz). If the feed's initial
  pass is still running at 16:10, the snapshot is taken as soon as it completes (up to 18 h later). An interval
  run landing after 16:10 satisfies the slot (no duplicate); an interval run due within 30 min before 16:10 is
  skipped in favour of the close snapshot. **Exchange holidays are not modelled** (a holiday gets one extra,
  harmless snapshot).
- Gates: the initial pass has completed; Year charts cover ≥95% of the universe or 20 min have passed; the
  ranking is live (not the persisted snapshot) and non-empty.
- Clock skew: if the clock is behind the last stored run, a run is due (writes are never blocked); the next
  interval counts from that write (last run = latest inserted).
- The model/rule version stamped on each run is `SCORE_RULE_VERSION` in
  `apps/windows/src-tauri/src/score_history.rs`.

## Protections
- **Append-only, kept forever.** No retention/pruning/TTL code. On `score_runs`, `score_history`,
  `score_history_migrations` and (migration v3) `score_shadow`: `BEFORE DELETE` / `BEFORE UPDATE` triggers `RAISE(ABORT)`, and (migration v2)
  `BEFORE INSERT` triggers abort when the key already exists, so `INSERT OR REPLACE`, `REPLACE INTO` and UPSERT
  (`ON CONFLICT … DO UPDATE`) cannot overwrite a row. Writes are plain `INSERT`; a repeated run id is detected
  first and reported as a no-op (`Duplicate`).
- **Startup check:** after migrating, the store verifies that all 12 triggers (9 from #67 plus 3 on `score_shadow`) (`REQUIRED_TRIGGERS`) exist in
  `sqlite_master` **and** that each one's normalized SQL equals the migration DDL (a same-name no-op trigger is
  caught). It then probes behaviour: inside a `SAVEPOINT` (a sentinel row is inserted if a table is empty) it tries
  DELETE, UPDATE and a duplicate INSERT on each table (including `score_shadow`), each in its own nested savepoint, expects the append-only
  `ABORT`, and always `ROLLBACK TO` + `RELEASE` — the probe never changes data. Any failure refuses every write
  (reads still work) and the error names the trigger(s). The probe runs inside `BEGIN IMMEDIATE`; if another
  writer holds the DB (SQLITE_BUSY/LOCKED) the result is *inconclusive*, not a failure: it retries with backoff,
  logs a loud warning and is re-run before the next write (one attempt, total budget 10 s; if still locked that write is refused and nothing is written, and the next write tries again), so a
  busy DB at open never disables history for the session. Future migrations must not edit the text of existing
  triggers (the SQL comparison would flag them); a changed trigger needs a new name.
- **The triggers are the real defence.** The CI text guard below is best-effort only (a source scan can be
  fooled by dynamically built SQL); it exists to catch mistakes early, not to enforce the guarantee.
- **CI guard** (`monorepo-validation` → *Score history* step: `cargo test --no-default-features --lib score_history`,
  `--lib history_tick`, `--bin vantage-headless`): a test scans `src/` — lower-cased, quotes removed, `main.`/schema
  prefixes stripped — and fails on DELETE/UPDATE (incl. `UPDATE OR …`), DROP TABLE, DROP TRIGGER of these tables'
  triggers, ALTER TABLE, `INSERT OR …`, `REPLACE INTO`, `ON CONFLICT`/`DO UPDATE` on these tables, and any
  `format!` SQL literal that names them.
- No app cleanup path touches them: the only time-based deletes in the app DB are provider caches/budgets
  (FMP/TipRanks); the desktop app has no "clear cache"/reset feature; headless has no reset command.
- **Migrations are additive only**; each migration and its log row run in one `BEGIN IMMEDIATE`. Before any
  pending migration the whole file is copied (`VACUUM INTO`, gzip, `integrity_check`) to the backup dir as
  `history.pre-score-history-v{from}-to-v{to}-{UTC}.sqlite.gz`.
- **Daily backups** (score tables only): once per UTC day `serve` exports `score_runs`, `score_history`,
  `score_shadow` and `score_history_migrations` — nothing else from the app DB — into a fresh SQLite file, gzips it to
  `score-history-YYYY-MM-DD.sqlite.gz` and verifies it (`integrity_check`; row counts ≥ the live counts taken
  just before). A failing file is renamed `*.failed`. Rotation keeps the newest 7 daily files, the newest file of each
  of the last 12 months and the newest file of **every year, forever**, and only ever removes files named exactly
  `score-history-YYYY-MM-DD.sqlite.gz`; pre-migration, pre-restore and manual (`history.manual-*`) copies keep the
  newest 10 of each kind. The live DB is never a candidate. Every backup holds the full history, so rotating a
  backup never loses a row that the live DB or a newer backup still has.
- **Backup directory**: `--history-backup-dir B`, else `$VANTAGE_HISTORY_BACKUP_DIR`, else
  `$XDG_DATA_HOME/vantage/backups` (`~/.local/share/vantage/backups`) — outside the data-dir by default.
- **Lock**: `serve` (and `history-record-snapshot`) hold an exclusive lock on `<data-dir>/vantage-headless.lock`
  for their lifetime; a second `serve` or a `history-restore` on the same data-dir refuses. (The desktop app does
  not take this lock — close it before restoring.)
- **A manual `rm -rf` of the data-dir is covered only by the off-dir backups.** Restore with `history-restore`.

## Storage (measured on the RC binary)
| | per run (94 ranked rows, sp500 today) | per run (501 rows, worst case) |
|---|---|---|
| app DB growth | ~12 KB | ~67 KB |
| gzip backup | ~3–3.7 KB | ~20 KB |

About 1,700 runs/year (4 interval runs/day + ≤252 close snapshots): **~20 MB/yr in the DB** at 94 rows/run
(~115 MB/yr at 501 rows). Each backup holds the full history: ~6 MB after one year at 94 rows (~33 MB at the
~20 KB/run worst case), ~29 MB / ~166 MB after five years.

Projected backup dir (all retained copies: 7 daily + 11 more month heads + one per past year; the second figure
adds the worst case of all 30 pre-migration/pre-restore/manual slots full at current size):

| | year 1 | year 5 |
|---|---|---|
| ~20 KB/run gz (501 rows, reviewer basis) | ~0.4 GB (≤ 1.4 GB) | ~3.1 GB (≤ 8.1 GB) |
| ~3.5 KB/run gz (94 rows, today) | ~73 MB (≤ 0.25 GB) | ~0.55 GB (≤ 1.4 GB) |

## Box daemon start command
```
VANTAGE_HISTORY_BACKUP_DIR=/cursor/stores/store-676cd913-00cd-4acb-a69b-30555d151409/score-history-backups \
  vantage-headless serve --addr 127.0.0.1:7979 --profile sp500 --data-dir /workspace/vh-data \
  --history-interval-hours 6
```
(equivalently `--history-backup-dir /cursor/stores/store-676cd913-00cd-4acb-a69b-30555d151409/score-history-backups`).
The store path is configuration, not a code default.

## Commands
- `vantage-headless history TICKER [--days N] [--changes] [--profile P] [--tz IANA] [--json]` — dates shown in
  `--tz` (default America/New_York) with the zone label; the API stays UTC. `days` is 1..=36500.
- `vantage-headless history-backup --data-dir DIR [--history-backup-dir B] [--verify]` — verified manual
  backup `history.manual-<UTC>.sqlite.gz` (score tables only); `--verify` also checks every `*.sqlite[.gz]` in B.
- `vantage-headless history-restore FILE --data-dir DIR [--history-backup-dir B]` — **append-only merge**: refuses
  while a `serve` holds the data-dir lock; verifies FILE (`.sqlite` or `.sqlite.gz`, must contain the score
  tables); exports the current score tables to B (`history.pre-restore-<UTC>.sqlite.gz`); then, in one
  `BEGIN EXCLUSIVE`, `ATTACH`es FILE and inserts only the runs/rows whose key is missing. Columns are matched
  **by name, never by position**: FILE's `score_runs`/`score_history` must have exactly the live column set with
  the same types; an unknown or missing column, a type mismatch, or a schema version (max
  `score_history_migrations.version`) newer than this binary's is refused with a clear error before anything is
  written (the only allowed gap is an older version missing exactly columns added by later migrations — none so
  far; v2 only added triggers). Older versions (v1) restore into v2. Existing rows are never
  overwritten or deleted; other app tables are untouched. Ends with `PRAGMA wal_checkpoint(TRUNCATE)`. Running it
  twice adds nothing the second time. (`--force` is no longer needed and is ignored.)
- `vantage-headless history-record-snapshot --data-dir DIR` — offline: records one run from the persisted
  `opportunity_snapshot.json` (trigger `snapshot`).
- `vantage-headless serve --no-feed` — offline: no quote feed, Year-chart or regime workers; serves the persisted
  snapshot and history.
- `GET /history?symbol=…` percent-decodes strictly on bytes; malformed escapes or non-UTF-8 → `400`.

## V3.1 shadow (`score_shadow`, migration v3)
- Migration v3 adds `score_shadow` (one row per run × ticker × shadow config) with the same protections as the
  other score tables: plain `INSERT`, `BEFORE INSERT` no-overwrite, `BEFORE DELETE`/`UPDATE` abort, part of the
  12-trigger startup check, the CI guard and the gz backups (verified row count ≥ live). A v2 file (#67) is
  backed up (`history.pre-score-history-v2-to-v3-…`) before migrating.
- `history-restore` merges `score_shadow` by column name with the same strict checks (unknown/missing column or
  type mismatch refuses), inserting only missing `(run_id, ticker, config_id)` keys; a pre-v3 backup without the
  table is accepted, a v3 backup without it is refused.
- `vantage-headless history-shadow-report --data-dir DIR [--profile P] [--min-n N] [--json]`: demoted vs kept
  forward returns at 5/20/60 recorded trading days.
## Full-universe evaluations (migration v4)

Every recorded run also stores one row per **universe** symbol in `score_evaluations`
(`status` / `reason`: `ok`/`qualified`; `excluded`/`unprofitable`, `gap_below_min:<bps>`,
`no_intrinsic_value`; `failed`/`not_loaded`, `invalid_price`, `score_error:<msg>`).
Excluded rows carry diagnostic f/t/fc and composite scores; `decision` is NULL unless `ok`.
The official ranking (`rank_android_v3`) is exactly the `ok` evaluations in main's order.

The raw snapshot rows are content-addressed in `score_inputs` (sha256 of the exact serde
bytes, gzip, deduplicated across runs); `score_run_inputs` lists each run's rows in order and
`score_run_stats` holds the counts plus the snapshot envelope, so
`ScoreHistoryStore::reconstruct_snapshot(run_id)` rebuilds the snapshot byte for byte (hashes
re-checked). All four tables are append-only (triggers, self-check, export, restore by name;
older backups without them restore fine). Everything is written in the run's one transaction.

`GET /history?all=1[&run=<id>][&profile=]` returns the latest (or given) run's evaluations
and stats; `GET /history?all=1&symbol=X` one symbol's evaluations across runs.
`/status.ranked_rows` is the real ranked count; `snapshot_rows` the loaded rows.
