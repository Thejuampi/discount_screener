# History And Time-Series Manual

## Purpose

This document explains how to use the app's canonical history engine.

There are two ways to query time series:

- inside the terminal UI for one selected symbol
- directly from the sqlite database for ad hoc analysis

The canonical history model is revision-based:

- `raw_capture` stores source payloads exactly as they were fetched
- `symbol_revision` stores immutable evaluated snapshots over time
- `symbol_latest` stores the newest evaluated snapshot per symbol for fast startup

If you want the app's own "what did we know at time T?" timeline, query `symbol_revision`.

## In-App History View

Open ticker detail the normal way, then press `h` to switch from `Snapshot` to `History`.

`History` now opens in a graph-first view. A table subview is still available for dense numeric inspection.

The history header shows:

- selected symbol
- active metric group
- active time window
- visible row position

### History Controls

- `h`: toggle between `Snapshot` and `History`
- `g`: toggle `Graphs` / `Table` inside `History`
- `1`: `Core`
- `2`: `Fundamentals`
- `3`: `Relative`
- `4`: `DCF`
- `5`: `Chart`
- `[` / `]`: move backward or forward through time windows
- `j` / `Down`: scroll down
- `k` / `Up`: scroll up
- `n`: next symbol in the filtered set
- `p`: previous symbol in the filtered set
- `e`: export the selected symbol's full history as Excel-friendly CSV files
- `Esc`: close detail
- `Backspace`: close detail
- `Enter`: close detail
- `d`: close detail

### Time Windows

- `1D`
- `1W`
- `1M`
- `3M`
- `1Y`
- `All`

The default history window is `1M`.

### Metric Groups

`Core` includes the currently evaluated top-level fields such as:

- market price
- intrinsic value
- internal gap
- qualification
- confidence
- external gap fields when available
- weighted target gap when available

`Fundamentals` includes raw mirrored fields from the stored fundamental snapshot, such as:

- market cap
- shares outstanding
- trailing and forward P/E
- price/book
- ROE
- EBITDA
- enterprise value
- EV/EBITDA
- debt and cash
- debt/equity
- free cash flow
- operating cash flow
- beta
- EPS
- earnings growth

`Relative` includes evaluated relative-strength metrics, including:

- per-metric percentile rows
- per-metric band values
- composite percentile output

`DCF` includes evaluated DCF outputs, including:

- bear intrinsic value
- base intrinsic value
- bull intrinsic value
- WACC
- base growth
- net debt
- margin of safety

`Chart` includes technical summaries for each supported range:

- `D`
- `W`
- `M`
- `1Y`
- `5Y`
- `10Y`

Per range, the history tab can show:

- latest close
- EMA20
- EMA50
- EMA200
- MACD
- signal
- histogram

### Graph View

The default `Graphs` subview uses small multiples.

- `Core`, `Fundamentals`, `Relative`, and `DCF` show one tile per metric
- `Chart` shows one tile per range: `D`, `W`, `M`, `1Y`, `5Y`, `10Y`
- wide terminals use a 2-column grid
- narrower terminals fall back to a 1-column stack

Each graph tile shows:

- metric or range label
- latest value
- previous value
- delta
- terminal line chart
- min/max labels
- compact footer context

### Table View

Press `g` to switch to `Table`.

The table subview keeps the older history-row style:

- metric name
- latest value
- previous value
- delta
- sparkline

### How To Read A History Row

Each row shows:

- metric name
- latest value in the selected window
- previous value
- delta
- sparkline

If no rows are available for the current group and window, the app shows:

```text
No history rows are available for the selected group and window yet.
```

That usually means one of these:

- the symbol has not produced enough revisions yet
- that metric group is still unavailable for the symbol
- the selected time window is too narrow

In that case, switch to `All` or wait for more revisions to accumulate.

## CSV Export

Press `e` from `History` to export the selected symbol's full canonical revision history.

Export is non-interactive and always writes all revisions for the symbol, regardless of the currently selected history window.

### Export Location

The app writes a timestamped export directory:

- if `state_db` is configured: `<state_db_parent>/exports/<symbol>/<YYYYMMDD_HHMMSSZ>/`
- otherwise: `<cwd>/exports/<symbol>/<YYYYMMDD_HHMMSSZ>/`

### Export Files

Each export directory contains:

- `export_metadata.csv`
- `core_wide.csv`
- `fundamentals_wide.csv`
- `relative_wide.csv`
- `dcf_wide.csv`
- `chart_wide.csv`
- `all_tidy.csv`

### Export Shapes

Wide CSV files are designed for immediate Excel charting:

- one row per revision
- one timestamp column
- one metric per column
- numeric values already converted into chart-friendly human units

`all_tidy.csv` is designed for filtering, pivots, and custom workbook work:

- one row per metric point
- explicit group, metric, range, and unit columns

### Export Units

- money columns use dollars and end with `_usd`
- percentage columns use percent points and end with `_pct`
- count columns end with `_count`
- ratio columns end with `_ratio`

Missing values are left blank in wide CSVs and omitted from `all_tidy.csv`.

## SQLite Time-Series Queries

The sqlite database is the canonical persistence store.

### Main Tables

