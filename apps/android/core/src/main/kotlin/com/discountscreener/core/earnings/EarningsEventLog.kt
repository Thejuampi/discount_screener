package com.discountscreener.core.earnings

import java.io.File
import kotlinx.serialization.json.Json

class EarningsEventLog(private val file: File) {

    fun read(): EventLogRead {
        if (!file.isFile) {
            return EventLogRead(
                events = emptyList(),
                unreadableLines = 0,
                lastCaptureEpochSeconds = lastCaptureEpochSeconds(),
            )
        }
        var newest = LinkedHashMap<EventKey, EarningsEventRecord>()
        var damaged = 0
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            var record = decode(line)
            if (record == null) damaged++ else newest[record.key()] = record
        }
        return EventLogRead(
            events = newest.values.sortedWith(compareBy({ it.pre.reportEpochDay }, { it.pre.symbol })),
            unreadableLines = damaged,
            lastCaptureEpochSeconds = lastCaptureEpochSeconds(),
        )
    }

    /**
     * When the capture last ran, whether or not it had anything to write.
     *
     * A pass that wrote nothing leaves no line in the log, so the log alone cannot tell a module
     * that is working from one that stopped running. The mark is what the screen reads to say so.
     */
    fun stampCapture(epochSeconds: Long) {
        markFile().parentFile?.mkdirs()
        runCatching { markFile().writeText(epochSeconds.toString()) }
    }

    fun lastCaptureEpochSeconds(): Long? =
        runCatching { markFile().readText().trim().toLong() }.getOrNull()?.takeIf { it > 0L }

    private fun markFile(): File = File(file.parentFile, file.name + ".captured-at")

    /**
     * The last symbol whose report date the capture asked Yahoo for.
     *
     * One pass can only afford a handful of calendar lookups, and the universe is hundreds of
     * symbols wide. The mark is where the next pass starts, so every stale date gets its turn
     * instead of the same few being asked forever.
     */
    fun stampCalendarCursor(symbol: String) {
        cursorFile().parentFile?.mkdirs()
        runCatching { cursorFile().writeText(symbol) }
    }

    fun calendarCursor(): String? =
        runCatching { cursorFile().readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun cursorFile(): File = File(file.parentFile, file.name + ".calendar-cursor")

    /**
     * What the calendar answered for a symbol, and when it was asked.
     *
     * Yahoo answers a symbol it has no future date for with the last one that passed. Keeping only
     * the date would put that symbol back in the queue on every pass, and the same few names would
     * hold the whole universe behind them. The hour of the ask is what lets the next pass move on.
     */
    fun rememberCalendarAsks(asks: Map<String, CalendarAsk>) {
        if (asks.isEmpty()) return
        var kept = calendarAsks() + asks
        asksFile().parentFile?.mkdirs()
        runCatching {
            asksFile().writeText(
                kept.entries.joinToString(separator = LINE) { (symbol, ask) ->
                    "$symbol ${ask.nextEarningsEpoch ?: NO_DATE} ${ask.askedAtEpochSeconds}"
                },
            )
        }
    }

    fun calendarAsks(): Map<String, CalendarAsk> =
        runCatching { asksFile().readLines() }
            .getOrDefault(emptyList())
            .mapNotNull { line ->
                var parts = line.trim().split(" ")
                if (parts.size != 3) return@mapNotNull null
                var askedAt = parts[2].toLongOrNull() ?: return@mapNotNull null
                parts[0] to CalendarAsk(parts[1].toLongOrNull(), askedAt)
            }
            .toMap()

    private fun asksFile(): File = File(file.parentFile, file.name + ".calendar-asks")

    fun event(symbol: String, reportEpochDay: Long): EarningsEventRecord? =
        read().events.firstOrNull { it.pre.symbol == symbol && it.pre.reportEpochDay == reportEpochDay }

    fun append(record: EarningsEventRecord) {
        file.parentFile?.mkdirs()
        file.appendText(json.encodeToString(EarningsEventRecord.serializer(), record) + "\n")
    }

    fun decide(symbol: String, reportEpochDay: Long, decision: EventDecision): Boolean =
        amend(symbol, reportEpochDay) { it.copy(decision = decision) }

    fun settle(symbol: String, reportEpochDay: Long, post: PostReport): Boolean =
        amend(symbol, reportEpochDay) { it.copy(post = post) }

    private fun amend(
        symbol: String,
        reportEpochDay: Long,
        change: (EarningsEventRecord) -> EarningsEventRecord,
    ): Boolean {
        var current = event(symbol, reportEpochDay) ?: return false
        append(change(current))
        return true
    }

    private fun decode(line: String): EarningsEventRecord? =
        runCatching { json.decodeFromString(EarningsEventRecord.serializer(), line) }.getOrNull()

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        const val LINE = "\n"
        const val NO_DATE = "-"
    }
}

data class CalendarAsk(
    val nextEarningsEpoch: Long?,
    val askedAtEpochSeconds: Long,
)

data class EventLogRead(
    val events: List<EarningsEventRecord>,
    val unreadableLines: Int,
    val lastCaptureEpochSeconds: Long? = null,
)

private data class EventKey(val symbol: String, val reportEpochDay: Long)

private fun EarningsEventRecord.key() = EventKey(pre.symbol, pre.reportEpochDay)
