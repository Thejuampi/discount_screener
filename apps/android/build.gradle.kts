import java.time.Duration

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}

/**
 * Make a hung test say its own name, and stop waiting for it after fifteen minutes.
 *
 * Both settings were bought with one incident. A Compose test spun inside
 * `ComposeIdlingResource.isIdleNow` → `advanceTimeByFrame` for 27 minutes at a full core before it
 * was killed by hand, and **nothing said so**. Gradle writes its XML reports only when the whole
 * task ends, and it logs a test only once that test *finishes* — the one event a hung test never
 * produces. So the console, the results directory, and a healthy slow run were indistinguishable.
 * Naming the test needed a thread dump of the worker process.
 *
 * `started` is the fix for the silence: the last line printed is the test that is stuck. The timeout
 * is the fix for the waiting: a hang becomes a failure with a stack rather than a build that never
 * returns.
 *
 * **Three minutes is a kill switch, not a budget.** `:app:testDebugUnitTest` takes about 70 seconds
 * and `:core:test` about 12, so this is over twice the slower of the two — wide enough that it
 * cannot fire on an ordinary machine, and tight enough that the next hang costs three minutes rather
 * than the twenty-seven this one did. If a legitimate run ever approaches three minutes, the answer
 * is to find out why the suite got slower, not to raise the number.
 *
 * It approached it once, on 2026-08-20, at 138 seconds for the debug variant alone. The suite had
 * grown: 100 of those seconds belong to classes added in one branch, and 34 to everything that came
 * before them. The load, quota and startup tests hold real wall-clock windows because the properties
 * they measure are made of time — the governor's 1, 2 and 4 second hold ladder is the subject, so
 * shortening the window measures something else. One of them costs 28 seconds on its own. Shrinking
 * them buys speed by giving up the reading.
 *
 * So the classes run beside each other instead. Four forks on a twenty-core machine took the debug
 * variant from 138 seconds to about 70, measured green three runs running, and one quarter of the
 * cores per fork leaves the timing tests enough machine to still read what they read. Raising the
 * cap higher starves them: these tests assert on elapsed milliseconds, so a fork count that outruns
 * the machine turns a real property into a flake.
 */
subprojects {
    tasks.withType<Test>().configureEach {
        testLogging.events("started", "passed", "failed", "skipped")
        timeout.set(Duration.ofMinutes(3))
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 4)
    }
}
