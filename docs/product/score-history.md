# Score history (headless)

Where: inside the app database, `<data-dir>/history.sqlite` (the same file the desktop app uses), in the
additive tables `score_runs` and `score_history` (plus the `score_history_migrations` log).
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
- **Append-only, kept forever.** No retention/pruning/TTL code. `BEFORE DELETE` / `BEFORE UPDATE` triggers
  `RAISE(ABORT)` on `score_runs`, `score_history` and `score_history_migrations`. A unit test scans `src/` and
  fails on any DELETE/UPDATE/DROP/REPLACE/non-additive ALTER aimed at those tables.
- No app cleanup path touches them: the only time-based deletes in the app DB are provider caches/budgets
  (FMP/TipRanks); the desktop app has no "clear cache"/reset feature; headless has no reset command.
- **Migrations are additive only** and, before any pending migration, the whole file is copied with
  `VACUUM INTO` (verified) to the backup dir as `history.pre-score-history-v{from}-to-v{to}-{UTC}.sqlite`.
- **Daily backups**: once per UTC day `serve` writes `score-history-YYYY-MM-DD.sqlite` (`VACUUM INTO`, then
  `PRAGMA integrity_check` + row counts must match the live tables) to `--history-backup-dir`
  (default `$VANTAGE_HISTORY_BACKUP_DIR`, else
  `/cursor/stores/store-676cd913-00cd-4acb-a69b-30555d151409/score-history-backups`). Rotation keeps 30 daily +
  12 monthly and only ever removes files named exactly `score-history-YYYY-MM-DD.sqlite` in that directory —
  never the live DB, manual, pre-migration or pre-restore copies.
- **A manual `rm -rf` of the data-dir is covered only by these off-dir backups.** Restore with
  `history-restore`.

## Commands
- `vantage-headless history TICKER [--days N] [--changes] [--profile P] [--tz IANA] [--json]` — dates shown in
  `--tz` (default America/New_York) with the zone label; the API stays UTC.
- `vantage-headless history-backup --data-dir DIR [--history-backup-dir B] [--verify]` — verified manual
  backup `history.manual-<UTC>.sqlite`; `--verify` also checks every `*.sqlite` in B.
- `vantage-headless history-restore FILE --data-dir DIR [--history-backup-dir B] [--force]` — verifies FILE,
  refuses to overwrite an existing `history.sqlite` without `--force`, backs the current file up to B first
  (`history.pre-restore-<UTC>.sqlite`), then swaps it in. Stop the daemon / desktop app first.
- `vantage-headless history-record-snapshot --data-dir DIR` — offline: records one run from the persisted
  `opportunity_snapshot.json` (trigger `snapshot`).
- `vantage-headless serve --no-feed` — offline: no quote feed, Year-chart or regime workers; serves the persisted
  snapshot and history.
