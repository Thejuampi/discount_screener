package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ScoreFactorComparison
import com.discountscreener.core.model.ScoreFactorValueKind
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * V4's fundamentals bucket reads a symbol against its own sector.
 *
 * The defect being fixed is that V3 puts a utility and a chip maker on one P/E band, so it ranks
 * industries before it ranks companies. Each test below fixes one part of the replacement: that the
 * sector is read at all, that return on equity is read with a band of a different shape, and that a
 * sector too small to have a level falls back to the old rule and says so in the label.
 */
class AggressiveV4FundamentalsTest {

    /**
     * The same three multiples, twice, in two sectors. Nothing about the company changes between
     * the two rows — only the level its peers trade at — and the bucket moves the whole way from
     * one end of the panel to the other.
     *
     *  expensive sector  centres 4000 / 2400 / 600  bands [2800,6000] [1680,3600] [420,900]
     *                    2000 / 1200 / 300 are all under the cheap edge  → panel +1 → 24/110
     *  cheap sector      centres 1000 /  600 / 150  bands  [700,1500]  [420,900]  [105,225]
     *                    the same three are all over the rich edge       → panel −1 → −24/110
     */
    @Test
    fun a_cheap_symbol_in_an_expensive_sector_outscores_the_same_multiples_in_a_cheap_sector() {
        var expensive = score(benchmarks(forwardPe = 4_000, evEbitda = 2_400, priceToBook = 600))
        var cheap = score(benchmarks(forwardPe = 1_000, evEbitda = 600, priceToBook = 150))

        assertEquals(listOf(22, -22), listOf(expensive.first, cheap.first))
    }

    /**
     * A sector with no benchmark scores on V3's absolute band, and the label says `Mult` with no
     * marker. Two rows in one list scored by two rules is fine; two rows that do not say which rule
     * scored them is not.
     *
     *  2000 in [800,3500] → +0.1111,  1200 in [600,2000] → +0.1429,  300 in [100,500] → 0.0
     *  median +0.1111 × 24 / 110 → 2.42 → 2
     */
    @Test
    fun a_symbol_with_no_sector_benchmark_falls_back_to_the_absolute_band_and_says_so() {
        var fallback = score(null)

        assertEquals(
            2 to listOf("Mult+"),
            fallback.score to fallback.termSignals(),
        )
    }

    /** The other half of the same claim: a sector-scored panel is marked, and marked once. */
    @Test
    fun a_sector_scored_panel_carries_the_marker() {
        var scored = score(benchmarks(forwardPe = 4_000, evEbitda = 2_400, priceToBook = 600))

        assertEquals(
            listOf("Mult§"),
            scored.termSignals().map { it.trimEnd('+', '-') },
        )
    }

    /**
     * The Score tab has to print both sides of the comparison. The panel already scored three
     * multiples against three centres; those pairs have to survive on the factor or the screen
     * can only show the points.
     */
    @Test
    fun a_sector_panel_keeps_each_multiple_against_its_sector_centre() {
        var factor = score(benchmarks(forwardPe = 4_000, evEbitda = 2_400, priceToBook = 600))
            .factors
            .single { it.key == "Mult§" }

        assertEquals(
            listOf(
                ScoreFactorComparison(2_000, ScoreFactorValueKind.Multiple, "P/E", 4_000),
                ScoreFactorComparison(1_200, ScoreFactorValueKind.Multiple, "EV/EBITDA", 2_400),
                ScoreFactorComparison(300, ScoreFactorValueKind.Multiple, "P/B", 600),
            ),
            factor.comparisons,
        )
    }

    @Test
    fun absolute_return_on_equity_keeps_the_rate_against_the_policy_band() {
        var factor = OpportunityEngine.aggressiveV4FundamentalsScore(
            detail("SUT", returnOnEquityBps = 1_000),
            null,
        ).factors.single { it.key == "ROE" }

        assertEquals(
            listOf(
                ScoreFactorComparison(
                    1_000,
                    ScoreFactorValueKind.Percent,
                    referenceLow = 0,
                    referenceHigh = 2_000,
                ),
            ),
            factor.comparisons,
        )
    }

