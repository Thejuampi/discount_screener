# Changelog

## Unreleased

### V3.1 decision rule (Android core + headless)

- Act is now checked on `S_adj = S · exp(−1.5 · max(0,−T)/100) · exp(−0.3 · max(0,−m))` instead of
  the raw V3 composite S; `m` = clip(MACD histogram / close / 0.005, −1, 1) on the Year chart.
  Avoid logic and ranking (raw S) are unchanged. See `docs/architecture/decision-rule-v3-1.md`.
- Headless JSON rows carry `s_adj` and `m`; `/ranking` reports `rule_version: "V3.1"`.
  Android `ProjectedOpportunityRow` exposes `sAdj` and `macdMomentum`.
- JVM parity diff checks the V3.1 constants on both sides and compares `sAdj`/`m` (tol 1e-9).
- m bound is its own constant (`MOMENTUM_HISTOGRAM_BOUND` / `V31_MOMENTUM_HISTOGRAM_BOUND`, default 0.005, overridable per call), independent of `V3_TECH_HISTOGRAM_BOUND`; calibration + recommended 0.05 in the V3.1 doc.

### Added
- Score history (headless, #67): append-only `score_runs` / `score_history` tables in `<data-dir>/history.sqlite`,
  written every `--history-interval-hours` (default 6) and after each US weekday close (~16:10 America/New_York).
  `GET /history` (UTC, ISO-8601, `profile=`) and `vantage-headless history` (local time, `--tz`, default
  America/New_York). Rule version from `SCORE_RULE_VERSION` (`score_history.rs`, `"V3"`).
- Protections: DELETE/UPDATE abort triggers, no pruning, backup before migrations, verified daily backups rotated
  30 daily + 12 monthly to `--history-backup-dir`; `history-backup [--verify]`, `history-restore FILE [--force]`.
- Offline mode `serve --no-feed` and `history-record-snapshot`.
- Contract `shared/contracts/score-history-v1.md`; docs `docs/product/score-history.md`.
