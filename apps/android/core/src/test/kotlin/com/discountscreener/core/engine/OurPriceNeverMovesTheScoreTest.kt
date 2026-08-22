package com.discountscreener.core.engine

import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Our price is a reference number, never a vote.
 *
 * The DCF is experimental, so it must not reach the ranking. This measures that rule instead of
 * reading it: the same symbol is scored with no DCF, with a wild DCF and with a crushed one. Every
 * scoring model must return the same buckets and the same composite for all three.
 */
class OurPriceNeverMovesTheScoreTest {

    @Test
    fun no_scoring_model_moves_its_score_when_our_price_changes() {
        var offenders = OpportunityScoringModel.entries.filter { model ->
            signaturesFor(model).distinct().size > 1
        }.map { it.name }

        assertEquals(emptyList(), offenders)
    }

    @Test
    fun the_probe_changes_our_price_by_enough_to_be_seen() {
        var bases = dcfVariants.mapNotNull { it?.baseIntrinsicValueCents }

        assertEquals(listOf(2_000_000L, 100L), bases)
    }

    private fun signaturesFor(model: OpportunityScoringModel) = dcfVariants.map { analysis ->
        var score = OpportunityEngine.scoreWithModel(
            detail = detail,
            summary = null,
            analysis = analysis,
            model = model,
        )
        listOf(
            score.fundamentalsScore,
            score.technicalScore,
            score.forecastScore,
            score.regimeScore,
            score.compositeScore,
            score.compositeScoreBase,
            score.coverageCount,
        ).joinToString("/")
    }

    private val dcfVariants = listOf(
        null,
        dcf(bearCents = 1_500_000, baseCents = 2_000_000, bullCents = 2_500_000),
        dcf(bearCents = 50, baseCents = 100, bullCents = 150),
    )

    private fun dcf(bearCents: Long, baseCents: Long, bullCents: Long) = DcfAnalysis(
        bearIntrinsicValueCents = bearCents,
        baseIntrinsicValueCents = baseCents,
        bullIntrinsicValueCents = bullCents,
        waccBps = 900,
        baseGrowthBps = 400,
        netDebtDollars = 12_000_000_000L,
        source = DcfSource.YahooFinance,
    )

    private val detail = SymbolDetail(
        symbol = "SUBJ",
        profitable = true,
        marketPriceCents = 19_950,
        intrinsicValueCents = 25_000,
        gapBps = 2_000,
        minimumGapBps = 1_000,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Supportive,
        externalSignalFairValueCents = 25_000,
        externalSignalLowFairValueCents = 21_000,
        externalSignalHighFairValueCents = 30_000,
        weightedExternalSignalFairValueCents = 25_400,
        externalSignalMaxAgeSeconds = 86_400,
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
        fundamentals = FundamentalSnapshot(
            symbol = "SUBJ",
            sectorName = "Technology",
            forwardPeHundredths = 1_800,
            priceToBookHundredths = 300,
            returnOnEquityBps = 1_500,
            enterpriseToEbitdaHundredths = 1_200,
            ebitdaDollars = 10_000_000_000L,
            totalDebtDollars = 20_000_000_000L,
            totalCashDollars = 8_000_000_000L,
            betaMillis = 1_000,
        ),
    )
}