    @Test
    fun return_on_equity_keeps_the_symbol_rate_against_the_sector_rate() {
        var factor = OpportunityEngine.aggressiveV4FundamentalsScore(
            detail("SUT", returnOnEquityBps = 1_000),
            benchmarks(returnOnEquityBps = 2_000),
        ).factors.single { it.key == "ROE§" }

        assertEquals(
            listOf(ScoreFactorComparison(1_000, ScoreFactorValueKind.Percent, reference = 2_000)),
            factor.comparisons,
        )
    }

    /**
     * Four members is not a sector, so the benchmark the engine computes for it is null and the row
     * lands on the absolute band — through the real [computeSectorBenchmarks], not a hand-made
     * null. The scoring path must survive a sector it cannot use, and produce the old answer.
     */
    @Test
    fun a_sector_of_four_members_falls_back_to_the_absolute_band_without_throwing() {
        var thinSector = listOf(1_800, 1_900, 2_000, 2_100)
            .mapIndexed { index, pe -> detail("PEER$index", forwardPeHundredths = pe) }

        assertEquals(score(null).first, score(computeSectorBenchmarks(thinSector)["Technology"]).first)
    }

    /**
     * Return on equity is read against the sector too, and the sector moves it.
     *
     *  absolute  1000 bps in [0,2000]        → 0.0  × 16 / 110 → 0
     *  sector    centre 2000, band [1500,3500]; 1000 is under the low edge → −1 × 16 / 110 → −15
     *
     * Twenty per cent on equity is good in most of the market and ordinary among the companies that
     * earn thirty. That is the whole point of the bucket.
     */
    @Test
    fun return_on_equity_is_read_against_the_sector_level() {
        var absolute = scoreReturnOnEquity(1_000, sectorReturnOnEquityBps = null)
        var sectorRelative = scoreReturnOnEquity(1_000, sectorReturnOnEquityBps = 2_000)

        assertEquals(listOf(0, -15), listOf(absolute, sectorRelative))
    }

    /**
     * The return-on-equity band is **additive**, and this is the test that would catch it being
     * made multiplicative like the three price multiples.
     *
     * A sector centred at −300 bps is a sector of loss makers, and it is an ordinary thing to be.
     * The additive band is [−800, +1200] and it orders the two symbols correctly. A `× 0.7 / × 1.5`
     * band on the same centre would be [−210, −450] — upper below lower — which is not a band at
     * all; `smoothRamp` refuses it and the row would throw rather than score.
     */
    @Test
    fun the_return_on_equity_band_is_additive_so_a_sector_centred_below_zero_still_orders() {
        var weak = scoreReturnOnEquity(-800, sectorReturnOnEquityBps = -300)
        var strong = scoreReturnOnEquity(1_200, sectorReturnOnEquityBps = -300)

        assertEquals(listOf(-15, 15), listOf(weak, strong))
    }

    /**
     * Where the two edges of the return-on-equity band actually sit.
     *
     * The two tests above put every observation outside the band, so they pin its sign and not its
     * width: an edge moved a few hundred basis points would keep them green. These observations sit
     * just inside each edge, where a moved edge changes the score.
     *
     *  centre 2000 → band [1500, 3500]
     *  1550 → 2 × 50 / 2000 − 1 = −0.95 × 16 / 110 → −14
     *  3400 → 2 × 1900 / 2000 − 1 = +0.90 × 16 / 110 → +13
     */
    @Test
    fun the_return_on_equity_band_runs_five_hundred_below_the_sector_to_fifteen_hundred_above() {
        var justInsideTheLowEdge = scoreReturnOnEquity(1_550, sectorReturnOnEquityBps = 2_000)
        var justInsideTheHighEdge = scoreReturnOnEquity(3_400, sectorReturnOnEquityBps = 2_000)

        assertEquals(listOf(-14, 13), listOf(justInsideTheLowEdge, justInsideTheHighEdge))
    }

    /**
     * The benchmarks reach the bucket through the list, and this is the only test that says so.
     *
     * Everything above calls the scorer directly, so all of it would stay green if the context
     * carried the map and the row never looked its sector up. The seam is a map lookup by a name
     * that lives two objects away from the call, and a seam nothing crosses is a feature nothing
     * ships.
     */
    @Test
    fun the_list_reads_a_symbols_sector_from_the_context() {
        var engine = ReportingEngine()
        engine.ingestSnapshot(
            MarketSnapshot(symbol = "SUT", profitable = true, marketPriceCents = 8_000, intrinsicValueCents = 10_000),
        )
        engine.ingestFundamentals(detail("SUT", 2_000, 1_200, 300).fundamentals!!)

        var context = OpportunityContext(
            scoringModel = OpportunityScoringModel.AggressiveV4,
            sectorBenchmarks = mapOf(
                "Technology" to benchmarks(forwardPe = 4_000, evEbitda = 2_400, priceToBook = 600),
            ),
        )

        assertEquals(22, OpportunityEngine.buildRows(engine, context).single().fundamentalsScore)
    }

