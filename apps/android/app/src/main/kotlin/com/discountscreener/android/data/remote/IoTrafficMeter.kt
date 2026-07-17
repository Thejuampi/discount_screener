package com.discountscreener.android.data.remote

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide counters for network/local IO volume.
 * Used for before/after optimization comparisons (tests + debug logcat).
 */
class IoTrafficMeter {
    private val quoteSummary = AtomicLong(0)
    private val chart = AtomicLong(0)
    private val timeseries = AtomicLong(0)
    private val session = AtomicLong(0)
    private val otherHttp = AtomicLong(0)
    private val chartByRange = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private val bytesIn = AtomicLong(0)
    private val http429 = AtomicLong(0)
    private val http5xx = AtomicLong(0)
    private val sqlitePersistBatches = AtomicLong(0)
    private val sqliteRawCaptures = AtomicLong(0)
    private val sqliteRevisions = AtomicLong(0)

    fun recordQuoteSummary() {
        quoteSummary.incrementAndGet()
    }

    fun recordChart(rangeName: String? = null) {
        chart.incrementAndGet()
        if (rangeName != null) {
            chartByRange.getOrPut(rangeName) { AtomicLong(0) }.incrementAndGet()
        }
    }

    fun recordTimeseries() {
        timeseries.incrementAndGet()
    }

    fun recordSession() {
        session.incrementAndGet()
    }

    fun recordOtherHttp() {
        otherHttp.incrementAndGet()
    }

    fun recordBytesIn(bytes: Long) {
        if (bytes > 0) bytesIn.addAndGet(bytes)
    }

    fun recordHttpStatus(code: Int) {
        when {
            code == 429 -> http429.incrementAndGet()
            code in 500..599 -> http5xx.incrementAndGet()
        }
    }

    fun recordSqliteBatch(rawCaptures: Int, revisions: Int) {
        sqlitePersistBatches.incrementAndGet()
        sqliteRawCaptures.addAndGet(rawCaptures.toLong())
        sqliteRevisions.addAndGet(revisions.toLong())
    }

    fun snapshot(): IoTrafficSnapshot = IoTrafficSnapshot(
        quoteSummary = quoteSummary.get(),
        chart = chart.get(),
        timeseries = timeseries.get(),
        session = session.get(),
        otherHttp = otherHttp.get(),
        chartByRange = chartByRange.mapValues { it.value.get() },
        bytesIn = bytesIn.get(),
        http429 = http429.get(),
        http5xx = http5xx.get(),
        sqlitePersistBatches = sqlitePersistBatches.get(),
        sqliteRawCaptures = sqliteRawCaptures.get(),
        sqliteRevisions = sqliteRevisions.get(),
    )

    fun reset() {
        quoteSummary.set(0)
        chart.set(0)
        timeseries.set(0)
        session.set(0)
        otherHttp.set(0)
        chartByRange.clear()
        bytesIn.set(0)
        http429.set(0)
        http5xx.set(0)
        sqlitePersistBatches.set(0)
        sqliteRawCaptures.set(0)
        sqliteRevisions.set(0)
    }

    companion object {
        /** Shared process meter for production wiring and optional test inspection. */
        val global = IoTrafficMeter()
    }
}

data class IoTrafficSnapshot(
    val quoteSummary: Long,
    val chart: Long,
    val timeseries: Long,
    val session: Long,
    val otherHttp: Long,
    val chartByRange: Map<String, Long>,
    val bytesIn: Long,
    val http429: Long,
    val http5xx: Long,
    val sqlitePersistBatches: Long,
    val sqliteRawCaptures: Long,
    val sqliteRevisions: Long,
) {
    val httpRequestsTotal: Long
        get() = quoteSummary + chart + timeseries + session + otherHttp

    val chartNonYear: Long
        get() = chartByRange.filterKeys { it != "Year" }.values.sum()

    fun toLogLine(): String =
        "http_total=$httpRequestsTotal quote=$quoteSummary chart=$chart chart_non_year=$chartNonYear " +
            "timeseries=$timeseries session=$session bytes_in=$bytesIn " +
            "sqlite_batches=$sqlitePersistBatches captures=$sqliteRawCaptures revisions=$sqliteRevisions " +
            "429=$http429 5xx=$http5xx ranges=$chartByRange"
}
