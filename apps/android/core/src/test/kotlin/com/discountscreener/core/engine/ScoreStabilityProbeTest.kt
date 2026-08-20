package com.discountscreener.core.engine

import com.discountscreener.core.math.robustCentre
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test

/**
 * Probe: how much of a symbol's score belongs to the symbol, and how much to what else loaded?
 *
 * Nothing is asserted here. The numbers are printed so the size of each effect can be read.
 */
class ScoreStabilityProbeTest {

    @Test
    fun probe_peer_population_moves_the_subject_score() {
        var subject = detail("SUBJ", forwardPeHundredths = 1_800, returnOnEquityBps = 1_500)
        var peers = (0..9).map {
            detail("P$it", forwardPeHundredths = 1_400 + it * 120, returnOnEquityBps = 1_000 + it * 200)
        }

        var full = computeSectorBenchmarks(peers + subject)["Technology"]
        var short = computeSectorBenchmarks(peers.drop(3) + subject)["Technology"]
        var floor = computeSectorBenchmarks(peers.take(3) + subject)["Technology"]

        println("PROBE benchFull=$full")
        println("PROBE benchShort=$short")
        println("PROBE benchFloor=$floor")
        listOf("full" to full, "short" to short, "floor" to floor).forEach { (name, benchmarks) ->
            var score = OpportunityEngine.scoreWithModel(
                detail = subject,
                summary = null,
                analysis = null,
                model = OpportunityScoringModel.AggressiveV4,
                sectorBenchmarks = benchmarks,
            )
            println(
                "PROBE $name fundamentals=${score.fundamentalsScore} composite=${score.compositeScore} " +
                    "coverage=${score.coverageCount}",
            )
        }
    }

    @Test
    fun probe_a_missing_bucket_moves_the_composite() {
        var buckets = listOf(60, 55, 50)
        (1..3).forEach { present ->
            var composite = OpportunityEngine.compositeScoreFor(
                model = OpportunityScoringModel.AggressiveV4,
                fundamentals = buckets.getOrNull(0),
                technical = if (present >= 2) buckets[1] else null,
                forecast = if (present >= 3) buckets[2] else null,
                regime = null,
                coverageCount = present,
                betaMillis = 1_000,
                betaHaircutMult = 1.0,
            )
            println("PROBE buckets=$present composite=$composite")
        }
    }

    @Test
    fun probe_the_market_read_landing_moves_every_stock() {
        var withoutRegime = OpportunityEngine.compositeScoreFor(
            model = OpportunityScoringModel.AggressiveV4,
            fundamentals = 60,
            technical = 55,
            forecast = 50,
            regime = null,
            coverageCount = 3,
            betaMillis = 1_000,
            betaHaircutMult = 1.0,
        )
        println("PROBE regimePending composite=$withoutRegime")
        listOf(30, 43, 55, 70).forEach { regime ->
            listOf(1.0, 1.3).forEach { haircut ->
                var composite = OpportunityEngine.compositeScoreFor(
                    model = OpportunityScoringModel.AggressiveV4,
                    fundamentals = 60,
                    technical = 55,
                    forecast = 50,
                    regime = regime,
                    coverageCount = 4,
                    betaMillis = 1_000,
                    betaHaircutMult = haircut,
                )
                println("PROBE regime=$regime haircut=$haircut composite=$composite move=${composite - withoutRegime}")
            }
        }
    }

    /**
     * The cohort arrives in network order, so the same sector is summed in a different order every
     * run. Floating-point addition is not associative; this asks whether that reaches the answer.
     */
    @Test
    fun probe_arrival_order_of_the_same_sector() {
        var values = (0..29).map { 1_000.0 + it * 37.7 + it % 3 * 0.31 }
        var centres = (1..200).map { seed ->
            robustCentre(values.shuffled(Random(seed)))
        }
        println("PROBE centreDistinct=${centres.distinct().size} values=${centres.distinct().take(4)}")
        println("PROBE centreRounded=${centres.mapNotNull { it?.roundToInt() }.distinct()}")

        var details = (0..29).map { detail("S$it", forwardPeHundredths = 1_400 + it * 41, returnOnEquityBps = 900 + it * 53) }
        var tables = (1..200).map { seed ->
            computeSectorBenchmarks(details.shuffled(Random(seed)))["Technology"]
        }
        println("PROBE tableDistinct=${tables.distinct().size} ${tables.distinct().take(3)}")
    }

    private fun detail(
        symbol: String,
        forwardPeHundredths: Int,
        returnOnEquityBps: Int,
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
            priceToBookHundredths = 300,
            returnOnEquityBps = returnOnEquityBps,
            enterpriseToEbitdaHundredths = 1_200,
            ebitdaDollars = 10_000_000_000L,
            totalDebtDollars = 20_000_000_000L,
            totalCashDollars = 8_000_000_000L,
            betaMillis = 1_000,
        ),
    )
}
