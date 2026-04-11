package com.discountscreener.android.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardFormattersTest {
    @Test
    fun compact_financial_number_uses_expected_suffixes() {
        assertEquals("950", compactFinancialNumber(950))
        assertEquals("1.25K", compactFinancialNumber(1_250))
        assertEquals("9.50M", compactFinancialNumber(9_500_000))
        assertEquals("4.95B", compactFinancialNumber(4_951_000_000))
        assertEquals("1.20T", compactFinancialNumber(1_200_000_000_000))
    }

    @Test
    fun compact_money_uses_short_chart_notation() {
        assertEquals("$844", compactMoney(84_357))
        assertEquals("$2.28K", compactMoney(228_303))
        assertEquals("-$67.0", compactMoney(-6_698))
    }

    @Test
    fun symbol_with_company_matches_main_list_label_style() {
        assertEquals("NVDA NVIDIA Corporation", symbolWithCompany("NVDA", "NVIDIA Corporation"))
        assertEquals("NVDA", symbolWithCompany("NVDA", null))
        assertEquals("NVDA", symbolWithCompany("NVDA", "NVDA"))
    }
}
