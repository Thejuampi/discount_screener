# android-jvm-parity

Runs the Android app's live data path on a plain JVM (no Android SDK, no emulator) and
writes what the Opps list shows, so headless/Rust parity can be checked on live data.

What it compiles: `apps/android/core` (engines, `ScreenReplay`) plus the SDK-free app files
`data/remote/*` (YahooFinanceClient, FRED/TNX/MarketsInsider/SEC clients),
`DcfSourceCoordinator.kt` and `AppLogger.kt`, with a stub `android.util.Log`.
`RepositoryDcfHelpers.kt` is generated from `DefaultDashboardRepository.kt` by
`./extract_helpers.sh` (re-run it after that file changes).

Pipeline (mirrors DefaultDashboardRepository on a cold start, minus persistence): quote pass
plus 3 retry rounds, Year chart pass (4 passes), DCF enrichment (4 passes),
`OpportunityEngine.buildRows(AggressiveV3, regime off)`, then the
`ScreenDataProjectionRequest` through `ScreenReplay.project`.

    JAVA_HOME=/path/to/jdk17 apps/android/gradlew -p tools/android-jvm-parity --no-configuration-cache run
    # env: PARITY_OUT (default /tmp/android-jvm-parity), PARITY_LIMIT=N, PARITY_SYMBOLS=A,B

Outputs: `opps.json` (projected Opps list in app order), `request.json` (replayable),
`fixture.json` (raw inputs in the headless-v3 fixture format). To rank the same inputs in Rust:

    cd apps/windows/src-tauri
    FIXTURE_IN=/tmp/android-jvm-parity/fixture.json FIXTURE_OUT=/tmp/rust.json \
      cargo test --no-default-features --lib dump_fixture_ranking -- --ignored

Rust stores the P/E and P/B fields as `u32`, so first clamp any negative
`forwardPeHundredths`/`trailingPeHundredths`/`priceToBookHundredths` to 0. That is what the
Rust Yahoo parser's saturating cast produces live.

Known gap: symbols with no analyst target get no snapshot from `fetchSymbol`. The app then
builds a chart-based fallback snapshot, which this driver skips. Such symbols cannot qualify
for Opps, so the list is unaffected.

## Field-by-field diff against headless

`opps.json` is the `ScreenReplay.project` Opps list (what the screen shows): order, scores,
row and projected confidence/gap/upside, trust signal, decision. `fixture.json` also records
the DCF state each symbol settled on (`dcf`), so the run can be replayed offline:

    # Offline replay (no network): Kotlin buildRows + projection on recorded inputs
    PARITY_REPLAY=shared/contracts/android-jvm-parity-recorded.json PARITY_OUT=/tmp/replay \
      JAVA_HOME=... apps/android/gradlew -p tools/android-jvm-parity --no-configuration-cache run
    # Rank the same inputs in Rust and diff every field (exit 1 on any difference)
    python3 tools/android-jvm-parity/diff_headless.py replay \
      shared/contracts/android-jvm-parity-recorded.json /tmp/replay/opps.json

    # Live: diff against a headless daemon that fetched its own data
    curl -s "127.0.0.1:7981/ranking?model=v3&semantics=android&top=1000" > /tmp/vh.json
    python3 tools/android-jvm-parity/diff_headless.py live /tmp/android-jvm-parity/opps.json /tmp/vh.json

CI (`android-jvm-parity` job) runs the offline replay and diff on the committed recorded set.
Headless flags rows whose on-screen value needs an Android DCF identity value it does not
compute (Street unusable and a live residual/FCFF DCF) with `projection_gap`; the diff
counts those separately instead of failing.

## V3.1

`opps.json` rows carry `sAdj`, `m` and `ruleVersion`. `diff_headless.py` first checks the V3.1
constants (λ_T, λ_M, histogram bound, version) match between `V31ActAdjustment.kt` and
`opportunity_v31.rs`, then compares `sAdj`/`m` with absolute tolerance 1e-9 and decisions exactly.
