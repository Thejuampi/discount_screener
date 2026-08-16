package com.discountscreener.core.math

import com.discountscreener.core.engine.ValuationPolicy
import kotlin.math.abs

/**
 * The centre of a sample, without the plain mean.
 *
 * The Kotlin peer of `valuation-core/src/numerics.rs`. The constants and the refusal rules are that
 * file's, so a number computed here and a number computed there are the same number.
 *
 * Two functions, because two questions.
 *
 * * [robustCentre] answers *what is this population's level*, and it can only be asked of a real
 *   sample. It removes what does not belong to the population before it answers, and it refuses
 *   when removal leaves too little to speak with.
 * * [medianOf] answers *what is the middle of these few values*. Below three observations no
 *   outlier can be named — there is no dispersion to judge one against — so trimming is not a
 *   thing that can be done, and the middle is the honest answer.
 *
 * A caller with a handful of values must pick [medianOf] deliberately. One function that quietly
 * changed rule with the sample size would be the same silent methodology switch this code exists
 * to avoid.
 */

/** Ratio between the median absolute deviation and the deviation of a normal sample. */
private val MAD_TO_DEVIATION: Double
    get() = ValuationPolicy.current.robustCentre.madToDeviation

private val MAX_ABSOLUTE_Z: Double
    get() = ValuationPolicy.current.robustCentre.maxAbsoluteZ

private val MIN_OBSERVATIONS: Int
    get() = ValuationPolicy.current.robustCentre.minObservations

/**
 * The centre of a sample, computed only from the observations that belong to it.
 *
 * The plain mean is what this replaces: it cannot tell a small member from a contaminated one, and
 * it reports the contamination as the level. Here the sample states its own dispersion, anything
 * beyond [MAX_ABSOLUTE_Z] of the middle is dropped, and the centre is taken from what is left.
 *
 * One pass, deliberately. Iterating to a fixed point lets each removal tighten the scale enough to
 * justify the next, and a sample can be trimmed to almost nothing that way — the answer would then
 * come from the procedure rather than from the evidence.
 *
 * Null, never the untrimmed mean, when the sample cannot support the question:
 *
 * * any non-finite value, which would poison the whole sample;
 * * no dispersion through the middle, which happens when more than half the values are identical.
 *   Then every score is zero or infinite, and the honest report is that this sample has no scale,
 *   not that everything unlike the mode is an outlier;
 * * fewer than [MIN_OBSERVATIONS] left after trimming.
 *
 * The observation floor is enforced once, after the trim, because a trim can only shrink a sample:
 * a sample too small to answer with is too small to answer with whatever the trim does. A second
 * gate on the raw count reads as an independent check and is not one — it can never be the gate
 * that fires, and a mutation test proved it never is.
 *
 * A caller holding two, three or four values will get null often, and that is the contract working:
 * ask [medianOf] instead.
 */
fun robustCentre(values: List<Double>): Double? {
    if (values.any { !it.isFinite() }) return null
    var location = medianOf(values) ?: return null
    var scale = (medianOf(values.map { abs(it - location) }) ?: return null) * MAD_TO_DEVIATION
    if (scale <= 0.0 || !scale.isFinite()) return null
    var kept = values.filter { abs(it - location) / scale <= MAX_ABSOLUTE_Z }
    if (kept.size < MIN_OBSERVATIONS) return null
    return kept.sum() / kept.size
}

/**
 * The middle of a sample, and the centre of an even one is the middle pair's midpoint.
 *
 * `sorted[size / 2]` is the lower of the two on an even count, which is not the middle and is not
 * what a caller asking for a median has asked for. Null only when there is nothing to take a middle
 * of.
 */
fun medianOf(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    var sorted = values.sorted()
    var middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
}

/**
 * Whether [candidate] sits outside the sample's population.
 *
 * Uses the same median / MAD scale as [robustCentre]. Below three observations no
 * outlier can be named, so the answer is false. A sample with no width keeps a
 * candidate on the mode and rejects anything else — there is no scale that can
 * excuse a miss.
 */
fun isForeignTo(candidate: Double, sample: List<Double>): Boolean {
    if (!candidate.isFinite()) return true
    if (sample.size < MIN_OBSERVATIONS) return false
    if (sample.any { !it.isFinite() }) return true
    var location = medianOf(sample) ?: return true
    var scale = (medianOf(sample.map { abs(it - location) }) ?: return true) * MAD_TO_DEVIATION
    if (scale <= 0.0 || !scale.isFinite()) return abs(candidate - location) > 0.0
    return abs(candidate - location) / scale > MAX_ABSOLUTE_Z
}
