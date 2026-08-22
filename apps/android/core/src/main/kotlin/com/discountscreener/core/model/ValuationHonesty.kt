package com.discountscreener.core.model

import kotlinx.serialization.Serializable

/** Whether a published valuation input is evidence-bound. */
@Serializable
enum class ValuationHonesty {
    Honest,
    NonHonest,
}

/** One path knob that can be honest or fitted to Street. */
@Serializable
enum class HonestyKnob {
    NearTermGrowth,
    StableMargin,
    DiscountRate,
    StartingRoe,
}

/** How far the Street-implied knob sits from the honest input. */
@Serializable
enum class ImpliedStretch {
    Modest,
    Stretched,
    Absurd,
    Unreachable,
}

@Serializable
data class HonestyTaggedKnob(
    val knob: HonestyKnob,
    val honesty: ValuationHonesty,
    val honestBps: Int,
    val impliedBps: Int?,
    val impliedCents: Long? = null,
    val reachable: Boolean,
    val deltaBps: Int? = null,
    val stretch: ImpliedStretch? = null,
    val note: String,
)

@Serializable
data class HonestPathInputs(
    val holdYears: Int? = null,
    val fadeYears: Int? = null,
    val startMarginBps: Int? = null,
    val stableMarginBps: Int? = null,
    val fadeExponentHundredths: Int? = null,
    val residualFadeYears: Int? = null,
    val residualFranchiseSpreadBps: Int? = null,
    val residualRetentionBps: Int? = null,
)

/**
 * Non-honest signal: the one-knob change that would make identity match Street.
 * Street is a scoreboard here, never an honest runtime input.
 */
@Serializable
data class StreetImpliedView(
    val honesty: ValuationHonesty = ValuationHonesty.NonHonest,
    val streetBaseCents: Long,
    val honestBaseCents: Long,
    val impliedBaseCents: Long? = null,
    val winningKnob: HonestyKnob? = null,
    val winningHonestBps: Int? = null,
    val winningImpliedBps: Int? = null,
    val winningDeltaBps: Int? = null,
    val winningStretch: ImpliedStretch? = null,
    val aligned: Boolean,
    val knobs: List<HonestyTaggedKnob>,
    val policyVersion: String,
)
