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
    val FRESH_DAYS: Long
        get() = ValuationPolicy.current.erp.freshDays

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
        ErpSchool.ImpliedIndex -> ValuationPolicy.current.erp.impliedIndex.map { it.toErpRow() }
        ErpSchool.KrollRecommended -> ValuationPolicy.current.erp.kroll.map { it.toErpRow() }
        ErpSchool.Bootstrap -> emptyList()
    }
}

private fun DatedRateRow.toErpRow(): ErpRow = ErpRow(asOfDate, valueBps, source)
