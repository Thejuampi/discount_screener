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
- **Startup check:** after migrating, the store verifies that all 12 triggers (`REQUIRED_TRIGGERS`) exist in
  `sqlite_master`; if any is missing it refuses every write (reads still work) and the error names them.
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
  just before). A failing file is renamed `*.failed`. Rotation keeps 30 daily + 12 monthly and only ever removes
  files named exactly `score-history-YYYY-MM-DD.sqlite.gz`; pre-migration and pre-restore copies keep the newest
  10 of each kind. The live DB is never a candidate. Manual backups are never rotated.
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
(~115 MB/yr at 501 rows). Each daily backup holds the full history: ~5–6 MB after one year (34 MB worst case);
the rotation keeps at most 42 such files.

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
  `BEGIN EXCLUSIVE`, `ATTACH`es FILE and inserts only the runs/rows whose key is missing. Existing rows are never
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
- `history-restore` merges only shadow rows whose key is missing (older backups without the table are fine).
- `vantage-headless history-shadow-report --data-dir DIR [--profile P] [--min-n N] [--json]`: demoted vs kept
  forward returns at 5/20/60 recorded trading days.
