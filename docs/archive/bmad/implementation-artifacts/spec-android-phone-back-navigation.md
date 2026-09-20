 ---
title: 'Android phone back navigation'
type: 'feature'
created: '2026-04-23T23:45:13.122-04:00'
baseline_commit: '2ff8179ac5c671706306ec13ee9fea1c46d70ede'
status: 'done'
context: ['{project-root}/apps/android/README.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The Android app can open symbol detail from the dashboard, but returning from detail currently depends on the in-app Back button only. The physical back button, gesture back, and navigation-bar back do not participate in that flow, so phone-native navigation feels broken.

**Approach:** Keep the existing manual state-driven navigation model and hook Android system back into the same `DashboardAction.BackFromDetail` path already used by the detail top bar. When detail is visible, system back should return to the dashboard; when detail is not visible, Android should keep its normal platform behavior.

## Boundaries & Constraints

**Always:** Preserve the current MVP-style Android split, keep navigation state in the existing `detailRoute` UI state, reuse `DashboardAction.BackFromDetail` instead of inventing a second back path, preserve the existing in-app Back affordance, and keep dashboard/root back behavior delegated to Android when no detail screen is active.

**Ask First:** Expanding this change beyond detail-screen back navigation, changing disclaimer or splash back behavior, introducing deeper back-stack history than the current single dashboard/detail split, or adding a navigation framework.

**Never:** Migrate to Navigation Compose for this task, move business logic into Compose UI code, add Activity-level back overrides that swallow root-level system back, or change core/domain behavior unrelated to screen navigation.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Detail back | Detail screen is visible and Android system back is pressed | App dispatches the same back action as the UI Back button and returns to dashboard content | N/A |
| Detail substate retained only while open | Detail is visible on Snapshot or History, possibly with replay offset or chart selection | System back exits detail rather than partially rewinding inner detail state | N/A |
| Root dashboard | Dashboard is visible and no detail route is active | Android system back keeps default platform behavior (finish/leave app) | N/A |
| Splash/disclaimer | App is not yet in main content stage | Existing splash/disclaimer close behavior remains unchanged | N/A |

</frozen-after-approval>

## Code Map

- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/DiscountScreenerApp.kt` -- top-level app shell that decides whether dashboard or detail content is mounted from `detailRoute`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DetailScreen.kt` -- detail view with the existing explicit Back button; primary UI hook for Android system back handling
- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt` -- existing presenter/state owner with `DashboardAction.BackFromDetail` and `backFromDetail()`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModelTest.kt` -- existing back-route regression coverage for presenter behavior
- `apps/android/app/build.gradle.kts` -- Android app test/tooling configuration if a local UI regression test needs Compose/Robolectric support
- `apps/android/app/src/test/kotlin/com/discountscreener/android/ui/DiscountScreenerAppBackNavigationTest.kt` -- candidate local UI regression test location for system-back behavior

## Tasks & Acceptance

**Execution:**
- [x] `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DetailScreen.kt` -- register Android system back while the detail screen is mounted and delegate it to `DashboardAction.BackFromDetail` -- ensures physical/gesture/nav-bar back uses the same exit path as the current UI button
- [x] `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/DiscountScreenerApp.kt` -- keep the dashboard/detail route swap compatible with system back handling and avoid intercepting back outside detail mode -- preserves normal Android exit behavior at the root surface
- [x] `apps/android/app/src/test/kotlin/com/discountscreener/android/ui/DiscountScreenerAppBackNavigationTest.kt` -- add the lightest practical regression coverage for the new UI back wiring, or reduce it to presenter-level assertions only if the current local Compose/Robolectric stack cannot support a stable UI test without extra framework churn -- protects against future regressions without overbuilding nav infrastructure
- [x] `apps/android/README.md` -- document that Android detail now supports system back via phone buttons/gesture/nav bar if the behavior is user-visible enough to merit operator guidance -- keeps the Android docs aligned with shipped behavior

**Acceptance Criteria:**
- Given the dashboard is showing symbol detail, when the user presses the Android system back button, swipe-back gesture, or navigation-bar back control, then the app returns to the dashboard instead of requiring the in-app Back button.
- Given the user is on the dashboard with no detail route active, when they press Android system back, then the app keeps the existing platform-default behavior instead of consuming that event inside Compose.
- Given the user is on detail History or Snapshot with ticker-local UI state active, when they press Android system back, then the app exits detail in one step through `BackFromDetail`.
- Given the existing top-bar Back button is used, when the user returns from detail, then the resulting state matches the Android system back path.

## Spec Change Log

- 2026-04-24: Wired Android system back through `DashboardAction.BackFromDetail`, added local Robolectric regression coverage for detail/root back behavior, and updated Android docs/status.

## Design Notes

The app already has a single source of truth for “detail is open”: `DashboardUiState.detailRoute`. That makes this a UI integration change, not a navigation-model rewrite.

The safest design is to bind system back only while `DetailScreen` is mounted and route it through the existing presenter action. That preserves the current manual navigation architecture, avoids dual logic paths, and leaves root-level Android back behavior untouched.

## Verification

**Commands:**
- `pwsh ./scripts/validate-android.ps1` -- expected: `:core:test` passes and, when an SDK is configured, `:app:testDebugUnitTest` and `:app:assembleDebug` pass

**Manual checks (if no CLI):**
- Open the Android app, enter a symbol detail screen, press the physical/system/nav-bar back control, and confirm the app returns to dashboard content.
- From the dashboard root, press Android system back and confirm the app uses normal Android exit/background behavior rather than swallowing the event.

## Suggested Review Order

**Back interception**

- Start where Android system back is converted into the existing detail-exit action.
  [`DetailScreen.kt:95`](../../apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DetailScreen.kt#L95)

- Confirm the detail app bar still uses the same presenter action path.
  [`DetailScreen.kt:125`](../../apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DetailScreen.kt#L125)

**Screen routing boundary**

- Verify dashboard/detail selection still depends only on `detailRoute`.
  [`DiscountScreenerApp.kt:65`](../../apps/android/app/src/main/kotlin/com/discountscreener/android/ui/DiscountScreenerApp.kt#L65)

**Regression coverage**

- Check the detail-screen test that proves system back dispatches `BackFromDetail`.
  [`DiscountScreenerAppBackNavigationTest.kt:28`](../../apps/android/app/src/test/kotlin/com/discountscreener/android/ui/DiscountScreenerAppBackNavigationTest.kt#L28)

- Check the root-dashboard test that preserves default activity back behavior.
  [`DiscountScreenerAppBackNavigationTest.kt:61`](../../apps/android/app/src/test/kotlin/com/discountscreener/android/ui/DiscountScreenerAppBackNavigationTest.kt#L61)

**Documentation**

- End with the Android README note describing the new phone-native back behavior.
  [`README.md:21`](../../apps/android/README.md#L21)
