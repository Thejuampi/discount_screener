package com.discountscreener.core.engine

import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.regime.confidentRegime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The market dimension is additive: switched off, every score is the score the app produced before
 * the dimension existed.
 *
 * Reading the code gives half of that for free — `scoreWithModel` returns `compositeBase` outright
 * unless the fit is Included, and `compositeBase` passes `regime = null` and `betaHaircutMult =
 * 1.0` — so the off path cannot reach the regime terms. What reading cannot give is that the
 * three-bucket arithmetic *itself* stayed put, which is the half that actually moved once already:
 * the dimension shipped with the Android default flipped AggressiveV2 -> AggressiveV3, and a
 * different default is a different score on every row for anyone who never touched the model chips.
 * That is why the pin below exists rather than only the invariant.
 *
 * The grid crosses the two terms a fourth bucket touches — the coverage bonus, which is
 * `5 * (coverage - 1)` and so moves with the bucket count, and the beta haircut, which the regime
 * policy multiplies. Beta spans below the ramp (700 against a 800 start), inside it (1200), and
 * past its end (1800, 2500), because a haircut that saturates hides a multiplier.
 *
 * The expected block was generated against `b2cb893`, the commit before the fourth bucket landed,
 * and verified equal on both trees. To regenerate after a *deliberate* scoring change: run this
 * test, take the `ACTUAL` block it prints on failure, and paste it in — reviewing every moved line,
 * because each one is a score somebody sees.
 *
 * Regenerated once, in `3520c818`. Twenty lines moved and all twenty are AggressiveV3 with
 * `fund=true`: `f` went 30 -> 23. Two engine changes produced that one number, and both are in the
 * budget the terms are scored against.
 *
 *  * `f1dc81a8` made the cash-flow term a yield and added the class exemption. The fixture below
 *    files no sector, so the policy reads it as `Unclassified`, the industrial FCF vote and cash
 *    conversion are both refused, and their weights leave the numerator and the divisor together.
 *  * `3520c818` gave V3 back the sixteen points of leverage budget that the exemption's old
 *    `{ false }` default had been taking from a term V3 still voted.
 *
 * The divisor went 100 -> 52 under the first and 52 -> 68 under the second. Legacy, Aggressive and
 * AggressiveV2 run neither path and did not move; no `fund=false` row moved.
 */
class CompositeMethodologyTest {

    @Test
    fun switched_off_every_score_is_the_score_from_before_the_market_dimension() {
        val actual = grid(regimeScoringEnabled = false)
        if (actual != EXPECTED) println("ACTUAL\n$actual")

        assertEquals(EXPECTED, actual)
    }

    /**
     * Without this, the test above is a tautology.
     *
     * A market reading is supplied in both, so "off" is a decision the engine has to make rather
     * than an absence it falls into. This asserts the population can tell the difference: if the
     * fourth bucket reached no row in this grid — wrong asset class, no daily summary, a policy
     * that refused — then off and on would agree and the pin above would pass no matter what the
     * switch did.
     */
    @Test
    fun switched_on_the_same_grid_scores_differently() {
        assertNotEquals(EXPECTED, grid(regimeScoringEnabled = true))
    }

    private fun grid(regimeScoringEnabled: Boolean): String = rows(regimeScoringEnabled).joinToString("\n")

