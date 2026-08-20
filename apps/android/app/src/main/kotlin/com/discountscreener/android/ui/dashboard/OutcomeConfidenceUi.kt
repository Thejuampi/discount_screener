package com.discountscreener.android.ui.dashboard

import com.discountscreener.android.presentation.dashboard.EvRangeRailModel
import com.discountscreener.android.presentation.dashboard.QuantLensUiState
import com.discountscreener.core.model.OutcomeConfidence
import com.discountscreener.core.model.QuantLensLensId

/**
 * The outcome range, as one line the reader can act on.
 *
 * It sits next to the confidence band and answers a different question. The band asks whether the
 * data is trustworthy; this asks how far apart the sources put the answer. Both can be good news
 * at once, and both can be bad news at once.
 */
internal data class OutcomeConfidenceUi(
    val label: String,
    /** True when the reader is at risk of over-trusting the line. The wording lives in core,
     *  in `OUTCOME_CONFIDENCE_UNMEASURED_NOTE`, so both platforms name the same holes. */
    val showCaveat: Boolean,
)

/**
 * Printed under a Narrow or an unmeasured reading, and not under the others.
 *
 * Those are the two states that can be read as good news. Wide already warns for itself, so
 * repeating the caveat there would cost a line and add nothing.
 */
internal fun outcomeConfidenceUi(band: OutcomeConfidence, widthBps: Int?): OutcomeConfidenceUi {
    var span = widthBps?.let { "sources span ${it / 100}% of the centre" }
    return when (band) {
        OutcomeConfidence.Unmeasured -> OutcomeConfidenceUi("Outcome range · not measured", showCaveat = true)
        OutcomeConfidence.Narrow -> OutcomeConfidenceUi("Outcome range · Narrow · $span", showCaveat = true)
        OutcomeConfidence.Moderate -> OutcomeConfidenceUi("Outcome range · Moderate · $span", showCaveat = false)
        OutcomeConfidence.Wide -> OutcomeConfidenceUi("Outcome range · Wide · $span", showCaveat = false)
    }
}

/**
 * The upside interval the Lens tab already draws, lifted to the header.
 *
 * Nothing new is computed. The section builds the rail only when all three points exist and the
 * range is scenario-weighted, so a header rail means the same thing there as it does here.
 */
internal fun headlineEvRail(quantLens: QuantLensUiState?): EvRangeRailModel? = quantLens
    ?.sections
    ?.firstOrNull { section -> section.lensId == QuantLensLensId.ExpectedValueRange }
    ?.evRailModel
