---
title: 'Profile switch review loop leftovers'
type: 'bugfix'
created: '2026-08-17'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
baseline_revision: 'ac0c05ff79617907a401426c45517be06d67b0b7'
final_revision: 'NO_COMMIT_DIRTY_TREE_WAIVED'
context:
  - G:\dev\repos\discount_screener\AGENTS.md
  - G:\dev\repos\discount_screener\_bmad-output\project-context.md
warnings: []
deferred:
  - summary: >-
      YahooSession.ensureCrumb still uses blocking Call.execute, so cancel waits on crumb HTTP.
    evidence: |-
      YahooSession.kt bootstrapCookies and fetchCrumb call execute() outside executeCancellable.
    location: >-
      apps/android/app/src/main/kotlin/com/discountscreener/android/data/remote/YahooSession.kt:40
    severity: medium
  - summary: >-
      MarketDataRepository keeps a 150s process-wide cache that can reuse the prior universe.
    evidence: |-
      refreshIfStale is not keyed by profile generation.
    location: >-
      apps/android/app/src/main/kotlin/com/discountscreener/android/data/market/MarketDataRepository.kt
    severity: medium
  - summary: >-
      adoptProfileFromStore writes tracked symbols, watchlist, and issues after the mutex.
    evidence: |-
      Two overlapping selects can finish disk writes out of order.
    location: >-
      apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt:818
    severity: medium
  - summary: >-
      FeatureCaseCoverageTest only checks that a fun name exists, not the Then clause.
    evidence: |-
      Name match can pass when the test never runs the intended race.
    location: >-
      apps/android/app/src/test/kotlin/com/discountscreener/android/domain/model/FeatureCaseCoverageTest.kt
    severity: low
---

<intent-contract>

## Intent

**Problem:** After a profile switch, leftover Yahoo, disk, and UI work still slows Opportunities fill. The first eight patches landed. Review leftover holes remain.

**Approach:** Close the leftover cancel and paint holes on the same Android switch path. Keep persistDelta awaited. Review, patch, review.

## Boundaries & Constraints

**Always:** Cancel previous-profile network and disk side effects at the generation gate. Re-throw CancellationException. Await persistDelta. Use Gherkin Scenario Outline Cases. One assert per test unless SoftAssertions.

**Block If:** A change needs fire-and-forget persistDelta, a live `sp500` run, or a device `android-run-qa`.

**Never:** Do not invent valuation rules. Do not change REFRESH_CONCURRENCY without a failing Case. Do not wipe app data. Do not treat SocketTimeoutException as cancel.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| in-flight Yahoo switch | `dow` refresh fetching, then switch to `qa` | No new `dow`-only fetch symbols | Cancel is success |
| steal refresh | Late stale `startRefresh` after `qa` job assigned | `qa` exclusive row still reaches Live | Stale start returns |
| stale journal | Cancel live refresh after one journaled pass | `appendScoreJournal` count does not rise | Skip finally journal |
| chart cancel | `fetchHistoricalCandles` throws CancellationException | Exclusive row stays Loading | Rethrow, do not apply |
| timeout | Yahoo `SocketTimeoutException` | Result is I/O, not CancellationException | Retry or record diagnostic |
| live paint | Refresh emits inside 300ms | Opportunities rows update without waiting for silence | Keep estimates 2s debounce |
| market read | Switch during `startMarketReadForCurrentProfile` | Old market job does not write the new profile | Cancel or generation check |
| detail cancel | Open ticker during switch | No "Ticker unavailable" from CancellationException | Rethrow or ignore cancel |

</intent-contract>

## Code Map

- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt:724` -- `beginProfileSwitch` / generation bump
- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt:945` -- `startRefresh` generation gate and finally journal
- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt:411` -- `startMarketReadForCurrentProfile` still fire-and-forget
- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt:818` -- `adoptProfileFromStore` disk writes after mutex
- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/remote/YahooFinanceClient.kt:462` -- `executeText` / enqueue / `isCanceledIo`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt:366` -- 300ms snapshot debounce
- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt:691` -- `openDetail` catches Throwable including cancel
- `apps/android/app/src/test/resources/features/profile-switch-cancel.feature` -- Cases for this slice

## Tasks & Acceptance

**Execution:**
- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt` -- paint live refresh without a 300ms silence gate -- debounce fights the fill
- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt` -- cancel or generation-gate the market-read job -- leftover Yahoo after switch
- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt` -- do not treat CancellationException as ticker unavailable -- switch abort is not a missing ticker
- `apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModelTest.kt` -- add Cases for live paint and detail cancel -- matrix coverage
- `apps/android/app/src/test/resources/features/profile-switch-cancel.feature` -- add those Cases to the existing outline -- spec by example

