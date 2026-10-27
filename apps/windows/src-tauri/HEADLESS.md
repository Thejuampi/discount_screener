# Headless V3 engine (`vantage-headless`)

Server/daemon + thin CLI for the Opportunities ranking (Long V3, Long V2, Short),
built from this crate **without** Tauri/gtk/webkit:

```bash
cd apps/windows/src-tauri
cargo build --release --no-default-features --bin vantage-headless
B=target/release/vantage-headless

# daemon (market context OFF by default; add --market-context to enable it)
$B serve --profile sp500 --addr 127.0.0.1:7979 --data-dir ~/.local/state/vantage-headless

# client
$B status
$B rank --model v3 --top 20          # table: score, setup, price, target, gap, F·T·C·R, sector
$B rank --model v2 --top 20 --json
$B rank --model short --top 20
$B parity --model v3                 # desktop command path vs headless path, same instant
$B persist                           # force-write the snapshot now (also every 60 s)
$B rank-snapshot ~/.local/state/vantage-headless/opportunity_snapshot.json --top 20  # offline replay
```

Profiles: `sp500`, `dow`, `russell`, `merval`, `nikkei`, `europe`, `asia`, `qa` (+ aliases).

## How it reuses the app

* `gui` cargo feature (default) carries Tauri and its plugins. `--no-default-features`
  drops them; `src/tauri_compat.rs` swaps `tauri::State` / `async_runtime` for
  std stand-ins so `commands.rs`, `regime`, `risk` compile unchanged
  (`#[tauri::command]` became `#[cfg_attr(feature = "gui", tauri::command)]`).
* `src/headless.rs` calls the app's own feed (`apply_universe_profile`) and the
  `get_opportunities` scoring body (`capture_opportunity_projection` +
  `project_opportunity_rows`); the list order mirrors `OpportunityList.tsx`
  (composite desc, stable).
* State: `history.sqlite` + `feed.log` (same as the app) and
  `opportunity_snapshot.json` (frozen scoring input, atomic write). After a
  restart the daemon serves the persisted snapshot until the live feed fills.
* HTTP on loopback, std only: `/status`, `/ranking?model=&top=`, `/parity?model=`, `/persist`.
