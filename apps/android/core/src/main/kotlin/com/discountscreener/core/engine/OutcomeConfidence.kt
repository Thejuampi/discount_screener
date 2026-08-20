package com.discountscreener.core.engine

import com.discountscreener.core.model.OutcomeConfidence

/**
 * How wide the range of outcomes is — a different question from whether the data can be trusted.
 *
 * [confidenceFor] answers the second one: qualified, supportive signal, enough analysts. A name can
 * pass all three and still have a fair value that every source places somewhere different. That
 * name reads High there and Wide here, and both readings are correct.
 *
 * Nothing in this file feeds a score. [confidenceFor] does move the composite on Windows
 * (`engine.rs:1940-1942`) and can force Avoid (`:2023`); this reading is shown and never consumed.
 */
data class OutcomeConfidenceReading(
    val band: OutcomeConfidence,
    /** The widest span found, in bps of its own centre. Null when no source gave one. */
    val widthBps: Int?,
    val causes: List<ConfidenceCause>,
)

/**
 * Under 20% of the centre. The knee is the same number the DCF width ramp calls a narrow book
 * (`V3_FORECAST_DCF_WIDTH_LOWER`), so the app has one vocabulary for "narrow" instead of two.
 *
 * The knees are not fitted to outcome data. No such measurement exists in this repo. They are the
 * two knees the scoring ramps already use, reused so the two readings cannot contradict each other
 * on the same screen.
 */
val OUTCOME_NARROW_MAX_BPS = (V3_FORECAST_DCF_WIDTH_LOWER * 10_000).toInt()

/** At or over 60% of the centre, which is where the target-range penalty already saturates. */
val OUTCOME_WIDE_MIN_BPS = (V3_FORECAST_UNCERTAINTY_BOUND * 10_000).toInt()

/**
 * What this reading does not look at, said out loud.
 *
 * The company's own guidance range is not ingested anywhere in this repo, and the analyst EPS
 * triple that `ForwardForecast` parses (`OperatingValuation.kt:128-130`) is validated and then
 * dropped — only the mean drives a projection, and none of the three reach a row. So a Narrow here
 * means the sources that were read agree, never that the outcome is settled.
 */
const val OUTCOME_CONFIDENCE_UNMEASURED_NOTE =
    "Read from Street targets and model scenarios only. Company guidance width is not ingested, " +
        "and the analyst EPS range is parsed but never reaches a row."

/**
 * The span between two bounds as bps of its centre.
 *
 * Null unless the three values make a real interval: a centre of zero has no scale to measure
 * against, and bounds that do not straddle it in order are a parse failure and not a narrow range.
 */
fun spanWidthBps(lowCents: Long?, highCents: Long?, centreCents: Long?): Int? {
    var low = lowCents ?: return null
    var high = highCents ?: return null
    var centre = centreCents ?: return null
    if (centre <= 0L || high <= low) return null
    return ((high - low) * 10_000L / centre).toInt()
}

/**
 * The widest span wins.
 *
 * Two sources that disagree about how uncertain a name is are not averaged: if either one says the
 * value could be anywhere, the reader has to know that before the other one calms them down.
 */
fun outcomeConfidenceFor(streetWidthBps: Int?, modelWidthBps: Int?): OutcomeConfidenceReading {
    var causes = listOf(
        ConfidenceCause("Street targets", widthLabel(streetWidthBps)),
        ConfidenceCause("Model scenarios", widthLabel(modelWidthBps)),
    )
    var widest = listOfNotNull(streetWidthBps, modelWidthBps).maxOrNull()
        ?: return OutcomeConfidenceReading(OutcomeConfidence.Unmeasured, null, causes)
    return OutcomeConfidenceReading(
        band = when {
            widest < OUTCOME_NARROW_MAX_BPS -> OutcomeConfidence.Narrow
            widest < OUTCOME_WIDE_MIN_BPS -> OutcomeConfidence.Moderate
            else -> OutcomeConfidence.Wide
        },
        widthBps = widest,
        causes = causes,
    )
}

private fun widthLabel(widthBps: Int?): String =
    if (widthBps == null) "not measured" else "${widthBps / 100}% of centre"
