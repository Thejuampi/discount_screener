package com.discountscreener.core.engine

/**
 * A reading of one −100…+100 opportunity bucket.
 *
 * Cuts match `score_band` in the regime interpreter: two bands either side of a neutral middle.
 * Composite Act / Avoid stays a separate decision on the final score.
 */
enum class ScoreReading {
    Strong,
    Good,
    Neutral,
    Weak,
    Poor,
}

val SCORE_READING_STRONG: Int
    get() = ValuationPolicy.current.score.strong
val SCORE_READING_GOOD: Int
    get() = ValuationPolicy.current.score.good

fun scoreReading(score: Int): ScoreReading = when {
    score >= SCORE_READING_STRONG -> ScoreReading.Strong
    score >= SCORE_READING_GOOD -> ScoreReading.Good
    score > -SCORE_READING_GOOD -> ScoreReading.Neutral
    score > -SCORE_READING_STRONG -> ScoreReading.Weak
    else -> ScoreReading.Poor
}