- `raw_capture`
- `raw_latest`
- `symbol_revision`
- `symbol_latest`
- `tracked_symbol`
- `watchlist`
- `issue_state`

### What To Query

Use `symbol_revision` when you want:

- a historical timeline of evaluated values
- stable symbol-level progression over time
- the same view the history UI is built from

Use `raw_capture` when you want:

- source-level audit data
- raw quote/external/fundamental/chart payload history
- debugging or future re-derivation work

Use `symbol_latest` when you want:

- only the current known evaluated state
- fast startup-style reads

## Ready-To-Run SQL

Examples below assume you are already connected to the sqlite database.

### Full Evaluated History For One Symbol

```sql
SELECT
  revision_id,
  symbol,
  evaluated_at,
  last_sequence,
  update_count,
  payload_json
FROM symbol_revision
WHERE symbol = 'NVDA'
ORDER BY evaluated_at ASC, revision_id ASC;
```

### Latest Evaluated State For One Symbol

```sql
SELECT
  symbol,
  revision_id,
  evaluated_at,
  payload_json
FROM symbol_latest
WHERE symbol = 'NVDA';
```

### Raw Source History For One Symbol

```sql
SELECT
  id,
  symbol,
  capture_kind,
  scope_key,
  captured_at,
  payload_json
FROM raw_capture
WHERE symbol = 'NVDA'
ORDER BY captured_at ASC, id ASC;
```

### Market Price Time Series From Canonical Revisions

If your sqlite build supports JSON functions:

```sql
SELECT
  datetime(evaluated_at, 'unixepoch') AS ts,
  json_extract(payload_json, '$.snapshot.market_price_cents') AS price_cents
FROM symbol_revision
WHERE symbol = 'NVDA'
ORDER BY evaluated_at ASC, revision_id ASC;
```

### Intrinsic Value And Gap Time Series

```sql
SELECT
  datetime(evaluated_at, 'unixepoch') AS ts,
  json_extract(payload_json, '$.snapshot.intrinsic_value_cents') AS intrinsic_value_cents,
  json_extract(payload_json, '$.gap_bps') AS gap_bps
FROM symbol_revision
WHERE symbol = 'NVDA'
ORDER BY evaluated_at ASC, revision_id ASC;
```

### DCF Base Case Time Series

```sql
SELECT
  datetime(evaluated_at, 'unixepoch') AS ts,
  json_extract(payload_json, '$.dcf_analysis.base_intrinsic_value_cents') AS base_intrinsic_cents,
  json_extract(payload_json, '$.dcf_analysis.wacc_bps') AS wacc_bps,
  json_extract(payload_json, '$.dcf_margin_of_safety_bps') AS margin_of_safety_bps
FROM symbol_revision
WHERE symbol = 'NVDA'
ORDER BY evaluated_at ASC, revision_id ASC;
```

### Fundamental Time Series

```sql
SELECT
  datetime(evaluated_at, 'unixepoch') AS ts,
  json_extract(payload_json, '$.fundamentals.trailing_pe_hundredths') AS trailing_pe_hundredths,
  json_extract(payload_json, '$.fundamentals.return_on_equity_bps') AS roe_bps,
  json_extract(payload_json, '$.fundamentals.free_cash_flow_dollars') AS free_cash_flow_dollars
FROM symbol_revision
WHERE symbol = 'NVDA'
ORDER BY evaluated_at ASC, revision_id ASC;
```

### Relative Score Time Series

```sql
SELECT
  datetime(evaluated_at, 'unixepoch') AS ts,
  json_extract(payload_json, '$.relative_score.composite_percentile') AS composite_percentile,
  json_extract(payload_json, '$.relative_score.group_label') AS peer_group
FROM symbol_revision
WHERE symbol = 'NVDA'
ORDER BY evaluated_at ASC, revision_id ASC;
```

### All Chart Technical Summaries Stored In Revisions

Chart summaries are stored as an array inside `payload_json`.

```sql
SELECT
  datetime(evaluated_at, 'unixepoch') AS ts,
  json_extract(payload_json, '$.chart_summaries') AS chart_summaries_json
FROM symbol_revision
WHERE symbol = 'NVDA'
ORDER BY evaluated_at ASC, revision_id ASC;
```

If you want one chart range only, you will usually extract the JSON array in your downstream tool rather than in bare sqlite.

## Practical Query Guidance

Use `symbol_revision` for product analysis questions such as:

- how did price, intrinsic value, and gap evolve?
- when did confidence or qualification change?
- when did DCF move materially?
- how did relative percentile shift against peers?

Use `raw_capture` for source analysis questions such as:

- when did the quote payload change?
- when were chart candles refreshed?
- what exactly was fetched before evaluation ran?

## Caveats

- History is append-only at the revision level.
- Some metric groups can be missing on older revisions.
- Chart summaries are stored in revision payloads as summarized technical outputs, not as one-row-per-indicator tables.
- The in-app history view is single-symbol today.
- For broad cross-symbol analytics, direct sqlite queries are the right path for now.

## Recommended Workflow

1. Use the terminal history tab to inspect one symbol quickly.
2. Use `symbol_revision` SQL when you need exact historical values or exports.
3. Use `raw_capture` only when debugging source-level behavior or building new derivation logic.
