package com.discountscreener.core.earnings

import java.io.File
import kotlinx.serialization.json.Json

class EarningsEventLog(private val file: File) {

    fun read(): EventLogRead {
        if (!file.isFile) return EventLogRead(events = emptyList(), unreadableLines = 0)
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
        )
    }

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
    }
}

data class EventLogRead(
    val events: List<EarningsEventRecord>,
    val unreadableLines: Int,
)

private data class EventKey(val symbol: String, val reportEpochDay: Long)

private fun EarningsEventRecord.key() = EventKey(pre.symbol, pre.reportEpochDay)
