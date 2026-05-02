package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.ViewFilter
import kotlin.test.Test
import kotlin.test.assertEquals

class ReportingEngineTest {
    @Test
    fun qualifies_discounted_symbol_without_external_signal() {
        val engine = ReportingEngine()
        engine.ingestSnapshot(snapshot("ACME", true, 8_000, 10_000))

        assertEquals(
            Triple(QualificationStatus.Qualified, ExternalSignalStatus.Missing, ConfidenceBand.Provisional),
            engine.detail("ACME")?.let { Triple(it.qualification, it.externalStatus, it.confidence) },
        )
    }

    @Test
    fun supportive_external_signal_upgrades_confidence() {
        val engine = ReportingEngine()
        engine.ingestSnapshot(snapshot("ACME", true, 8_000, 10_000))
        engine.ingestExternal(external("ACME", 12_000, 5, analystOpinionCount = 5))

        assertEquals(ConfidenceBand.High, engine.candidate("ACME")?.confidence)
    }

    @Test
    fun supportive_external_signal_with_thin_analyst_coverage_stays_provisional() {
        val engine = ReportingEngine()
        engine.ingestSnapshot(snapshot("ACME", true, 8_000, 10_000))
        engine.ingestExternal(external("ACME", 12_000, 5, analystOpinionCount = 1))

        assertEquals(ConfidenceBand.Provisional, engine.candidate("ACME")?.confidence)
    }

    @Test
    fun checked_gap_and_upside_bps_use_distinct_denominators() {
        assertEquals(3_585, checkedGapBps(marketPriceCents = 17_270, fairValueCents = 26_923))
        assertEquals(5_589, checkedUpsideBps(marketPriceCents = 17_270, fairValueCents = 26_923))
    }

    @Test
    fun filters_rows_by_watchlist_and_query() {
        val engine = ReportingEngine()
        engine.ingestSnapshot(snapshot("ALFA", true, 7_000, 10_000))
        engine.ingestSnapshot(snapshot("ALGO", true, 8_000, 10_000))
        engine.ingestSnapshot(snapshot("BETA", true, 6_000, 10_000))
        engine.toggleWatchlist("ALFA")
        engine.toggleWatchlist("ALGO")

        assertEquals(
            listOf("ALFA", "ALGO"),
            engine.filteredRows(
                filter = ViewFilter(query = "AL", watchlistOnly = true),
                limit = 10,
            ).map { it.symbol },
        )
    }

    @Test
    fun opportunity_rows_rank_by_composite_signals() {
        val engine = ReportingEngine()
        engine.ingestSnapshot(snapshot("STRONG", true, 1_000, 2_500))
        engine.ingestExternal(
            ExternalValuationSignal(
                symbol = "STRONG",
                fairValueCents = 2_300,
                ageSeconds = 0,
                lowFairValueCents = 1_900,
                highFairValueCents = 2_400,
                analystOpinionCount = 12,
                recommendationMeanHundredths = 140,
                weightedFairValueCents = 2_400,
                weightedAnalystCount = 9,
            ),
        )
        engine.ingestFundamentals(
            FundamentalSnapshot(
                symbol = "STRONG",
                freeCashFlowDollars = 200_000_000,
                operatingCashFlowDollars = 250_000_000,
                returnOnEquityBps = 2_500,
                debtToEquityHundredths = 60,
                totalCashDollars = 800_000_000,
                totalDebtDollars = 100_000_000,
                earningsGrowthBps = 1_500,
            ),
        )

        engine.ingestSnapshot(snapshot("WEAK", true, 1_000, 2_700))
        engine.ingestExternal(
            ExternalValuationSignal(
                symbol = "WEAK",
                fairValueCents = 1_200,
                ageSeconds = 0,
                lowFairValueCents = 1_000,
                highFairValueCents = 1_300,
                analystOpinionCount = 3,
                recommendationMeanHundredths = 260,
            ),
        )
        engine.ingestFundamentals(
            FundamentalSnapshot(
                symbol = "WEAK",
                freeCashFlowDollars = -10_000_000,
                operatingCashFlowDollars = -5_000_000,
                returnOnEquityBps = 200,
                debtToEquityHundredths = 220,
                totalCashDollars = 20_000_000,
                totalDebtDollars = 200_000_000,
                earningsGrowthBps = -700,
            ),
        )

        val rows = OpportunityEngine.buildRows(
            engine,
            OpportunityContext(
                chartSummariesBySymbol = mapOf(
                    "STRONG" to mapOf(
                        ChartRange.Year to summary(2_450, 2_100, 1_900, 1_700, 220, 120, 100),
                    ),
                    "WEAK" to mapOf(
                        ChartRange.Year to summary(1_050, 1_200, 1_250, 1_350, -50, 20, -70),
                    ),
                ),
            ),
        )

        assertEquals(listOf("STRONG", "WEAK"), rows.take(2).map { it.symbol })
    }

