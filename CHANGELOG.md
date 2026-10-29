# Changelog

## Unreleased
### Added
- Score history (headless, #67): append-only `score_runs` / `score_history` tables in `<data-dir>/history.sqlite`,
  written every `--history-interval-hours` (default 6) and after each US weekday close (~16:10 America/New_York).
  `GET /history` (UTC, ISO-8601, `profile=`) and `vantage-headless history` (local time, `--tz`, default
  America/New_York). Rule version from `SCORE_RULE_VERSION` (`score_history.rs`, `"V3"`).
- Protections: DELETE/UPDATE abort triggers, no pruning, backup before migrations, verified daily backups rotated
  30 daily + 12 monthly to `--history-backup-dir`; `history-backup [--verify]`, `history-restore FILE [--force]`.
- Offline mode `serve --no-feed` and `history-record-snapshot`.
- Contract `shared/contracts/score-history-v1.md`; docs `docs/product/score-history.md`.
