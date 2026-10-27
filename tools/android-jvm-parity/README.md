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
