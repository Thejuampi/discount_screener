package com.discountscreener.android.domain.model

import com.discountscreener.core.model.ScoreFactor
import kotlinx.serialization.Serializable

/**
 * One symbol, one model, one scoring pass — the row that will eventually say which model works.
 *
 * V4 removes defects that are visible in the code. That is not the same as being more accurate,
 * and nothing in the suite can settle the difference: there are no point-in-time fundamentals to
 * replay against. So the app records what each model said, on the day it said it, and the answer
 * arrives in weeks rather than being asserted now.
 *
 * Both models are journalled whenever the user views one, so V3 and V4 accumulate against the same
 * days and the same prices. A comparison built from two different date ranges would be worthless.
 *
 * [compositeScoreBase] is kept beside [compositeScore] because the difference is what the market
 * dimension did, and a journal that stored only the final could never separate "the model changed"
 * from "the market reading changed".
 */
data class ScoreJournalRow(
    val symbol: String,
    val scoringModel: String,
    val scoredAtEpochSeconds: Long,
    val fundamentalsScore: Int?,
    val technicalScore: Int?,
    val forecastScore: Int?,
    val regimeScore: Int?,
    val compositeScore: Int,
    val compositeScoreBase: Int,
    val marketPriceCents: Long,
    /**
     * The terms that built the bucket scores, captured on the day they were scored.
     *
     * Bucket scores say which model won; only the terms say why, and only the terms can be put on
     * trial one by one when that verdict arrives. Null for rows written before factor capture
     * existed and for rows whose stored JSON cannot be read — an unreadable term list is a hole in
     * the record, never a fabricated empty set.
     */
    val factors: JournalFactors? = null,
)

/**
 * The per-bucket term lists behind one scoring pass. The regime bucket keeps causes and signals,
 * not scored factors, so it has no column here by design rather than by omission.
 */
@Serializable
data class JournalFactors(
    val fundamentals: List<ScoreFactor> = emptyList(),
    val technical: List<ScoreFactor> = emptyList(),
    val forecast: List<ScoreFactor> = emptyList(),
)
