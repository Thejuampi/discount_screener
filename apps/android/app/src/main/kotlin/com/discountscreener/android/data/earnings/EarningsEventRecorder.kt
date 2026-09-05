package com.discountscreener.android.data.earnings

import com.discountscreener.android.domain.logging.AppLogger
import com.discountscreener.android.domain.logging.NoOpAppLogger
import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.core.earnings.CAPTURE_WINDOW_DAYS
import com.discountscreener.core.earnings.CalendarAsk
import com.discountscreener.core.earnings.ConsensusEstimate
import com.discountscreener.core.earnings.DailyClose
import com.discountscreener.core.earnings.DcfAsOf
import com.discountscreener.core.earnings.EarningsAnnouncement
import com.discountscreener.core.earnings.EarningsEventLog
import com.discountscreener.core.earnings.EarningsEventRecord
import com.discountscreener.core.earnings.EventLogRead
import com.discountscreener.core.earnings.OptionChainSnapshot
import com.discountscreener.core.earnings.ReportTiming
import com.discountscreener.core.earnings.ReportedQuarter
import com.discountscreener.core.earnings.decisionOf
import com.discountscreener.core.earnings.EXCHANGE_ZONE
import com.discountscreener.core.earnings.expiryAfterReport
import com.discountscreener.core.earnings.isQuoteStale
import com.discountscreener.core.earnings.marketBetaExcludingEvents
import com.discountscreener.core.earnings.normalDailyMoveBps
import com.discountscreener.core.earnings.pastAbnormalReturnsOf
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
    private val history: CloseSource = closes,
    private val announcements: AnnouncementSource = AnnouncementSource { emptyList() },
    private val reported: ReportedQuarterSource = ReportedQuarterSource { emptyList() },
    private val calendar: CalendarSource = CalendarSource { _, _ -> null },
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

    fun interface AnnouncementSource {
        suspend fun announcements(symbol: String): List<EarningsAnnouncement>
    }

    fun interface ReportedQuarterSource {
        suspend fun quarters(symbol: String): List<ReportedQuarter>
    }

    fun interface CalendarSource {
        suspend fun nextEarningsEpoch(symbol: String, nowEpochSeconds: Long): Long?
    }

    fun events(): EventLogRead = log.read()

    fun backupText(): String = log.backupText()

    fun restore(text: String): Int = log.restore(text)

    /**
     * A report the chain never priced is asked again on the next pass.
     *
     * An option chain is never republished. One failed lookup would otherwise cost the event its
     * priced move for good, which is the loss this log exists to prevent. The retry stops when the
     * report leaves the capture window.
     */
    suspend fun capture(rows: List<OpportunityListRow>): Int {
        var today = Instant.ofEpochSecond(nowProvider()).atZone(EXCHANGE_ZONE).toLocalDate()
        var stored = settleDueEvents(log.read().events, today)
        var priced = stored
            .filter { it.pre.impliedMoveBps != null && !isQuoteStale(it.pre) }
            .mapTo(HashSet()) { it.pre.symbol to it.pre.reportEpochDay }
        var reactions = stored
            .mapNotNull { record -> record.post?.abnormalReturnBps?.let { record.pre.symbol to it } }
            .groupBy({ it.first }, { it.second })
        var written = 0
        var refreshed = refreshStaleDates(rows, today)
        rows.forEach { row ->
            var epoch = refreshed[row.symbol] ?: row.nextEarningsEpoch ?: return@forEach
            var (reportDate, timing) = reportTimingOf(epoch)
            if (!isInWindow(reportDate, today)) return@forEach
            if (row.symbol to reportDate.toEpochDay() in priced) return@forEach
            runCatching {
                captureOne(row, reportDate, timing, reactions[row.symbol].orEmpty(), today)
            }
                .onSuccess { written++ }
                .onFailure { error -> logger.error(TAG, "earnings capture failed: ${row.symbol}", error) }
        }
        log.stampCapture(nowProvider())
        return written
    }

    /**
     * The report dates the last refresh left behind, asked again a few at a time.
     *
     * A refresh needs the app open. A phone nobody opens carries dates that have already passed,
     * or none at all, and the capture then prices nothing while the chains it needed expire. Each
     * pass buys a handful of lookups and starts where the last one stopped, so the whole universe
     * comes round.
     */
    private suspend fun refreshStaleDates(
        rows: List<OpportunityListRow>,
        today: LocalDate,
    ): Map<String, Long> {
        var stale = rows.filter { isStale(it.nextEarningsEpoch, today) }.map { it.symbol }
        if (stale.isEmpty()) return emptyMap()
        var asks = log.calendarAsks().filterKeys { it in stale }
        var known = asks
            .mapNotNull { (symbol, ask) ->
                ask.nextEarningsEpoch?.takeIf { !isStale(it, today) }?.let { symbol to it }
            }
            .toMap()
        var open = stale.filterNot { it in known || askedRecently(asks[it]) }.sorted()
        if (open.isEmpty()) return known
        var cursor = log.calendarCursor()
        var start = cursor?.let { mark -> open.indexOfFirst { it > mark }.takeIf { it >= 0 } } ?: 0
        var asked = List(minOf(CALENDAR_LOOKUPS_PER_PASS, open.size)) { open[(start + it) % open.size] }
        var found = HashMap<String, CalendarAsk>()
        asked.forEach { symbol ->
            var epoch = runCatching { calendar.nextEarningsEpoch(symbol, nowProvider()) }
                .onFailure { error -> logger.error(TAG, "earnings calendar failed: $symbol", error) }
                .getOrNull()
            found[symbol] = CalendarAsk(epoch, nowProvider())
        }
        log.stampCalendarCursor(asked.last())
        log.rememberCalendarAsks(found)
        logger.info(TAG, "earnings calendar: asked ${asked.size} of ${open.size} stale date(s)")
        return known + found.mapNotNull { (symbol, ask) -> ask.nextEarningsEpoch?.let { symbol to it } }
    }

    private fun askedRecently(ask: CalendarAsk?): Boolean {
        var at = ask?.askedAtEpochSeconds ?: return false
        return nowProvider() - at < CALENDAR_RECHECK_SECONDS
    }

    private fun isStale(epochSeconds: Long?, today: LocalDate): Boolean {
        var epoch = epochSeconds ?: return true
        return reportTimingOf(epoch).first < today
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
        var filings = filedAnnouncementsOf(record.pre.symbol)
        var post = settlementOf(
            pre = record.pre,
            symbolCloses = closes.closes(record.pre.symbol),
            marketCloses = marketCloses,
            reportedQuarters = reportedQuartersOf(record.pre.symbol),
            marketBeta = betaOf(record.pre.symbol, filings),
            announcements = filings,
        ) ?: return null
        log.settle(record.pre.symbol, record.pre.reportEpochDay, post)
        return record.copy(post = post)
    }

    /**
     * How much of the ticker's move the index explains, measured away from its own reports.
     *
     * The long history and the filed report dates are already bought for the risk denominator, so
     * the slope costs no call of its own. A ticker whose history is too short keeps the one-for-one
     * subtraction it had.
     */
    private suspend fun betaOf(symbol: String, filings: List<EarningsAnnouncement>): Double? =
        runCatching {
            marketBetaExcludingEvents(
                symbolCloses = history.closes(symbol),
                marketCloses = marketHistory(),
                eventDates = filings.map { it.date },
            )
        }
            .onFailure { error -> logger.error(TAG, "earnings beta failed: $symbol", error) }
            .getOrNull()

    private suspend fun filedAnnouncementsOf(symbol: String): List<EarningsAnnouncement> =
        runCatching { announcements.announcements(symbol) }
            .onFailure { error -> logger.error(TAG, "earnings filing list failed: $symbol", error) }
            .getOrDefault(emptyList())

    private suspend fun reportedQuartersOf(symbol: String): List<ReportedQuarter> =
        runCatching { reported.quarters(symbol) }
            .onFailure { error -> logger.error(TAG, "earnings actuals failed: $symbol", error) }
            .getOrDefault(emptyList())

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
        var expiry = expiryAfterReport(expiries, reportDate, timing)
        var chain = expiry?.let {
            chains.chain(row.symbol, it.atStartOfDay(ZoneOffset.UTC).toEpochSecond())
        }
        var filed = filedReactionsOf(row.symbol)
        var pre = preReportOf(
            symbol = row.symbol,
            reportDate = reportDate,
            timing = timing,
            priceCents = row.marketPriceCents,
            dcf = row.intrinsicValueCents?.let { DcfAsOf(it, computedOn = today) },
            chain = chain,
            expiry = expiry,
            consensus = consensus.consensus(row.symbol),
            pastAbnormalReturnsBps = filed.ifEmpty { pastReactionsBps },
            normalDailyMoveBps = quietDayMoveOf(row.symbol),
        )
        log.append(EarningsEventRecord(pre = pre, decision = decisionOf(pre)))
    }

    private suspend fun filedReactionsOf(symbol: String): List<Int> =
        runCatching {
            var events = announcements.announcements(symbol)
            if (events.isEmpty()) emptyList() else pastAbnormalReturnsOf(
                announcements = events,
                symbolCloses = history.closes(symbol),
                marketCloses = marketHistory(),
            )
        }
            .onFailure { error -> logger.error(TAG, "earnings filing history failed: $symbol", error) }
            .getOrDefault(emptyList())

    private suspend fun marketHistory(): List<DailyClose> =
        marketHistoryCache ?: history.closes(marketSymbol).also { marketHistoryCache = it }

    private var marketHistoryCache: List<DailyClose>? = null

    private suspend fun quietDayMoveOf(symbol: String): Int? =
        runCatching { normalDailyMoveBps(closes.closes(symbol)) }
            .onFailure { error -> logger.error(TAG, "earnings quiet day read failed: $symbol", error) }
            .getOrNull()

    private fun isInWindow(reportDate: LocalDate, today: LocalDate): Boolean {
        var days = reportDate.toEpochDay() - today.toEpochDay()
        return days in 0..windowDays
    }

    private companion object {
        const val CALENDAR_LOOKUPS_PER_PASS = 12
        const val CALENDAR_RECHECK_SECONDS = 24L * 60L * 60L
        const val SETTLE_WINDOW_DAYS = 30L
        const val MARKET_SYMBOL = "SPY"
        const val TAG = "DiscountScreener"
    }
}
