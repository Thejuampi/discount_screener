package com.discountscreener.android.app

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CrashDumpTest {
    @get:Rule
    var folder = TemporaryFolder()

    @Test
    fun a_capture_writes_a_thread_dump_that_names_the_failing_thread() {
        var dir = folder.newFolder("crash-dumps")
        var dump = CrashDump(dir) { }
        var traces = mapOf(
            Thread.currentThread() to arrayOf(StackTraceElement("Foo", "bar", "Foo.kt", 1)),
        )

        dump.capture(reason = "uncaught", error = IllegalStateException("boom"), traces = traces)

        var text = File(dir.listFiles()!!.single(), "threads.txt").readText()
        assertTrue(text.contains(Thread.currentThread().name))
    }
}
