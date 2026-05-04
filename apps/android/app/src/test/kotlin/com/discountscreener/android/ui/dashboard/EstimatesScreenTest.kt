package com.discountscreener.android.ui.dashboard

import com.discountscreener.core.model.DcfCoverageStatus
import com.discountscreener.core.model.DcfCoverageSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class EstimatesScreenTest {
    @Test
    fun dcf_banner_uses_core_status_instead_of_recomputing_thresholds() {
        var summary = DcfCoverageSummary(
            totalEligibleSymbols = 100,
            coveredSymbols = 95,
            coverageBps = 9_500,
            status = DcfCoverageStatus.LowConfidence,
        )

        assertEquals(DcfCoverageBannerKind.LowConfidence, dcfCoverageBannerKind(summary))
    }
}