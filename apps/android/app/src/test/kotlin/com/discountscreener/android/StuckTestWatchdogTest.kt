package com.discountscreener.android

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * The watchdog only ever runs on the day something is already broken, so it is checked here rather
 * than trusted. A tripwire nobody has seen fire is a tripwire nobody knows is connected — and the
 * reason this class exists at all is that the last one produced no evidence when it mattered.
 *
 * Both directions are asserted. A rule that always printed would pass a fires-on-stall test and be
 * useless; a rule that never printed would pass a stays-quiet test and be worse than useless.
 */
class StuckTestWatchdogTest {
    @Test
    fun a_test_that_stalls_past_the_threshold_gets_its_threads_printed() {
        val err = captureStderrWhile(stallMillis = 400L, stallSeconds = 0L)

        assertTrue(
            "the watchdog must name the stuck test and dump threads, got: $err",
            "STUCK TEST WATCHDOG" in err && "a-stalling-test" in err,
        )
    }

    /**
     * The companion, and the one that makes the assertion above mean something: 415 tests share this
     * JVM, and a watchdog that printed a full thread dump after every one of them would bury the
     * real report in noise the next time it fires.
     */
    @Test
    fun a_test_that_finishes_promptly_prints_nothing() {
        val err = captureStderrWhile(stallMillis = 0L, stallSeconds = 30L)

        assertTrue("a prompt test must produce no dump, got: $err", err.isBlank())
    }

    private fun captureStderrWhile(stallMillis: Long, stallSeconds: Long): String {
        val captured = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(captured, true))
        try {
            val body = object : Statement() {
                override fun evaluate() {
                    Thread.sleep(stallMillis)
                }
            }
            StuckTestWatchdog(stallSeconds = stallSeconds)
                .apply(body, Description.createTestDescription(javaClass, "a-stalling-test"))
                .evaluate()
            // The dump runs on the watchdog thread, which the rule interrupts on the way out; give
            // it the moment it needs to finish writing before the stream is read.
            Thread.sleep(200L)
        } finally {
            System.setErr(original)
        }
        return captured.toString()
    }
}
