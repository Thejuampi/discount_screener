package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarketsInsiderBondTableTest {
    @Test
    fun exact_issuer_name_returns_the_borrower_id() {
        assertEquals("20821", parseMarketsInsiderBorrowerId(options(), "Apple Inc."))
    }

    @Test
    fun corporation_matches_corp_on_the_same_stem() {
        assertEquals("41909", parseMarketsInsiderBorrowerId(options(), "Microsoft Corporation"))
    }

    @Test
    fun amazon_inc_does_not_take_the_conservation_vehicle() {
        assertEquals("109728", parseMarketsInsiderBorrowerId(options(), "Amazon.com, Inc."))
    }

    @Test
    fun merck_company_matches_us_merck_only() {
        assertEquals("47865", parseMarketsInsiderBorrowerId(options(), "Merck & Company, Inc."))
    }

    @Test
    fun unknown_issuer_is_empty() {
        assertNull(parseMarketsInsiderBorrowerId(options(), "Not A Company"))
    }

    private fun options(): String {
        var stream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("marketsinsider/borrower-options.html"),
        )
        return stream.bufferedReader().use { it.readText() }
    }
}
