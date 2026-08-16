package com.discountscreener.core.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Versioned ERP table. Default school is the Damodaran implied *index* premium.
 * Firm-level implied cost of capital is not a school and must not be added.
 */
enum class ErpSchool(val wireName: String) {
    ImpliedIndex("implied_index"),
    KrollRecommended("kroll_recommended"),
    Bootstrap("bootstrap"),
}

data class ErpResolution(
    val school: ErpSchool,
    val erpBps: Int,
    val asOfDate: String,
    val source: String,
    val stale: Boolean,
    val policyVersion: String = ErpPolicy.VERSION,
)

private data class ErpRow(
    val asOfDate: String,
    val erpBps: Int,
    val source: String,
)

object ErpPolicy {
    const val VERSION = "erp-policy/1"
    val DEFAULT_SCHOOL = ErpSchool.ImpliedIndex

    /** A row older than this at the valuation date is dated, so stay provisional. */
    const val FRESH_DAYS = 180L

    fun resolve(school: ErpSchool, asOfEpochMillis: Long): ErpResolution {
        if (school == ErpSchool.Bootstrap) {
            return ErpResolution(
                school = ErpSchool.Bootstrap,
                erpBps = DEFAULT_ERP_BPS,
                asOfDate = "bootstrap",
                source = "bootstrap",
                stale = true,
            )
        }
        var asOf = Instant.ofEpochMilli(asOfEpochMillis).atZone(ZoneOffset.UTC).toLocalDate()
        var rows = rowsFor(school)
        var row = rows.lastOrNull { LocalDate.parse(it.asOfDate) <= asOf } ?: rows.first()
        var ageDays = ChronoUnit.DAYS.between(LocalDate.parse(row.asOfDate), asOf)
        return ErpResolution(
            school = school,
            erpBps = row.erpBps,
            asOfDate = row.asOfDate,
            source = row.source,
            stale = ageDays > FRESH_DAYS,
        )
    }

    private fun rowsFor(school: ErpSchool): List<ErpRow> = when (school) {
        ErpSchool.ImpliedIndex -> IMPLIED_INDEX
        ErpSchool.KrollRecommended -> KROLL
        ErpSchool.Bootstrap -> emptyList()
    }
}

/**
 * Damodaran US implied ERP on the S&P 500 (index), not a firm ICC.
 * July 2026: https://pages.stern.nyu.edu/~adamodar/pc/implprem/ERPJuly26.xlsx (4.42%).
 */
private val IMPLIED_INDEX = listOf(
    ErpRow("2026-07-01", 442, "damodaran_implied_spx_2026-07"),
)

/**
 * Kroll recommended US ERP. 5.0% from 2025-09-02; still listed April 2026.
 */
private val KROLL = listOf(
    ErpRow("2025-09-02", 500, "kroll_recommended_us_2025-09-02"),
)
