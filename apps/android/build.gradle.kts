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
 * **Three minutes is a kill switch, not a budget.** `:app:testDebugUnitTest` takes about 50 seconds
 * and `:core:test` about 13, so this is over three times the slower of the two — wide enough that it
 * cannot fire on an ordinary machine, and tight enough that the next hang costs three minutes rather
 * than the twenty-seven this one did. If a legitimate run ever approaches three minutes, the answer
 * is to find out why the suite got three times slower, not to raise the number.
 */
subprojects {
    tasks.withType<Test>().configureEach {
        testLogging.events("started", "passed", "failed", "skipped")
        timeout.set(Duration.ofMinutes(3))
    }
}
