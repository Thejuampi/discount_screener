package com.discountscreener.android.data.debug

import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.OpportunityRow
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.regime.RegimeCauseFactor
import com.discountscreener.core.regime.RegimeFitTerm

/**
 * A CSV of what the engine scored, for offline analysis.
 *
 * This exists to answer one question that reading the code cannot: how much do the four buckets
 * repeat each other? `RegimeFit.valueScore` reads the same three multiples the fundamentals bucket
 * reads, and `RegimeFit.trendScore` reads the same EMAs the technicals bucket reads. That the
 * inputs are shared is a fact of the source. How far that carries into the *scores* is a
 * measurement, and it needs the whole population, not a screenshot.
 *
 * So the export carries both: the four bucket scores, and the raw inputs the duplication is
 * suspected in. The correlation of the scores says how big the effect is; the correlation of the
 * inputs says whether the shared inputs are the cause of it.
 *
 * **The population is the cohort, not the Opportunities list.** Qualification keeps roughly one
 * symbol in eight, and a correlation over survivors alone is range-restricted — it would report a
 * smaller overlap than the engine really has, and the gate this measurement feeds would then be
 * biased toward the wrong answer. Every candidate is written, with `qualified` as a column, so the
 * analysis can show the attenuation instead of inheriting it.
 *
 * Debug surface only. Nothing here is on a render path and nothing here is shipped behaviour.
 */
object ScoreExport {

    /**
     * The market bucket's terms, each written as a signed value and a weight.
     *
     * The bucket is a weighted mean of these, so a correlation against the bucket alone cannot say
     * which term carries which sign. Trend and Extension read the same stretch with opposite signs,
     * and the regime stance decides which of the two wins by weight — that arbitration is invisible
     * in the composite and is the whole reason these columns exist.
     */
    private val TERM_FACTORS = RegimeCauseFactor.entries.filter {
        it != RegimeCauseFactor.GeneralFit && it != RegimeCauseFactor.Neutral
    }

    /**
     * Column order is the file's contract with the analysis in `lab/`. Append, never reorder.
     */
    private val COLUMNS = listOf(
        "symbol",
        "qualified",
        "sector",
        "fundamentals",
        "technical",
        "forecast",
        "market",
        "regime_status",
        "composite_base",
        "composite_final",
        "coverage",
        "beta_millis",
        "forward_pe_hundredths",
        "ev_ebitda_hundredths",
        "price_to_book_hundredths",
        "return_on_equity_bps",
        "close_cents",
        "ema20_cents",
        "ema50_cents",
        "ema200_cents",
        "stance",
    ) + TERM_FACTORS.flatMap { listOf("t_${it.legacyTag.lowercase()}", "w_${it.legacyTag.lowercase()}") } +
        listOf("industry", "fcf_yield_bps")

    /**
     * One header line, then one line per row in [rows], in the order the engine ranked them.
     *
     * A symbol with no detail or no daily summary still gets a line, with its input columns empty.
     * Dropping it would quietly shrink the population the correlation is computed over, and a
     * measurement that silently loses rows is worse than one that reports gaps.
     */
    fun buildCsv(
        rows: List<OpportunityRow>,
        qualifiedSymbols: Set<String>,
        detailsBySymbol: Map<String, SymbolDetail>,
        dailySummariesBySymbol: Map<String, ChartRangeSummary>,
        termsBySymbol: Map<String, List<RegimeFitTerm>> = emptyMap(),
        stance: String? = null,
    ): String {
        var lines = mutableListOf(COLUMNS.joinToString(","))
        for (row in rows) {
            var fundamentals = detailsBySymbol[row.symbol]?.fundamentals
            var summary = dailySummariesBySymbol[row.symbol]
            var terms = termsBySymbol[row.symbol].orEmpty().associateBy { it.factor }
            lines += (listOf(
                quote(row.symbol),
                if (row.symbol in qualifiedSymbols) "1" else "0",
                quote(fundamentals?.sectorName),
                cell(row.fundamentalsScore),
                cell(row.technicalScore),
                cell(row.forecastScore),
                cell(row.regimeScore),
                quote(row.regimeStatus.name),
                cell(row.compositeScoreBase),
                cell(row.compositeScore),
                cell(row.coverageCount),
                cell(fundamentals?.betaMillis),
                cell(fundamentals?.forwardPeHundredths),
                cell(fundamentals?.enterpriseToEbitdaHundredths),
                cell(fundamentals?.priceToBookHundredths),
                cell(fundamentals?.returnOnEquityBps),
                cell(summary?.latestCloseCents),
                cell(summary?.ema20Cents),
                cell(summary?.ema50Cents),
                cell(summary?.ema200Cents),
                quote(stance),
            ) + TERM_FACTORS.flatMap { factor ->
                // A term the symbol has no input for is absent, and absent is an empty pair of
                // cells. A zero would read as "the feature was measured and came out neutral".
                listOf(cell(terms[factor]?.signed), cell(terms[factor]?.weight))
            } + listOf(
                quote(fundamentals?.industryName),
                cell(row.fundamentalsFactors.firstOrNull { it.token.startsWith("FCFy") }?.inputBps),
            )).joinToString(",")
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    /** A missing number is an empty cell, never a zero — zero is a score a bucket can really have. */
    private fun cell(value: Number?): String = value?.toString() ?: ""

    private fun quote(value: String?): String =
        if (value == null) "" else "\"" + value.replace("\"", "\"\"") + "\""
}
