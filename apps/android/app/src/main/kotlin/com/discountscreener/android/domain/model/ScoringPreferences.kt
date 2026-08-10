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
         * V3 rather than V2, because the market dimension only applies to V3 and a default of V2
         * would ship the fourth dimension switched off for everyone who never touches the model
         * chips. Windows defaults to `aggressive_v3` for the same reason.
         *
         * This moves scores on first launch for installs that never chose a model — deliberately,
         * and once. An install that has chosen one keeps it, because the choice is written the
         * moment it is made.
         */
        val DEFAULT_OPPORTUNITY_MODEL: OpportunityScoringModel = OpportunityScoringModel.AggressiveV3

        /** Windows' `apply_regime_scoring = true`. The dimension is on until switched off. */
        const val DEFAULT_REGIME_ENABLED: Boolean = true
    }
}
