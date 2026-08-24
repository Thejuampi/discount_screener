package com.discountscreener.android.app

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a thread dump (and optionally a heap dump) the process can still produce.
 * SIGKILL from the low-memory killer does not run this. [onTrimMemory] is the last
 * Java callback before that kill.
 */
class CrashDump(
    private val root: File,
    private val dumpHeap: (File) -> Unit = {},
) {
    fun capture(
        reason: String,
        error: Throwable? = null,
        traces: Map<Thread, Array<StackTraceElement>> = Thread.getAllStackTraces(),
        writeHeap: Boolean = true,
    ): File {
        root.mkdirs()
        var stamp = STAMP.format(Date())
        var dir = File(root, stamp)
        dir.mkdirs()
        File(dir, "reason.txt").writeText(buildString {
            appendLine(reason)
            if (error != null) {
                appendLine(error::class.java.name)
                appendLine(error.message ?: "")
                appendLine(error.stackTraceToString())
            }
        })
        File(dir, "threads.txt").writeText(renderThreads(traces))
        if (writeHeap) {
            runCatching { dumpHeap(File(dir, "heap.hprof")) }
        }
        Log.e(TAG, "crash dump written to ${dir.absolutePath} reason=$reason")
        return dir
    }

    private fun renderThreads(traces: Map<Thread, Array<StackTraceElement>>): String = buildString {
        traces.entries.sortedBy { entry -> entry.key.name }.forEach { (thread, frames) ->
            appendLine("\"${thread.name}\" prio=${thread.priority} state=${thread.state} id=${thread.id}")
            frames.forEach { frame ->
                appendLine("    at $frame")
            }
            appendLine()
        }
    }

    private companion object {
        private const val TAG = "DiscountScreener"
        private val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    }
}
