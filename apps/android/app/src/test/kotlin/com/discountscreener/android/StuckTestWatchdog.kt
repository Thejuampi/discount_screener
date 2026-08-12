package com.discountscreener.android

import java.util.concurrent.TimeUnit
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Prints every thread's stack if one test runs far longer than any test here ever should.
 *
 * **Why this exists.** The app suite hung for 27 minutes inside `ComposeIdlingResource.isIdleNow ->
 * advanceTimeByFrame`, diagnosed only because somebody was watching and ran `jstack` by hand twice.
 * A three-minute task timeout was added so the next one would die quickly instead of eating an
 * afternoon. It then recurred — and the timeout killed the JVM, which threw away the test reports
 * and produced **no stack trace at all**. A tripwire that leaves no evidence buys one thing: the
 * knowledge that it happened again. That is not enough to fix it, and the standing rule for this
 * defect is that a recurrence gets root-caused rather than bounded a second time.
 *
 * **It observes and never intervenes.** `org.junit.rules.Timeout` would be the obvious tool and is
 * the wrong one here: it runs the test body on a *separate* thread, and these are Robolectric and
 * Compose tests that must stay on the main looper. So this starts a daemon thread that sleeps,
 * prints, and exits. It fails nothing, interrupts nothing, and changes no test's semantics — the
 * three-minute task timeout is still what stops the build. The only difference is that the log now
 * says which test was running and what every thread was doing while it did not finish.
 *
 * [STALL_SECONDS] is two orders of magnitude above the slowest widget test in this module (~0.2s),
 * so it cannot fire on a merely slow machine. It is a stall detector, not a performance budget.
 */
class StuckTestWatchdog(private val stallSeconds: Long = STALL_SECONDS) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val watchdog = Thread {
                try {
                    TimeUnit.SECONDS.sleep(stallSeconds)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                dumpAllThreads(description.displayName)
            }
            watchdog.isDaemon = true
            watchdog.name = "stuck-test-watchdog"
            watchdog.start()
            try {
                base.evaluate()
            } finally {
                watchdog.interrupt()
            }
        }
    }

    private fun dumpAllThreads(testName: String) {
        val report = buildString {
            appendLine()
            appendLine("=== STUCK TEST WATCHDOG ===")
            appendLine("$testName has not finished after $STALL_SECONDS seconds.")
            appendLine("Every thread's stack follows. See docs/diagnostics/2026-08-11-compose-test-hang/.")
            Thread.getAllStackTraces().forEach { (thread, frames) ->
                appendLine()
                appendLine("\"${thread.name}\" state=${thread.state} daemon=${thread.isDaemon}")
                frames.forEach { frame -> appendLine("    at $frame") }
            }
            appendLine("=== END STUCK TEST WATCHDOG ===")
        }
        System.err.println(report)
        System.err.flush()
    }

    private companion object {
        const val STALL_SECONDS = 45L
    }
}
