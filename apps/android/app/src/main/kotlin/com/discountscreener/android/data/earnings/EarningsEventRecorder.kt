package com.discountscreener.android.data.earnings

import com.discountscreener.android.domain.logging.AppLogger
import com.discountscreener.android.domain.logging.NoOpAppLogger
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.core.earnings.ConsensusEstimate
import com.discountscreener.core.earnings.DailyClose
import com.discountscreener.core.earnings.DcfAsOf
import com.discountscreener.core.earnings.EarningsEventLog
import com.discountscreener.core.earnings.EarningsEventRecord
import com.discountscreener.core.earnings.EventLogRead
import com.discountscreener.core.earnings.OptionChainSnapshot
import com.discountscreener.core.earnings.ReportTiming
import com.discountscreener.core.earnings.decisionOf
import com.discountscreener.core.earnings.expiryAfterReport
import com.discountscreener.core.earnings.normalDailyMoveBps
import com.discountscreener.core.earnings.preReportOf
import com.discountscreener.core.earnings.reportTimingOf
import com.discountscreener.core.earnings.settlementOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class EarningsEventRecorder(
    private val log: EarningsEventLog,
    private val chains: OptionChainSource,
    private val consensus: ConsensusSource,
    private val closes: CloseSource,
    private val nowProvider: () -> Long,
    private val logger: AppLogger = NoOpAppLogger,
    private val windowDays: Long = CAPTURE_WINDOW_DAYS,
    private val marketSymbol: String = MARKET_SYMBOL,
) {

    fun interface OptionChainSource {
        suspend fun chain(symbol: String, expiryEpochSeconds: Long?): OptionChainSnapshot?
    }

    fun interface ConsensusSource {
        suspend fun consensus(symbol: String): ConsensusEstimate?
    }

    fun interface CloseSource {
        suspend fun closes(symbol: String): List<DailyClose>
    }

    fun events(): EventLogRead = log.read()

    suspend fun capture(rows: List<OpportunityListRow>): Int {
        var today = LocalDate.ofInstant(Instant.ofEpochSecond(nowProvider()), ZoneOffset.UTC)
        var stored = settleDueEvents(log.read().events, today)
        var known = stored.mapTo(HashSet()) { it.pre.symbol to it.pre.reportEpochDay }
        var reactions = stored
            .mapNotNull { record -> record.post?.abnormalReturnBps?.let { record.pre.symbol to it } }
            .groupBy({ it.first }, { it.second })
        var written = 0
        rows.forEach { row ->
            var epoch = row.nextEarningsEpoch ?: return@forEach
            var (reportDate, timing) = reportTimingOf(epoch)
            if (!isInWindow(reportDate, today)) return@forEach
            if (row.symbol to reportDate.toEpochDay() in known) return@forEach
            runCatching {
                captureOne(row, reportDate, timing, reactions[row.symbol].orEmpty(), today)
            }
                .onSuccess { written++ }
                .onFailure { error -> logger.error(TAG, "earnings capture failed: ${row.symbol}", error) }
        }
        return written
    }

    private suspend fun settleDueEvents(
        stored: List<EarningsEventRecord>,
        today: LocalDate,
    ): List<EarningsEventRecord> {
        var due = stored.filter { it.post?.abnormalReturnBps == null && isSettleable(it, today) }
        if (due.isEmpty()) return stored
        var market = runCatching { closes.closes(marketSymbol) }
            .onFailure { error -> logger.error(TAG, "earnings settle failed: $marketSymbol", error) }
            .getOrDefault(emptyList())
        var updated = stored.toMutableList()
        due.forEach { record ->
            runCatching { settleOne(record, market) }
                .onSuccess { settled -> settled?.let { updated[updated.indexOf(record)] = it } }
                .onFailure { error ->
                    logger.error(TAG, "earnings settle failed: ${record.pre.symbol}", error)
                }
        }
        return updated
    }

    private suspend fun settleOne(
        record: EarningsEventRecord,
        marketCloses: List<DailyClose>,
    ): EarningsEventRecord? {
        var post = settlementOf(record.pre, closes.closes(record.pre.symbol), marketCloses) ?: return null
        log.settle(record.pre.symbol, record.pre.reportEpochDay, post)
        return record.copy(post = post)
    }

    private fun isSettleable(record: EarningsEventRecord, today: LocalDate): Boolean {
        var days = today.toEpochDay() - record.pre.reportEpochDay
        return days in 1..SETTLE_WINDOW_DAYS
    }

    private suspend fun captureOne(
        row: OpportunityListRow,
        reportDate: LocalDate,
        timing: ReportTiming,
        pastReactionsBps: List<Int>,
        today: LocalDate,
    ) {
        var expiries = chains.chain(row.symbol, null)?.expiries.orEmpty()
        var expiry = expiryAfterReport(expiries, reportDate)
        var chain = expiry?.let {
            chains.chain(row.symbol, it.atStartOfDay(ZoneOffset.UTC).toEpochSecond())
        }
        var pre = preReportOf(
            symbol = row.symbol,
            reportDate = reportDate,
            timing = timing,
            priceCents = row.marketPriceCents,
            dcf = row.intrinsicValueCents?.let { DcfAsOf(it, computedOn = today) },
            chain = chain,
            expiry = expiry,
            consensus = consensus.consensus(row.symbol),
            pastAbnormalReturnsBps = pastReactionsBps,
            normalDailyMoveBps = quietDayMoveOf(row.symbol),
        )
        log.append(EarningsEventRecord(pre = pre, decision = decisionOf(pre)))
    }

    private suspend fun quietDayMoveOf(symbol: String): Int? =
        runCatching { normalDailyMoveBps(closes.closes(symbol)) }
            .onFailure { error -> logger.error(TAG, "earnings quiet day read failed: $symbol", error) }
            .getOrNull()

    private fun isInWindow(reportDate: LocalDate, today: LocalDate): Boolean {
        var days = reportDate.toEpochDay() - today.toEpochDay()
        return days in 0..windowDays
    }

    private companion object {
        const val CAPTURE_WINDOW_DAYS = 10L
        const val SETTLE_WINDOW_DAYS = 30L
        const val MARKET_SYMBOL = "SPY"
        const val TAG = "DiscountScreener"
    }
}
