# Profile load worker result

## Scope

This worker fixes review findings R1 and R7 for Android profile loads.

The worker preserves the shared Yahoo client, request governor, generation checks, and store owner.

The calendar changes belong to the earlier calendar worker and remain inherited work.

## Changes

- A profile refresh now finishes before its market reader starts.
- The final refresh stage still starts one market read for the active generation.
- A profile switch cancels the prior market job before it starts the new job.
- A stale generation cannot publish a market regime or daily summaries.
- Each market refresh captures one epoch and uses it for every daily summary.
- The market reader stages each forty-symbol candle chunk in a binary spool.
- The heap keeps views and one fetch chunk while the full candle set stays on disk.
- Only a usable regime drains the spool into `DailyCandleSink`.
- Complete spool validation precedes each per-chunk sink write.
- Usable regime publication waits for all sink calls.
- A later sink failure can leave earlier chunks committed, as before.
- Success, cancellation, failure, and unusable readings remove active spool files.
- Repository construction removes inactive `market-session-*` directories from its configured directory.
- Production uses the app cache directory for market candle staging.

## R1 evidence

The initial production-shaped test failed because market work started before quote refresh release.

The fixed test passes for `dow` and `sp500` profile switches.

The `sp500` scheduling case uses the real profile catalog.

A separate 501-symbol regression exercises the real `MarketDataRepository` path.

## R7 evidence

The first staging test failed because the constructor accepted no staging directory.

The next test failed because the old chunk list retained no observable bounded spool.

The review follow-up red run found a stuck refresh gate after spool open failure.

It also found silent acceptance of truncated candle data and active session deletion risk.

The spool implementation passes blocked-chunk, cancellation, failure, unusable, orphan, and multi-chunk cases.

The 501-symbol case stores every symbol and produces at least thirteen sink calls.

The spool bounds heap retention to one forty-symbol chunk, but disk usage can reach the full universe.

The reader validates every staged chunk before it writes the first candle chunk.

If the process dies before repository construction, an orphan can remain until the next construction.

Repository construction removes inactive session directories with the `market-session-` prefix.

An active session stays in a process-wide registry until its reader closes.

The spool has a forty-symbol chunk limit and a ten-thousand-candle per-symbol read limit.

## R8 status and minimal plan

R8 remains pending because this worker did not claim a sustained-rate policy change.

The current reactive governor is the neutral baseline.

The next spike should record endpoint calls, rows per second, refusals, hold time, and disk-write time.

The test matrix should model a replenishing quota without `Retry-After`.

It should compare cold, warm, expired-cache, forced-refresh, restart, and profile-switch paths.

It should compare S&P 500 and Russell with the same payloads, quota, and cache state.

Only measured evidence should select profile workload policy or recovery probes.

The policy must retain provider-wide holds and avoid guessed sleeps or global concurrency cuts.

## R9 status and minimal plan

R9 remains pending because typed load phases and UI status sit outside this worker ownership.

The next spec should define completed, pending, unavailable, and retry states.

It should define provider wait reasons and the next retry time.

The implementation should publish typed state from the repository through the ViewModel.

The UI should show that state beside the Positions list.

## Proposed document reconciliation

The spec should state that profile work follows this order.

1. Load cached rows for the selected profile.
2. Finish the profile quote refresh.
3. Start one market read for the active generation.
4. Publish only usable market evidence.
5. Persist daily candles only after usability passes.

The spec should state that candle staging bounds heap retention to one fetch chunk.

It should state that only usable readings trigger sink writes.

It should state that full spool validation finishes before sink writes start.

It should state that a later sink failure can leave earlier chunks committed.

It should state that restart cleanup removes inactive session directories when the repository constructs.

It should state that disk staging can retain a full-universe payload until the reading finishes.

The review record should link this result and the green test evidence.

## Gherkin cases

### Profile market scheduling

```gherkin
Scenario Outline: A profile starts market work after quote refresh
  Given the selected <profile> profile has its symbols
  And its quote refresh waits before completion
  When the profile load starts
  Then the market reader has made <early calls> calls before quote release
  When the quote refresh completes
  Then the market reader makes <final calls> call for the active generation

Examples:
  | Case   | profile | early calls | final calls |
  | R1-QA  | dow     | 0           | 1           |
  | R1-SP  | sp500   | 0           | 1           |
```

Test mapping: `ProfileLoadSchedulingTest` covers both Cases.

### Candle staging and cleanup

```gherkin
Scenario Outline: A market read controls candle staging
  Given a market read has <read condition>
  When the reader processes <universe size> symbols
  Then the sink receives <sink result>
  And the stage directory is <stage result>

Examples:
  | Case      | read condition             | universe size | sink result          | stage result |
  | R7-BLOCK  | second chunk blocks        | 80            | no early write       | staged        |
  | R7-CANCEL | refresh is cancelled       | 80            | no partial write     | empty        |
  | R7-FAIL   | sentiment fetch fails      | 90            | no write             | empty        |
  | R7-SP500  | reading is usable          | 501           | all symbols written  | empty        |
  | R7-TRUNC  | candle data truncates      | 90            | no write             | empty        |
  | R7-HEADER | chunk header truncates     | 90            | no write             | empty        |
  | R7-OPEN   | spool directory open fails | 90            | no write             | empty        |
```

Test mapping:

- R7-BLOCK: `a_blocked_second_chunk_leaves_the_first_chunk_in_the_bounded_spool`.
- R7-CANCEL: `a_cancelled_refresh_removes_partial_candle_spool`.
- R7-FAIL: `a_failed_market_bundle_removes_staged_bars_without_publishing_them`.
- R7-SP500: `a_sp500_sized_universe_persists_all_symbols_through_the_market_reader`.
- R7-TRUNC: `a_truncated_candle_spool_fails_the_read_without_partial_publication`.
- R7-HEADER: `a_partial_candle_spool_header_fails_the_read_without_partial_publication`.
- R7-OPEN: `a_spool_open_failure_does_not_stick_the_refresh_gate`.

### Restart cleanup

```gherkin
Scenario Outline: Market repositories isolate and clean candle sessions
  Given the staging directory has <session state>
  When <action> occurs
  Then the session result is <result>

Examples:
  | Case       | session state        | action                      | result                 |
  | R7-RESTART | inactive orphan     | a new repository constructs | orphan session removed |
  | R7-ACTIVE  | active first reader | a second repository starts  | active session kept   |
```

Test mapping: R7-RESTART uses `a_new_repository_removes_orphaned_candle_spools`.

Test mapping: R7-ACTIVE uses `a_second_repository_does_not_delete_an_active_repository_candle_spool`.

## Test evidence

The profile test red run proved premature market work before quote release.

The profile green run passed two cases with zero failures.

The focused spool red run failed three cases before the follow-up parser case.

The follow-up parser red run failed the partial-header case.

The focused spool green run passed four recovery and isolation cases.

The market green run passed 30 cases with zero failures.

The SP500 coverage ran within `.\gradlew.bat :app:testDebugUnitTest --tests '*MarketDataRepositoryTest' --rerun`.

The SP500 case stored all 501 symbols and made at least thirteen sink calls.

This offline case provides coverage evidence without timing or live quota evidence.

The production repository green run passed the full `DefaultDashboardRepositoryTest` suite with zero failures.

All Gradle runs used the `Local\\DiscountScreenerLunaGradle` mutex.

No live providers, devices, or profile loads ran.

## Handoff

Profile files are ready for the book worker's full core, app, and assemble validation.

The parent should relay this release to `/root/luna_book_import`.
