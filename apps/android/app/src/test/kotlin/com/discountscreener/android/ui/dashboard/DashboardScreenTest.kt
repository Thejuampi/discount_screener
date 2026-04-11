package com.discountscreener.android.ui.dashboard

import androidx.compose.ui.unit.dp
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
}