    @Test
    fun opportunity_scoring_rules_match_terminal_behavior() {
        val detail = engineDetail()

        assertEquals(
            Pair(5, listOf("FCF+", "OCF+", "ROE>10", "Balance", "Growth+")),
            OpportunityEngine.scoreFundamentals(detail),
        )
        assertEquals(
            Pair(5, listOf(">EMA20", ">EMA50", ">EMA200", "EMA20>50", "MACD+")),
            OpportunityEngine.scoreTechnicals(summary(2_450, 2_100, 1_900, 1_700, 220, 120, 100)),
        )
        assertEquals(
            Pair(5, listOf("Supportive", "5+Analysts", "Rec<=2.0", "Weighted+", "DCF+")),
            OpportunityEngine.scoreForecasts(
                detail,
                com.discountscreener.core.model.DcfAnalysis(
                    bearIntrinsicValueCents = 25_000,
                    baseIntrinsicValueCents = 32_000,
                    bullIntrinsicValueCents = 38_000,
                    waccBps = 900,
                    baseGrowthBps = 1_200,
                    netDebtDollars = 0,
                ),
            ),
        )
    }

    @Test
    fun aggressive_model_rewards_high_upside_and_trend_more_than_legacy() {
        val detail = engineDetail()
        val summary = summary(2_450, 2_100, 1_900, 1_700, 220, 120, 100)
        val analysis = com.discountscreener.core.model.DcfAnalysis(
            bearIntrinsicValueCents = 25_000,
            baseIntrinsicValueCents = 32_000,
            bullIntrinsicValueCents = 38_000,
            waccBps = 900,
            baseGrowthBps = 1_200,
            netDebtDollars = 0,
        )

        assertEquals(
            15,
            OpportunityEngine.scoreWithModel(
                detail = detail,
                summary = summary,
                analysis = analysis,
                model = OpportunityScoringModel.Legacy,
            ).compositeScore,
        )
        assertEquals(
            27,
            OpportunityEngine.scoreWithModel(
                detail = detail,
                summary = summary,
                analysis = analysis,
                model = OpportunityScoringModel.Aggressive,
            ).compositeScore,
        )
    }

    @Test
    fun aggressive_model_penalizes_broken_balance_sheet_and_bearish_setup() {
        val detail = engineDetail().copy(
            externalStatus = ExternalSignalStatus.Divergent,
            weightedExternalSignalFairValueCents = 15_000,
            analystOpinionCount = 2,
            recommendationMeanHundredths = 340,
            fundamentals = FundamentalSnapshot(
                symbol = "NVDA",
                freeCashFlowDollars = -10,
                operatingCashFlowDollars = -10,
                returnOnEquityBps = -500,
                debtToEquityHundredths = 240,
                totalCashDollars = 10_000_000,
                totalDebtDollars = 500_000_000,
                earningsGrowthBps = -900,
            ),
        )
        val summary = summary(1_000, 1_100, 1_200, 1_300, -120, 10, -130)
        val analysis = com.discountscreener.core.model.DcfAnalysis(
            bearIntrinsicValueCents = 8_500,
            baseIntrinsicValueCents = 9_000,
            bullIntrinsicValueCents = 9_500,
            waccBps = 900,
            baseGrowthBps = 1_200,
            netDebtDollars = 0,
        )

        assertEquals(
            -22,
            OpportunityEngine.scoreWithModel(
                detail = detail,
                summary = summary,
                analysis = analysis,
                model = OpportunityScoringModel.Aggressive,
            ).compositeScore,
        )
    }

    private fun snapshot(symbol: String, profitable: Boolean, marketPriceCents: Long, intrinsicValueCents: Long) =
        MarketSnapshot(symbol = symbol, profitable = profitable, marketPriceCents = marketPriceCents, intrinsicValueCents = intrinsicValueCents)

    private fun external(
        symbol: String,
        fairValueCents: Long,
        ageSeconds: Long,
        analystOpinionCount: Int? = null,
    ) = ExternalValuationSignal(
        symbol = symbol,
        fairValueCents = fairValueCents,
        ageSeconds = ageSeconds,
        analystOpinionCount = analystOpinionCount,
    )

    private fun summary(
        latest: Long,
        ema20: Long?,
        ema50: Long?,
        ema200: Long?,
        macd: Long?,
        signal: Long?,
        histogram: Long?,
    ) = ChartRangeSummary(
        range = ChartRange.Year,
        capturedAt = 0,
        candleCount = 52,
        latestCloseCents = latest,
        ema20Cents = ema20,
        ema50Cents = ema50,
        ema200Cents = ema200,
        macdCents = macd,
        signalCents = signal,
        histogramCents = histogram,
    )

    private fun engineDetail() = ReportingEngine().apply {
        ingestSnapshot(snapshot("NVDA", true, 17_270, 26_923))
        ingestExternal(
            ExternalValuationSignal(
                symbol = "NVDA",
                fairValueCents = 26_500,
                ageSeconds = 0,
                lowFairValueCents = 18_500,
                highFairValueCents = 32_000,
                analystOpinionCount = 42,
                recommendationMeanHundredths = 185,
                strongBuyCount = 20,
                buyCount = 10,
                holdCount = 8,
                sellCount = 3,
                strongSellCount = 1,
                weightedFairValueCents = 27_200,
                weightedAnalystCount = 9,
            ),
        )
        ingestFundamentals(
            FundamentalSnapshot(
                symbol = "NVDA",
                freeCashFlowDollars = 200_000_000,
                operatingCashFlowDollars = 250_000_000,
                returnOnEquityBps = 2_500,
                debtToEquityHundredths = 40,
                totalCashDollars = 500_000_000,
                totalDebtDollars = 100_000_000,
                earningsGrowthBps = 1_400,
            ),
        )
    }.detail("NVDA")!!
}