    /**
     * The cycle-peak penalty reaches the bucket, and it reaches it as a subtraction.
     *
     * Both arms hold the same company: the same five years of revenue, the same five years of net
     * income, the same return on equity. Only the industry key differs, so the gap between the two
     * scores is the penalty and nothing else.
     */
    @Test
    fun a_peak_margin_in_a_through_cycle_industry_scores_below_the_same_company_outside_one() {
        var throughCycle = cycleScore(industryKey = "oil-gas-integrated").first
        var elsewhere = cycleScore(industryKey = "software-infrastructure").first

        assertEquals(4, elsewhere!! - throughCycle!!)
    }

    /** And the list says why, in points, so a reader can see what the mark cost. */
    @Test
    fun the_penalty_names_itself_in_the_factors() {
        var factor = cycleScore(industryKey = "oil-gas-integrated")
            .factors
            .single { it.key == CYCLE_PEAK_LABEL }

        assertEquals(-4, factor.bucketPoints)
    }

    private fun cycleScore(industryKey: String) = OpportunityEngine.aggressiveV4FundamentalsScore(
        detail("SUT", returnOnEquityBps = 2_000).let { base ->
            base.copy(
                fundamentals = base.fundamentals!!.copy(sectorKey = "energy", industryKey = industryKey),
            )
        },
        null,
        FundamentalTimeseries(
            revenue = CYCLE_YEARS.zip(listOf(285.0, 413.0, 344.0, 320.0, 420.0)) { year, value ->
                AnnualReportedValue(year, value)
            },
            netIncome = CYCLE_YEARS.zip(listOf(23.0, 55.7, 36.0, 33.7, 50.0)) { year, value ->
                AnnualReportedValue(year, value)
            },
        ),
    )

    private companion object {
        val CYCLE_YEARS = listOf("2021-12-31", "2022-12-31", "2023-12-31", "2024-12-31", "2025-12-31")
    }

    /** Score the three multiples alone, so the panel is the only term the bucket holds. */
    private fun score(sectorBenchmarks: SectorBenchmarks?) = OpportunityEngine
        .aggressiveV4FundamentalsScore(
            detail(
                "SUT",
                forwardPeHundredths = 2_000,
                enterpriseToEbitdaHundredths = 1_200,
                priceToBookHundredths = 300,
            ),
            sectorBenchmarks,
        )

    /** Score return on equity alone, for the same reason. */
    private fun scoreReturnOnEquity(observedBps: Int, sectorReturnOnEquityBps: Int?) = OpportunityEngine
        .aggressiveV4FundamentalsScore(
            detail("SUT", returnOnEquityBps = observedBps),
            sectorReturnOnEquityBps?.let { benchmarks(returnOnEquityBps = it) },
        ).first

    private fun benchmarks(
        forwardPe: Int? = null,
        evEbitda: Int? = null,
        priceToBook: Int? = null,
        returnOnEquityBps: Int? = null,
        netDebtToEbitdaHundredths: Int? = null,
    ) = SectorBenchmarks(
        forwardPeHundredths = forwardPe,
        enterpriseToEbitdaHundredths = evEbitda,
        priceToBookHundredths = priceToBook,
        returnOnEquityBps = returnOnEquityBps,
        netDebtToEbitdaHundredths = netDebtToEbitdaHundredths,
    )

    private fun detail(
        symbol: String,
        forwardPeHundredths: Int? = null,
        enterpriseToEbitdaHundredths: Int? = null,
        priceToBookHundredths: Int? = null,
        returnOnEquityBps: Int? = null,
    ) = SymbolDetail(
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
            forwardPeHundredths = forwardPeHundredths,
            enterpriseToEbitdaHundredths = enterpriseToEbitdaHundredths,
            priceToBookHundredths = priceToBookHundredths,
            returnOnEquityBps = returnOnEquityBps,
        ),
    )
}
