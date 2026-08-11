package com.discountscreener.android.data.debug

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.OpportunityRow
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.regime.RegimeScoreStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreExportTest {

    @Test
    fun the_header_names_every_column() {
        var header = ScoreExport.buildCsv(emptyList(), emptySet(), emptyMap(), emptyMap()).trim()

        assertEquals(
            "symbol,qualified,sector,fundamentals,technical,forecast,market,regime_status," +
                "composite_base,composite_final,coverage,beta_millis," +
                "forward_pe_hundredths,ev_ebitda_hundredths,price_to_book_hundredths," +
                "return_on_equity_bps,close_cents,ema20_cents,ema50_cents,ema200_cents",
            header,
        )
    }

    /**
     * The measurement is a claim about a population. A row silently missing from the file shrinks
     * that population without saying so, which is the one failure the correlation cannot survive —
     * so the count is asserted against the input, with a row that has neither detail nor summary.
     */
    @Test
    fun every_row_gets_a_line_even_with_no_detail_and_no_summary() {
        var rows = listOf(row("AAA"), row("BBB"), row("CCC"))
        var details = mapOf("AAA" to detail("AAA"), "BBB" to detail("BBB"))
        var summaries = mapOf("AAA" to summary())

        var lines = ScoreExport.buildCsv(rows, setOf("AAA"), details, summaries).trim().lines()

        assertEquals(rows.size + 1, lines.size)
    }

    /**
     * Qualification selects roughly one symbol in eight. The column is what lets the analysis
     * compare the cohort against that subset instead of quietly inheriting its range restriction.
     */
    @Test
    fun an_unqualified_row_is_written_and_marked_unqualified() {
        var rows = listOf(row("AAA"), row("BBB"))

        var cells = ScoreExport.buildCsv(rows, setOf("AAA"), emptyMap(), emptyMap())
            .trim().lines()[2].split(",")

        assertEquals("0", cells[1])
    }

    @Test
    fun a_row_carries_its_scores_and_the_inputs_behind_them() {
        var rows = listOf(row("AAA"))
        var details = mapOf("AAA" to detail("AAA"))

        var line = ScoreExport
            .buildCsv(rows, setOf("AAA"), details, mapOf("AAA" to summary()))
            .trim().lines()[1]

        assertEquals(
            "\"AAA\",1,\"Technology\",40,-6,72,43,\"Included\",45,52,4,1200," +
                "2500,1800,900,1500,19950,19000,18500,17000",
            line,
        )
    }

    /** A bucket that is absent must read as empty, never as the zero a bucket can really score. */
    @Test
    fun a_missing_bucket_is_an_empty_cell_not_a_zero() {
        var rows = listOf(
            row("AAA").copy(regimeScore = null, regimeStatus = RegimeScoreStatus.Unavailable),
        )

        var cells = ScoreExport.buildCsv(rows, emptySet(), emptyMap(), emptyMap())
            .trim().lines()[1].split(",")

        assertEquals("", cells[6])
    }

    private fun row(symbol: String) = OpportunityRow(
        symbol = symbol,
        marketPriceCents = 19_950,
        intrinsicValueCents = 25_000,
        gapBps = 2_000,
        confidence = ConfidenceBand.High,
        isWatched = false,
        fundamentalsScore = 40,
        technicalScore = -6,
        forecastScore = 72,
        regimeScore = 43,
        compositeScore = 52,
        compositeScoreBase = 45,
        coverageCount = 4,
        regimeStatus = RegimeScoreStatus.Included,
    )

    private fun detail(symbol: String) = SymbolDetail(
        symbol = symbol,
        profitable = true,
        marketPriceCents = 19_950,
        intrinsicValueCents = 25_000,
        gapBps = 2_000,
        minimumGapBps = 1_000,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Supportive,
        externalSignalMaxAgeSeconds = 86_400,
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
        fundamentals = FundamentalSnapshot(
            symbol = symbol,
            sectorName = "Technology",
            forwardPeHundredths = 2_500,
            priceToBookHundredths = 900,
            returnOnEquityBps = 1_500,
            enterpriseToEbitdaHundredths = 1_800,
            betaMillis = 1_200,
        ),
    )

    private fun summary() = ChartRangeSummary(
        range = ChartRange.Year,
        capturedAt = 0,
        candleCount = 252,
        latestCloseCents = 19_950,
        ema20Cents = 19_000,
        ema50Cents = 18_500,
        ema200Cents = 17_000,
    )
}
