# The Android app suite hung for 27 minutes, once, and has not done it again

**Status: open, cause unknown.** Bounded, not fixed. Escalation bar at the bottom of this file.

## What happened

On 2026-08-11, `./gradlew :core:test :app:testDebugUnitTest --rerun` in `apps/android` stopped
producing output. It was not slow. It ran for 27 minutes at a full core and was killed by hand.

Nothing in the ordinary output said so, and that is half of why this file exists:

- Gradle writes its JUnit XML reports only when the whole task ends. The results directory still
  held the *previous* run's files, so it looked stale rather than empty.
- Gradle logs a test only once that test **finishes** — the one event a hung test cannot produce.
  The console was silent in exactly the way it is silent during healthy work.
- `binary/output.bin` had not grown since minute one.

A healthy slow run and a permanent hang were indistinguishable from outside the process. Naming
the cause needed `jstack` on the test worker.

## What the dumps show

Two dumps, 70 seconds apart, in this directory. They prove a **live spin**, not a deadlock:
`SDK 35 Main Thread` had burned 1,568 s of CPU in 1,610 s of wall clock, and the two dumps caught
it at different frames of the same loop.

`jstack-1.txt` — caught inside the idle check:

```
java.util.HashSet.<init>
androidx.test.espresso.IdlingRegistry.getResources
org.robolectric.android.internal.LocalUiController.syncIdlingResources
org.robolectric.android.internal.LocalUiController.loopMainThreadUntilIdle
androidx.compose.ui.test.EspressoLink_androidKt.runEspressoOnIdle
androidx.compose.ui.test.RobolectricIdlingStrategy.runUntilIdle
androidx.compose.ui.test.AndroidComposeUiTestEnvironment.runTest
```

`jstack-2.txt` — same loop, caught in the clock instead:

```
kotlin.time.DurationKt.durationOfNanos
kotlinx.coroutines.test.TestCoroutineScheduler.advanceTimeBy
androidx.compose.ui.test.AbstractMainTestClock.advanceTimeByFrame
androidx.compose.ui.test.ComposeIdlingResource.isIdleNow
androidx.compose.ui.test.RobolectricIdlingStrategy.runUntilIdle
```

Reading: a Compose test called `waitForIdle`, the composition never reached idle, and the strategy
advanced the test clock a frame at a time forever. `Test worker` was parked waiting on that future,
so the whole task was blocked behind one test.

**The test was never identified.** Because Gradle prints a test only on completion, the log named
every test *except* the one that mattered.

## What is not the cause

- **Not the `§` render shipped in `dfc06960`.** That is a static `FlowRow` of text tokens. It has no
  animation and no recomposition loop, so it cannot drive the Compose clock. `DetailScoreHeaderTest`
  runs in 16 s on its own, including the two tests that commit added.
- **Not a deadlock.** Both dumps are `RUNNABLE`, at different frames, burning CPU.
- **Not an out-of-memory thrash.** The GC threads had accumulated ordinary CPU time; the spin is in
  application code, not in the collector.

## Reproduction attempts

Three full runs after the kill, all green, none hung:

| Run | Result |
|---|---|
| `:app:testDebugUnitTest --rerun` | 410 passed, 0 failed, 50 s |
| `:core:test --rerun` | 512 passed, 0 failed, 13 s |
| both, `--rerun` on each task | 922 passed, 0 failed, 45 s |

## What was done instead of a fix

`1c866d66` adds, in `apps/android/build.gradle.kts` under `subprojects`:

- `testLogging.events("started", …)` — the last line printed is now the stuck test, so the next
  occurrence names itself and no thread dump is needed to find it.
- `timeout = 3 minutes` — a hang becomes a failure with a stack instead of a build that never
  returns. The suite runs in ~50 s, so this can only fire on a defect.

This makes the next occurrence cheap and bounded. **It does not prevent it.**

## Escalation bar

Set deliberately, and low, because three of this effort's defects were the verification instruments
failing rather than the product:

> **If this recurs even once — during Wave 4a's journal run, during the Rust-port wave, or in
> ordinary CI — it escalates to mandatory root-cause. Not a second round of bounding.**

One unexplained hang in the harness that grades this product is a registered risk. Two is a pattern,
and a pattern in the instrument has to be answered before the instrument is trusted again.

When it recurs, the work is already scoped: read the last `STARTED` line, run that class alone in a
loop until it hangs, and look for an indefinite animation or a coroutine that keeps posting to the
main looper.