**Acceptance Criteria:**
- Given a live refresh emits inside 300ms, when Opportunities is visible, then the new rows paint without waiting for a quiet gap.
- Given a profile switch, when a prior market read finishes, then it does not write `marketRegime` for the new profile.
- Given Detail open is cancelled by a switch, when the job dies, then the UI does not show "Ticker unavailable".

## Spec Change Log

- 2026-08-17: Closed leftover holes. Live snapshot paint has no 300ms silence gate. Market-read job cancels and generation-gates. Detail cancel rethrows CancellationException.

## Review Triage Log

### 2026-08-17 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 8: (high 4, medium 4, low 0)
- defer: 4: (high 0, medium 3, low 1)
- reject: 0
- addressed_findings:
  - `[high]` `[patch]` Clear marketRegime on reset and start a market read on hydrate
  - `[high]` `[patch]` Skip journal unless the refresh job is still active
  - `[high]` `[patch]` Cancel detail and refresh jobs on profile switch
  - `[high]` `[patch]` persistDelta skips a stale generation; refreshAll reads symbols and generation in one lock
  - `[medium]` `[patch]` Gate the null-market publish; startEnrichment returns when stale
  - `[medium]` `[patch]` Close the Yahoo response if the coroutine is cancelled after enqueue
  - `[medium]` `[patch]` refresh() rethrows CancellationException
  - `[medium]` `[patch]` Market-read test asserts MarketReadStatus.Pending after switch

### 2026-08-17 — Second review pass
- intent_gap: 0
- bad_spec: 0
- patch: 3: (high 2, medium 1, low 0)
- defer: 0
- reject: 0
- addressed_findings:
  - `[high]` `[patch]` refreshAll no longer pairs an old symbol list with a new generation
  - `[high]` `[patch]` selectProfile cancels an in-flight refresh job
  - `[medium]` `[patch]` persistDelta drops a write when the generation moved

## Auto Run Result

Status: done
Blocking condition: none. Juan waived the clean-tree rule. No commit. Valuation WIP stays uncommitted.

Summary: Profile switch now cancels leftover Yahoo, market read, Detail, and refresh jobs. Live Opportunities paint without a 300ms silence gate. Stale journal and persist are skipped.

Files:
- DefaultDashboardRepository.kt — generation gates, market-read cancel, persist skip
- YahooFinanceClient.kt — enqueue cancel, SocketTimeout is I/O
- DashboardViewModel.kt — no snapshot debounce, cancel Detail/refresh jobs
- Tests and profile-switch-cancel.feature — Cases for the matrix

Review: 8 then 3 patches applied. 4 items deferred. Follow-up review recommended: true. High patches this pass: 2. Score: 2 high + 1 medium = above the threshold.

Verification: targeted :app:testDebugUnitTest green. No device QA.

Residual risk: crumb HTTP is still blocking. Market cache can reuse a 150s prior-universe read. adopt disk writes can still race.

## Design Notes

The eight landed patches stay: generation check before steal, job-identity finally, rethrow chart cancel, SocketTimeout is I/O, enqueue cancel, in-flight Yahoo tests.

`persistDelta` stays awaited. Estimates keep the 2s debounce.

## Verification

**Commands:**
- `Set-Location apps\android; .\gradlew.bat :app:testDebugUnitTest --tests com.discountscreener.android.data.repository.DefaultDashboardRepositoryTest.stale_generation_does_not_fetch --tests com.discountscreener.android.data.repository.DefaultDashboardRepositoryTest.second_switch_does_not_cancel_new_refresh --tests com.discountscreener.android.data.repository.DefaultDashboardRepositoryTest.cancelled_refresh_does_not_journal --tests com.discountscreener.android.data.repository.DefaultDashboardRepositoryTest.chart_cancel_aborts_symbol --tests com.discountscreener.android.data.remote.YahooFinanceClientTest.cancelled_request_does_not_retry_sleep --tests com.discountscreener.android.data.remote.YahooFinanceClientTest.socket_timeout_is_not_treated_as_cancel --tests com.discountscreener.android.presentation.dashboard.DashboardViewModelTest --tests com.discountscreener.android.domain.model.FeatureCaseCoverageTest --rerun` -- expected: BUILD SUCCESSFUL, those tests PASS
