package com.discountscreener.android.data.repository

import com.discountscreener.android.domain.logging.AppLogger
import java.util.Collections

/**
 * Keeps the timing lines the repository writes, in the order it wrote them.
 *
 * The repository reports its own stages through [AppLogger] (`logStageMillis`). A bench reads them
 * from here, so no bench has to guess where the time went from the outside.
 */
internal class StageRecordingLogger : AppLogger {
    private val messages = Collections.synchronizedList(mutableListOf<String>())

    override fun error(tag: String, message: String, throwable: Throwable?) = Unit

    override fun info(tag: String, message: String) {
        messages.add(message)
    }

    fun clear() = messages.clear()

    fun lines(): List<String> = synchronized(messages) { messages.toList() }

    /** Every reading of every stage, in the order it was written. */
    fun stageSamples(): Map<String, List<Long>> = buildMap<String, MutableList<Long>> {
        lines().filter { line -> line.startsWith(DefaultDashboardRepository.STAGE_TIMING_PREFIX) }
            .forEach { line ->
                val fields = line.split(" ")
                    .mapNotNull { field -> field.split("=").takeIf { it.size == 2 } }
                    .associate { pair -> pair[0] to pair[1] }
                val stage = fields["stage"] ?: return@forEach
                val millis = fields["ms"]?.toLongOrNull() ?: return@forEach
                getOrPut(stage) { mutableListOf() }.add(millis)
            }
    }

    /** A stage that ran more than once inside one window is reported by its last run. */
    fun stageMillis(): Map<String, Long> = stageSamples().mapValues { (_, samples) -> samples.last() }
}
