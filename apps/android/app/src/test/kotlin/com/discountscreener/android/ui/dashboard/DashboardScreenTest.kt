package com.discountscreener.android.ui.dashboard

import androidx.compose.ui.unit.dp
import com.discountscreener.core.model.ProjectedProviderCategory
import com.discountscreener.core.model.ProjectedProviderState
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardScreenTest {
    @Test
    fun maintenance_layout_stacks_on_narrow_widths() {
        assertEquals(MaintenanceLayoutMode.Stacked, maintenanceLayoutMode(280.dp))
    }

    @Test
    fun maintenance_layout_splits_on_regular_widths() {
        assertEquals(MaintenanceLayoutMode.Split, maintenanceLayoutMode(360.dp))
    }

    @Test
    fun provider_status_summary_uses_projected_provider_copy() {
        val summary = providerStatusSummary(
            ProjectedProviderState(
                category = ProjectedProviderCategory.ProviderUncertain,
                statusCopy = "Sources disagree; confidence lowered",
                retryable = true,
                affectedSymbols = listOf("AAPL", "MSFT"),
            ),
        )

        assertEquals(
            ProviderStatusSummary(
                title = "Provider State: ProviderUncertain",
                status = "Sources disagree; confidence lowered",
                affectedSymbols = "AAPL, MSFT",
                retryState = "Retryable",
            ),
            summary,
        )
    }
}
