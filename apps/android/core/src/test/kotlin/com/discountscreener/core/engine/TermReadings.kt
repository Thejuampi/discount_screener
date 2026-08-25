package com.discountscreener.core.engine

import com.discountscreener.core.model.ScoreFactor

/**
 * A bucket's reading with only the terms in it.
 *
 * [FUND_COVERAGE_GAP_LABEL] is not a term. It carries no points and no input, and it fires whenever
 * the budget is mostly idle — which is true of nearly every fixture in these tests, because a
 * fixture that probes one constant supplies the inputs of one term and leaves the rest of the
 * budget empty. A test that pins the complete signal list would otherwise carry the flag in every
 * expectation and say nothing by it.
 *
 * Dropping the flag here is safe only while it still fires when it should, and
 * `AggressiveV4CashVoteTest.missing_fundamentals_slots_flag_coverage` is what holds that: a flag
 * that stopped firing fails there rather than passing quietly through these filters.
 */
internal fun BucketEvidence.termSignals(): List<String> = signals.filter { it != FUND_COVERAGE_GAP_LABEL }

/** The factor list without [FUND_COVERAGE_GAP_LABEL], for the same reason as [termSignals]. */
internal fun BucketEvidence.termFactors(): List<ScoreFactor> = factors.filter { it.key != FUND_COVERAGE_GAP_LABEL }

/** The keys of [termFactors], which is what most pins compare. */
internal fun BucketEvidence.termKeys(): List<String> = termFactors().map { it.key }
