# Changelog

## Unreleased

### V3.1 decision rule — SHADOW MODE (Android core + headless)

- Official Act/Watch/Avoid, ranking and `rule_version` stay **V3** (byte-identical to main, shadow fields excluded).
- Shadow grid `V31_SHADOW_CONFIGS` (bound {0.005, 0.02, 0.05} × λ_M {0.15, 0.3}, λ_T 1.5, stable `config_id`s):
  `/ranking` and `rank-snapshot --json` rows carry an additive, optional `shadow` array (`rule_version, config_id, bound, lambda_t, lambda_m, m, warmup, s_adj, shadow_decision`, headless also `hist_source`); m uses the unrounded histogram, falling back to the rounded cents when no raw value exists (recorded as `hist_source` raw/rounded), null during the < 35-bar warm-up.
- Headless records it in an additive, append-only `score_shadow` table (history.sqlite migration v3, after #67's v2;
  DELETE/UPDATE/no-overwrite triggers in the startup check, CI guard, gz score-table backups, restore merges missing rows, pre-migration backup, backup row-count verification), in the same transaction as the official run.
  Android computes shadow values for parity only (no store).
- `vantage-headless history-shadow-report`: demoted vs kept forward returns at 5/20/60 recorded trading days.
- Parity diff checks the shadow constants and config list on both sides and every config per row (tol 1e-9).
  See `docs/architecture/decision-rule-v3-1.md`.

### Added
- Score history (headless, #67): append-only `score_runs` / `score_history` tables in `<data-dir>/history.sqlite`,
  written every `--history-interval-hours` (default 6) and after each US weekday close (~16:10 America/New_York).
  `GET /history` (UTC, ISO-8601, `profile=`) and `vantage-headless history` (local time, `--tz`, default
  America/New_York). Rule version from `SCORE_RULE_VERSION` (`score_history.rs`, `"V3"`).
- Protections: DELETE/UPDATE abort triggers plus BEFORE INSERT triggers that reject an existing key (no
  REPLACE/UPSERT overwrite), plain INSERTs, startup trigger check by SQL and by behaviour (refuses writes if one is missing or altered), CI guard step,
  no pruning, gzip backup before migrations, verified daily gzip backups of the score tables only, rotated
  7 daily + 12 monthly + one per year forever (pre-* and manual copies: newest 10) to `--history-backup-dir` (default
  `$XDG_DATA_HOME/vantage/backups`); `history-backup [--verify]`; `history-restore FILE` merges append-only by column name (refuses unknown/missing columns, type
  mismatches and newer schema versions) and
  refuses while `serve` holds `<data-dir>/vantage-headless.lock`.
- Offline mode `serve --no-feed` and `history-record-snapshot`.
- Contract `shared/contracts/score-history-v1.md`; docs `docs/product/score-history.md`.