    private fun rows(regimeScoringEnabled: Boolean): List<String> = buildList {
        listOf(null, 700, 1_200, 1_800, 2_500).forEach { beta ->
            listOf(true, false).forEach { hasFund ->
                listOf(true, false).forEach { hasSummary ->
                    listOf(true, false).forEach { hasDcf ->
                        val fund = when {
                            hasFund -> fundamentals(
                                freeCashFlowDollars = 2_000_000,
                                operatingCashFlowDollars = 2_400_000,
                                marketCapDollars = 20_000_000,
                                returnOnEquityBps = 1_500,
                                earningsGrowthBps = 900,
                                debtToEquityHundredths = 4_000,
                                forwardPeHundredths = 1_800,
                                enterpriseToEbitdaHundredths = 1_100,
                                priceToBookHundredths = 300,
                                betaMillis = beta,
                            )
                            // Beta lives on the fundamentals snapshot, so the no-fundamentals half
                            // of the grid would silently lose every beta case without this.
                            beta != null -> fundamentals(betaMillis = beta)
                            else -> null
                        }
                        val detail = baseDetail(
                            weightedExternalSignalFairValueCents = 15_000,
                            analystOpinionCount = 12,
                            recommendationMeanHundredths = 180,
                            fundamentals = fund,
                        )
                        MODELS.forEach { model ->
                            val scored = OpportunityEngine.scoreWithModel(
                                detail = detail,
                                summary = if (hasSummary) TECHNICALS else null,
                                analysis = if (hasDcf) dcf(base = 16_000) else null,
                                model = model,
                                regimeSummary = TECHNICALS,
                                marketRegime = REGIME,
                                regimeScoringEnabled = regimeScoringEnabled,
                            )
                            add(
                                "beta=$beta fund=$hasFund sum=$hasSummary dcf=$hasDcf $model " +
                                    "comp=${scored.compositeScore} f=${scored.fundamentalsScore} " +
                                    "t=${scored.technicalScore} fc=${scored.forecastScore} " +
                                    "cov=${scored.coverageCount}",
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** A reading confident enough that [com.discountscreener.core.regime.RegimeScoringPolicy] exists. */
        val REGIME = confidentRegime()

        val MODELS = listOf(
            OpportunityScoringModel.Legacy,
            OpportunityScoringModel.Aggressive,
            OpportunityScoringModel.AggressiveV2,
            OpportunityScoringModel.AggressiveV3,
        )

        val TECHNICALS = summary(
            ema20Cents = 10_400,
            ema50Cents = 10_100,
            ema200Cents = 9_500,
            macdCents = 50,
            signalCents = 20,
            histogramCents = 30,
            latestWilderRsi = 55.0,
            latestRsiSlope = 0.5,
            volumeRatioHundredths = 120,
        )

        val EXPECTED = """
            beta=null fund=true sum=true dcf=true Legacy comp=11 f=4 t=3 fc=4 cov=3
            beta=null fund=true sum=true dcf=true Aggressive comp=12 f=3 t=1 fc=8 cov=3
            beta=null fund=true sum=true dcf=true AggressiveV2 comp=56 f=26 t=45 fc=66 cov=3
            beta=null fund=true sum=true dcf=true AggressiveV3 comp=51 f=23 t=46 fc=53 cov=3
            beta=null fund=true sum=true dcf=false Legacy comp=11 f=4 t=3 fc=4 cov=3
            beta=null fund=true sum=true dcf=false Aggressive comp=12 f=3 t=1 fc=8 cov=3
            beta=null fund=true sum=true dcf=false AggressiveV2 comp=56 f=26 t=45 fc=66 cov=3
            beta=null fund=true sum=true dcf=false AggressiveV3 comp=51 f=23 t=46 fc=53 cov=3
            beta=null fund=true sum=false dcf=true Legacy comp=8 f=4 t=null fc=4 cov=2
            beta=null fund=true sum=false dcf=true Aggressive comp=11 f=3 t=null fc=8 cov=2
            beta=null fund=true sum=false dcf=true AggressiveV2 comp=51 f=26 t=null fc=66 cov=2
            beta=null fund=true sum=false dcf=true AggressiveV3 comp=43 f=23 t=null fc=53 cov=2
            beta=null fund=true sum=false dcf=false Legacy comp=8 f=4 t=null fc=4 cov=2
            beta=null fund=true sum=false dcf=false Aggressive comp=11 f=3 t=null fc=8 cov=2
            beta=null fund=true sum=false dcf=false AggressiveV2 comp=51 f=26 t=null fc=66 cov=2
            beta=null fund=true sum=false dcf=false AggressiveV3 comp=43 f=23 t=null fc=53 cov=2
            beta=null fund=false sum=true dcf=true Legacy comp=7 f=null t=3 fc=4 cov=2
            beta=null fund=false sum=true dcf=true Aggressive comp=9 f=null t=1 fc=8 cov=2
            beta=null fund=false sum=true dcf=true AggressiveV2 comp=61 f=null t=45 fc=66 cov=2
            beta=null fund=false sum=true dcf=true AggressiveV3 comp=55 f=null t=46 fc=53 cov=2
            beta=null fund=false sum=true dcf=false Legacy comp=7 f=null t=3 fc=4 cov=2
            beta=null fund=false sum=true dcf=false Aggressive comp=9 f=null t=1 fc=8 cov=2
            beta=null fund=false sum=true dcf=false AggressiveV2 comp=61 f=null t=45 fc=66 cov=2
            beta=null fund=false sum=true dcf=false AggressiveV3 comp=55 f=null t=46 fc=53 cov=2
            beta=null fund=false sum=false dcf=true Legacy comp=4 f=null t=null fc=4 cov=1
            beta=null fund=false sum=false dcf=true Aggressive comp=8 f=null t=null fc=8 cov=1
            beta=null fund=false sum=false dcf=true AggressiveV2 comp=66 f=null t=null fc=66 cov=1
            beta=null fund=false sum=false dcf=true AggressiveV3 comp=53 f=null t=null fc=53 cov=1
            beta=null fund=false sum=false dcf=false Legacy comp=4 f=null t=null fc=4 cov=1
            beta=null fund=false sum=false dcf=false Aggressive comp=8 f=null t=null fc=8 cov=1
            beta=null fund=false sum=false dcf=false AggressiveV2 comp=66 f=null t=null fc=66 cov=1
            beta=null fund=false sum=false dcf=false AggressiveV3 comp=53 f=null t=null fc=53 cov=1
            beta=700 fund=true sum=true dcf=true Legacy comp=11 f=4 t=3 fc=4 cov=3
            beta=700 fund=true sum=true dcf=true Aggressive comp=12 f=3 t=1 fc=8 cov=3
            beta=700 fund=true sum=true dcf=true AggressiveV2 comp=56 f=26 t=45 fc=66 cov=3
            beta=700 fund=true sum=true dcf=true AggressiveV3 comp=51 f=23 t=46 fc=53 cov=3
            beta=700 fund=true sum=true dcf=false Legacy comp=11 f=4 t=3 fc=4 cov=3
            beta=700 fund=true sum=true dcf=false Aggressive comp=12 f=3 t=1 fc=8 cov=3
            beta=700 fund=true sum=true dcf=false AggressiveV2 comp=56 f=26 t=45 fc=66 cov=3
            beta=700 fund=true sum=true dcf=false AggressiveV3 comp=51 f=23 t=46 fc=53 cov=3
            beta=700 fund=true sum=false dcf=true Legacy comp=8 f=4 t=null fc=4 cov=2
            beta=700 fund=true sum=false dcf=true Aggressive comp=11 f=3 t=null fc=8 cov=2
            beta=700 fund=true sum=false dcf=true AggressiveV2 comp=51 f=26 t=null fc=66 cov=2
            beta=700 fund=true sum=false dcf=true AggressiveV3 comp=43 f=23 t=null fc=53 cov=2
            beta=700 fund=true sum=false dcf=false Legacy comp=8 f=4 t=null fc=4 cov=2
            beta=700 fund=true sum=false dcf=false Aggressive comp=11 f=3 t=null fc=8 cov=2
            beta=700 fund=true sum=false dcf=false AggressiveV2 comp=51 f=26 t=null fc=66 cov=2
            beta=700 fund=true sum=false dcf=false AggressiveV3 comp=43 f=23 t=null fc=53 cov=2
            beta=700 fund=false sum=true dcf=true Legacy comp=7 f=0 t=3 fc=4 cov=3
            beta=700 fund=false sum=true dcf=true Aggressive comp=4 f=-5 t=1 fc=8 cov=3
            beta=700 fund=false sum=true dcf=true AggressiveV2 comp=61 f=null t=45 fc=66 cov=2
            beta=700 fund=false sum=true dcf=true AggressiveV3 comp=55 f=null t=46 fc=53 cov=2
            beta=700 fund=false sum=true dcf=false Legacy comp=7 f=0 t=3 fc=4 cov=3
            beta=700 fund=false sum=true dcf=false Aggressive comp=4 f=-5 t=1 fc=8 cov=3
            beta=700 fund=false sum=true dcf=false AggressiveV2 comp=61 f=null t=45 fc=66 cov=2
            beta=700 fund=false sum=true dcf=false AggressiveV3 comp=55 f=null t=46 fc=53 cov=2
            beta=700 fund=false sum=false dcf=true Legacy comp=4 f=0 t=null fc=4 cov=2
            beta=700 fund=false sum=false dcf=true Aggressive comp=3 f=-5 t=null fc=8 cov=2
            beta=700 fund=false sum=false dcf=true AggressiveV2 comp=66 f=null t=null fc=66 cov=1
            beta=700 fund=false sum=false dcf=true AggressiveV3 comp=53 f=null t=null fc=53 cov=1
            beta=700 fund=false sum=false dcf=false Legacy comp=4 f=0 t=null fc=4 cov=2
            beta=700 fund=false sum=false dcf=false Aggressive comp=3 f=-5 t=null fc=8 cov=2
            beta=700 fund=false sum=false dcf=false AggressiveV2 comp=66 f=null t=null fc=66 cov=1
            beta=700 fund=false sum=false dcf=false AggressiveV3 comp=53 f=null t=null fc=53 cov=1
            beta=1200 fund=true sum=true dcf=true Legacy comp=11 f=4 t=3 fc=4 cov=3
            beta=1200 fund=true sum=true dcf=true Aggressive comp=12 f=3 t=1 fc=8 cov=3
            beta=1200 fund=true sum=true dcf=true AggressiveV2 comp=56 f=26 t=45 fc=66 cov=3
            beta=1200 fund=true sum=true dcf=true AggressiveV3 comp=46 f=23 t=46 fc=53 cov=3
            beta=1200 fund=true sum=true dcf=false Legacy comp=11 f=4 t=3 fc=4 cov=3
            beta=1200 fund=true sum=true dcf=false Aggressive comp=12 f=3 t=1 fc=8 cov=3
            beta=1200 fund=true sum=true dcf=false AggressiveV2 comp=56 f=26 t=45 fc=66 cov=3
            beta=1200 fund=true sum=true dcf=false AggressiveV3 comp=46 f=23 t=46 fc=53 cov=3
            beta=1200 fund=true sum=false dcf=true Legacy comp=8 f=4 t=null fc=4 cov=2
            beta=1200 fund=true sum=false dcf=true Aggressive comp=11 f=3 t=null fc=8 cov=2
            beta=1200 fund=true sum=false dcf=true AggressiveV2 comp=51 f=26 t=null fc=66 cov=2
            beta=1200 fund=true sum=false dcf=true AggressiveV3 comp=38 f=23 t=null fc=53 cov=2
            beta=1200 fund=true sum=false dcf=false Legacy comp=8 f=4 t=null fc=4 cov=2
            beta=1200 fund=true sum=false dcf=false Aggressive comp=11 f=3 t=null fc=8 cov=2
            beta=1200 fund=true sum=false dcf=false AggressiveV2 comp=51 f=26 t=null fc=66 cov=2
            beta=1200 fund=true sum=false dcf=false AggressiveV3 comp=38 f=23 t=null fc=53 cov=2
            beta=1200 fund=false sum=true dcf=true Legacy comp=7 f=0 t=3 fc=4 cov=3
            beta=1200 fund=false sum=true dcf=true Aggressive comp=4 f=-5 t=1 fc=8 cov=3
            beta=1200 fund=false sum=true dcf=true AggressiveV2 comp=61 f=null t=45 fc=66 cov=2
            beta=1200 fund=false sum=true dcf=true AggressiveV3 comp=50 f=null t=46 fc=53 cov=2
            beta=1200 fund=false sum=true dcf=false Legacy comp=7 f=0 t=3 fc=4 cov=3
            beta=1200 fund=false sum=true dcf=false Aggressive comp=4 f=-5 t=1 fc=8 cov=3
            beta=1200 fund=false sum=true dcf=false AggressiveV2 comp=61 f=null t=45 fc=66 cov=2
            beta=1200 fund=false sum=true dcf=false AggressiveV3 comp=50 f=null t=46 fc=53 cov=2
            beta=1200 fund=false sum=false dcf=true Legacy comp=4 f=0 t=null fc=4 cov=2
            beta=1200 fund=false sum=false dcf=true Aggressive comp=3 f=-5 t=null fc=8 cov=2
            beta=1200 fund=false sum=false dcf=true AggressiveV2 comp=66 f=null t=null fc=66 cov=1
            beta=1200 fund=false sum=false dcf=true AggressiveV3 comp=48 f=null t=null fc=53 cov=1
            beta=1200 fund=false sum=false dcf=false Legacy comp=4 f=0 t=null fc=4 cov=2
            beta=1200 fund=false sum=false dcf=false Aggressive comp=3 f=-5 t=null fc=8 cov=2
            beta=1200 fund=false sum=false dcf=false AggressiveV2 comp=66 f=null t=null fc=66 cov=1
            beta=1200 fund=false sum=false dcf=false AggressiveV3 comp=48 f=null t=null fc=53 cov=1
            beta=1800 fund=true sum=true dcf=true Legacy comp=11 f=4 t=3 fc=4 cov=3
            beta=1800 fund=true sum=true dcf=true Aggressive comp=12 f=3 t=1 fc=8 cov=3
            beta=1800 fund=true sum=true dcf=true AggressiveV2 comp=56 f=26 t=45 fc=66 cov=3
            beta=1800 fund=true sum=true dcf=true AggressiveV3 comp=41 f=23 t=46 fc=53 cov=3
            beta=1800 fund=true sum=true dcf=false Legacy comp=11 f=4 t=3 fc=4 cov=3
            beta=1800 fund=true sum=true dcf=false Aggressive comp=12 f=3 t=1 fc=8 cov=3
            beta=1800 fund=true sum=true dcf=false AggressiveV2 comp=56 f=26 t=45 fc=66 cov=3
            beta=1800 fund=true sum=true dcf=false AggressiveV3 comp=41 f=23 t=46 fc=53 cov=3
            beta=1800 fund=true sum=false dcf=true Legacy comp=8 f=4 t=null fc=4 cov=2
            beta=1800 fund=true sum=false dcf=true Aggressive comp=11 f=3 t=null fc=8 cov=2
            beta=1800 fund=true sum=false dcf=true AggressiveV2 comp=51 f=26 t=null fc=66 cov=2
            beta=1800 fund=true sum=false dcf=true AggressiveV3 comp=33 f=23 t=null fc=53 cov=2
            beta=1800 fund=true sum=false dcf=false Legacy comp=8 f=4 t=null fc=4 cov=2
            beta=1800 fund=true sum=false dcf=false Aggressive comp=11 f=3 t=null fc=8 cov=2
            beta=1800 fund=true sum=false dcf=false AggressiveV2 comp=51 f=26 t=null fc=66 cov=2
            beta=1800 fund=true sum=false dcf=false AggressiveV3 comp=33 f=23 t=null fc=53 cov=2
            beta=1800 fund=false sum=true dcf=true Legacy comp=7 f=0 t=3 fc=4 cov=3
            beta=1800 fund=false sum=true dcf=true Aggressive comp=4 f=-5 t=1 fc=8 cov=3
            beta=1800 fund=false sum=true dcf=true AggressiveV2 comp=61 f=null t=45 fc=66 cov=2
            beta=1800 fund=false sum=true dcf=true AggressiveV3 comp=45 f=null t=46 fc=53 cov=2
            beta=1800 fund=false sum=true dcf=false Legacy comp=7 f=0 t=3 fc=4 cov=3
            beta=1800 fund=false sum=true dcf=false Aggressive comp=4 f=-5 t=1 fc=8 cov=3
            beta=1800 fund=false sum=true dcf=false AggressiveV2 comp=61 f=null t=45 fc=66 cov=2
            beta=1800 fund=false sum=true dcf=false AggressiveV3 comp=45 f=null t=46 fc=53 cov=2
            beta=1800 fund=false sum=false dcf=true Legacy comp=4 f=0 t=null fc=4 cov=2
            beta=1800 fund=false sum=false dcf=true Aggressive comp=3 f=-5 t=null fc=8 cov=2
            beta=1800 fund=false sum=false dcf=true AggressiveV2 comp=66 f=null t=null fc=66 cov=1
            beta=1800 fund=false sum=false dcf=true AggressiveV3 comp=43 f=null t=null fc=53 cov=1
            beta=1800 fund=false sum=false dcf=false Legacy comp=4 f=0 t=null fc=4 cov=2
            beta=1800 fund=false sum=false dcf=false Aggressive comp=3 f=-5 t=null fc=8 cov=2
            beta=1800 fund=false sum=false dcf=false AggressiveV2 comp=66 f=null t=null fc=66 cov=1
            beta=1800 fund=false sum=false dcf=false AggressiveV3 comp=43 f=null t=null fc=53 cov=1
            beta=2500 fund=true sum=true dcf=true Legacy comp=11 f=4 t=3 fc=4 cov=3
            beta=2500 fund=true sum=true dcf=true Aggressive comp=12 f=3 t=1 fc=8 cov=3
            beta=2500 fund=true sum=true dcf=true AggressiveV2 comp=56 f=26 t=45 fc=66 cov=3
            beta=2500 fund=true sum=true dcf=true AggressiveV3 comp=41 f=23 t=46 fc=53 cov=3
            beta=2500 fund=true sum=true dcf=false Legacy comp=11 f=4 t=3 fc=4 cov=3
            beta=2500 fund=true sum=true dcf=false Aggressive comp=12 f=3 t=1 fc=8 cov=3
            beta=2500 fund=true sum=true dcf=false AggressiveV2 comp=56 f=26 t=45 fc=66 cov=3
            beta=2500 fund=true sum=true dcf=false AggressiveV3 comp=41 f=23 t=46 fc=53 cov=3
            beta=2500 fund=true sum=false dcf=true Legacy comp=8 f=4 t=null fc=4 cov=2
            beta=2500 fund=true sum=false dcf=true Aggressive comp=11 f=3 t=null fc=8 cov=2
            beta=2500 fund=true sum=false dcf=true AggressiveV2 comp=51 f=26 t=null fc=66 cov=2
            beta=2500 fund=true sum=false dcf=true AggressiveV3 comp=33 f=23 t=null fc=53 cov=2
            beta=2500 fund=true sum=false dcf=false Legacy comp=8 f=4 t=null fc=4 cov=2
            beta=2500 fund=true sum=false dcf=false Aggressive comp=11 f=3 t=null fc=8 cov=2
            beta=2500 fund=true sum=false dcf=false AggressiveV2 comp=51 f=26 t=null fc=66 cov=2
            beta=2500 fund=true sum=false dcf=false AggressiveV3 comp=33 f=23 t=null fc=53 cov=2
            beta=2500 fund=false sum=true dcf=true Legacy comp=7 f=0 t=3 fc=4 cov=3
            beta=2500 fund=false sum=true dcf=true Aggressive comp=4 f=-5 t=1 fc=8 cov=3
            beta=2500 fund=false sum=true dcf=true AggressiveV2 comp=61 f=null t=45 fc=66 cov=2
            beta=2500 fund=false sum=true dcf=true AggressiveV3 comp=45 f=null t=46 fc=53 cov=2
            beta=2500 fund=false sum=true dcf=false Legacy comp=7 f=0 t=3 fc=4 cov=3
            beta=2500 fund=false sum=true dcf=false Aggressive comp=4 f=-5 t=1 fc=8 cov=3
            beta=2500 fund=false sum=true dcf=false AggressiveV2 comp=61 f=null t=45 fc=66 cov=2
            beta=2500 fund=false sum=true dcf=false AggressiveV3 comp=45 f=null t=46 fc=53 cov=2
            beta=2500 fund=false sum=false dcf=true Legacy comp=4 f=0 t=null fc=4 cov=2
            beta=2500 fund=false sum=false dcf=true Aggressive comp=3 f=-5 t=null fc=8 cov=2
            beta=2500 fund=false sum=false dcf=true AggressiveV2 comp=66 f=null t=null fc=66 cov=1
            beta=2500 fund=false sum=false dcf=true AggressiveV3 comp=43 f=null t=null fc=53 cov=1
            beta=2500 fund=false sum=false dcf=false Legacy comp=4 f=0 t=null fc=4 cov=2
            beta=2500 fund=false sum=false dcf=false Aggressive comp=3 f=-5 t=null fc=8 cov=2
            beta=2500 fund=false sum=false dcf=false AggressiveV2 comp=66 f=null t=null fc=66 cov=1
            beta=2500 fund=false sum=false dcf=false AggressiveV3 comp=43 f=null t=null fc=53 cov=1
        """.trimIndent()
    }
}
