package com.discountscreener.android.domain.model

import com.discountscreener.core.model.OpportunityScoringModel

/**
 * The two Opportunities scoring choices that outlive the process.
 *
 * Both were in-memory until now, which meant every cold start silently re-scored the whole list
 * against whatever the code happened to default to. A scoring model the user picked is a setting,
 * not a session detail.
 */
data class ScoringPreferences(
    val opportunityModel: OpportunityScoringModel = DEFAULT_OPPORTUNITY_MODEL,
    val regimeScoringEnabled: Boolean = DEFAULT_REGIME_ENABLED,
) {
    companion object {
        /**
         * V2, which is what the app defaulted to before the market dimension existed.
         *
         * This was briefly V3, on the argument that the fourth dimension only applies to V3 and a
         * V2 default ships it invisible. That argument is real but it is not worth its price: V3
         * scores every name differently from V2, so the default alone moved every number on the
         * Opportunities tab for every install that had never touched the model chips — a change
         * with nothing to do with adding a dimension. Adding a dimension must add a dimension.
         *
         * The dimension is not lost, it is opted into: pick V3 from the model chips and it appears,
         * already switched on by [DEFAULT_REGIME_ENABLED]. Windows defaults to `aggressive_v3`, so
         * the two platforms disagree on the default and agree on every score per model, which is
         * the direction of disagreement that costs nothing.
         */
        val DEFAULT_OPPORTUNITY_MODEL: OpportunityScoringModel = OpportunityScoringModel.AggressiveV2

        /** Windows' `apply_regime_scoring = true`. The dimension is on until switched off. */
        const val DEFAULT_REGIME_ENABLED: Boolean = true
    }
}
