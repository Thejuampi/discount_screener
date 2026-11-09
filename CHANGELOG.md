# Changelog

## Unreleased

### Score history quote-freshness gate (migrations v5 + v6)
- Runs are deferred after a suspend / clock jump until a full quote pass completes after it, when the last pass is
  older than `--quote-stale-secs` (default 1800), or when > 10% of rows are stale; this also gates the 18 h close catch-up.
- Additive `quote_ts`, `quote_age_secs`, `stale` columns; stale rows are stored but excluded from `/history`,
  decision changes and the shadow report. `/status` adds `oldest_quote_age_secs`, `stale_symbols`.
- A priced row with no quote time counts as stale (fails closed). A full pass needs fresh quotes for at least 90% of
  the universe.
- Flags: `--clock-jump-secs` (300), `--max-stale-share` (0.10), `--defer-warn-hours` (6). `/status` adds
  `deferred_since_epoch` and `deferred_reason`.
- `/history?all=1` returns a boolean `stale` flag on every evaluation.
- Append-only `run_annotations`, written only by the `history-annotate` CLI (no daemon write route).
- `POST /persist` refuses requests with an `Origin` header and requires `X-Vantage-Cli: 1`.
- A quote time in the future counts as stale (no age). Chart-close prefers `meta.regularMarketTime`, then a
  non-future candle time; wall only when both are missing (absent/`<=0`). A present timestamp beyond the 120 s
  skew is left unknown (not wall) so it stays stale and out of the 90% full-pass count. After hours,
  `regularMarketTime` ≈ 16:00 ET, so a post-close pass fails safe. `--defer-warn-hours` warns once per
  deferral episode. QuoteSummary still has no trade time.
- A not-due tick clears the deferral shown in `/status`.
- **Migration v6:** append-only `run_deferrals` (+ 3 triggers, and a `score_runs (profile, model, captured_at)`
  index) records each scheduled slot that does not record. It writes one row per `(profile, slot)`, with a reason
  code, `stale_count` and `total`. It is covered by backups and restore. Read it with `GET /history/deferrals`
  (`since`, `before` paging, `limit` 100 by default) or `history-deferrals`. Each row's `next_run` is the first v3 run
  of the profile at or after the slot.
  - v5 is unchanged from its 3c2e275 release. v4 and older stores go straight to v6.
  - A v5 store that already has `run_deferrals` (from a pre-release build) is refused untouched.
- `record_failed`: a due slot whose run fails with an error leaves a best-effort row. If that write fails too, only
  the error is reported (rate-limited in `serve`).
- `run_deferrals` also covers downtime. At `serve` startup, every scheduled slot (interval or close) missed
  while the process was down gets a `process_down` row. Slots older than `PROCESS_DOWN_DETAIL_DAYS` (7) collapse into
  one summary row (`covers`, `covers_from`). A fresh store, a backwards clock and a restart write nothing.
- Run annotations are machine-readable. `annotations` is always present (an array of reasons) on `/history` points
  and in the `/history?all=1` export, on the run and on each evaluation. Annotated runs are not filtered.

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
