package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TickerSearchEngineTest {
    @Test
    fun exact_ticker_beats_company_name_match() {
        val ranked = TickerSearchEngine.mergeAndRank(
            candidates = listOf(
                nameCandidate("META", "Meta Platforms, Inc.", TickerSearchRank.NAME_EXACT),
                profileCandidate("META", matchRank = TickerSearchRank.EXACT_TICKER_CURRENT),
            ),
            limit = 8,
        )

        assertEquals("META", ranked.first().symbol)
        assertEquals(TickerSearchRank.EXACT_TICKER_CURRENT, ranked.first().matchRank)
    }

    @Test
    fun meli_exact_remote_ranks_above_fuzzy_remote_matches() {
        val ranked = TickerSearchEngine.mergeAndRank(
            candidates = listOf(
                remoteCandidate("MELI.BA", "MercadoLibre, Inc.", TickerSearchRank.REMOTE_FUZZY),
                remoteCandidate("MELI", "MercadoLibre, Inc.", TickerSearchRank.EXACT_TICKER_REMOTE),
            ),
            limit = 8,
        )

        assertEquals("MELI", ranked.first().symbol)
        assertEquals(TickerSearchRank.EXACT_TICKER_REMOTE, ranked.first().matchRank)
    }

    @Test
    fun dedup_prefers_local_profile_row_over_remote_duplicate() {
        val ranked = TickerSearchEngine.mergeAndRank(
            candidates = listOf(
                remoteCandidate("MSFT", "Microsoft Corporation", TickerSearchRank.EXACT_TICKER_REMOTE),
                profileCandidate(
                    symbol = "MSFT",
                    profiles = listOf("dow", "sp500"),
                    inCurrentProfile = true,
                    matchRank = TickerSearchRank.EXACT_TICKER_CURRENT,
                ),
            ),
            limit = 8,
        )

        assertEquals(1, ranked.size)
        assertEquals("MSFT", ranked.single().symbol)
        assertFalse(ranked.single().isRemote)
        assertEquals(listOf("dow", "sp500"), ranked.single().profiles)
    }

    @Test
    fun should_trigger_remote_for_whitespace_name_query() {
        val local = listOf(
            TickerSearchResult(
                symbol = "MERC.CN",
                companyName = "Mercado Minerals Ltd.",
                matchRank = TickerSearchRank.REMOTE_FUZZY,
                isRemote = true,
            ),
        )

        assertTrue(TickerSearchEngine.shouldTriggerRemoteSearch("mercado libre", local))
    }

    @Test
    fun should_not_trigger_remote_for_single_character_query() {
        assertFalse(TickerSearchEngine.shouldTriggerRemoteSearch("L", emptyList()))
    }

    @Test
    fun should_not_trigger_remote_when_local_has_prefix_matches() {
        val local = listOf(
            TickerSearchResult(
                symbol = "MSFT",
                profiles = listOf("sp500"),
                inCurrentProfile = true,
                matchRank = TickerSearchRank.PREFIX_TICKER_CURRENT,
            ),
        )

        assertFalse(TickerSearchEngine.shouldTriggerRemoteSearch("MS", local))
    }

    @Test
    fun mercado_is_a_word_start_of_mercado_libre() {
        assertEquals(
            TickerSearchRank.NAME_WORD_START,
            TickerSearchEngine.companyNameMatchRank("mercado", "MercadoLibre, Inc."),
        )
    }

    @Test
    fun company_name_exact_match_is_high_confidence() {
        val result = TickerSearchResult(
            symbol = "MELI",
            companyName = "MercadoLibre, Inc.",
            matchRank = TickerSearchRank.REMOTE_FUZZY,
            isRemote = true,
        )

        assertTrue(TickerSearchEngine.isHighConfidenceMatch("MercadoLibre, Inc.", result))
    }

    @Test
    fun ticker_token_detection_allows_dots_and_hyphens() {
        assertTrue(TickerSearchEngine.isTickerToken("BRK.B"))
        assertTrue(TickerSearchEngine.isTickerToken("BRK-B"))
        assertFalse(TickerSearchEngine.isTickerToken("mercado libre"))
    }

    @Test
    fun a_seven_letter_company_word_is_not_an_exact_typed_ticker() {
        assertEquals(
            TickerSearchRank.TYPED_FALLBACK,
            TickerSearchEngine.typedQueryFallbackRank("mercado"),
        )
    }

    @Test
    fun a_short_typed_ticker_keeps_exact_remote_rank() {
        assertEquals(
            TickerSearchRank.EXACT_TICKER_REMOTE,
            TickerSearchEngine.typedQueryFallbackRank("meli"),
        )
    }

    @Test
    fun a_multi_letter_suffix_is_a_foreign_listing() {
        assertTrue(TickerSearchEngine.hasForeignExchangeSuffix("MELI.BA"))
    }

    @Test
    fun a_nasdaq_share_class_is_not_a_foreign_listing() {
        assertTrue(!TickerSearchEngine.hasForeignExchangeSuffix("BRK.B"))
    }

    @Test
    fun unsuffixed_meli_is_the_us_listing() {
        assertTrue(!TickerSearchEngine.hasForeignExchangeSuffix("MELI"))
    }

    @Test
    fun remote_name_search_refuses_the_argentina_line_when_the_adr_is_present() {
        assertTrue(
            !TickerSearchEngine.admitsRemoteSearchHit(
                symbol = "MELI.BA",
                query = "mercado",
                siblingSymbols = listOf("MELI", "MELI.BA", "MERC.CN"),
            ),
        )
    }

    @Test
    fun remote_name_search_keeps_other_mercado_names() {
        assertTrue(
            TickerSearchEngine.admitsRemoteSearchHit(
                symbol = "MERC.CN",
                query = "mercado",
                siblingSymbols = listOf("MELI", "MELI.BA", "MERC.CN"),
            ),
        )
    }

    @Test
    fun an_exact_typed_foreign_symbol_is_still_admitted() {
        assertTrue(TickerSearchEngine.admitsRemoteSearchHit("MELI.BA", "MELI.BA"))
    }

    @Test
    fun remote_name_search_keeps_the_nasdaq_meli_line() {
        assertTrue(TickerSearchEngine.admitsRemoteSearchHit("MELI", "mercado"))
    }

    @Test
    fun nasdaq_meli_ranks_above_the_argentina_line() {
        val ranked = TickerSearchEngine.mergeAndRank(
            candidates = listOf(
                remoteCandidate("MELI.BA", "MercadoLibre, Inc.", TickerSearchRank.REMOTE_FUZZY),
                remoteCandidate("MELI", "MercadoLibre, Inc.", TickerSearchRank.REMOTE_FUZZY),
            ),
            limit = 8,
        )
        assertEquals("MELI", ranked.first().symbol)
    }

    @Test
    fun typed_mercado_fallback_loses_to_meli_name_hit() {
        val ranked = TickerSearchEngine.mergeAndRank(
            candidates = listOf(
                remoteCandidate("MELI", "MercadoLibre, Inc.", TickerSearchRank.REMOTE_FUZZY),
                TickerSearchCandidate(
                    symbol = "MERCADO",
                    matchRank = TickerSearchEngine.typedQueryFallbackRank("mercado")!!,
                ),
            ),
            limit = 8,
        )
        assertEquals("MELI", ranked.first().symbol)
    }

    @Test
    fun lowercase_mercado_with_multiple_suggestions_does_not_direct_open() {
        assertFalse(
            TickerSearchEngine.shouldDirectOpenTickerOnSubmit(
                query = "mercado",
                suggestionSymbols = listOf("MELI", "MELI.BA", "MERC.CN"),
            ),
        )
    }

    @Test
    fun uppercase_meli_with_multiple_suggestions_direct_opens_exact_symbol() {
        assertTrue(
            TickerSearchEngine.shouldDirectOpenTickerOnSubmit(
                query = "MELI",
                suggestionSymbols = listOf("MELI", "MELI.BA"),
            ),
        )
    }

    @Test
    fun uppercase_meli_without_suggestions_direct_opens() {
        assertTrue(
            TickerSearchEngine.shouldDirectOpenTickerOnSubmit(
                query = "MELI",
                suggestionSymbols = emptyList(),
            ),
        )
    }

    @Test
    fun lowercase_meli_without_suggestions_direct_opens() {
        assertTrue(
            TickerSearchEngine.shouldDirectOpenTickerOnSubmit(
                query = "meli",
                suggestionSymbols = emptyList(),
            ),
        )
    }

    private fun profileCandidate(
        symbol: String,
        profiles: List<String> = listOf("sp500"),
        inCurrentProfile: Boolean = true,
        matchRank: Int,
    ) = TickerSearchCandidate(
        symbol = symbol,
        profiles = profiles,
        inCurrentProfile = inCurrentProfile,
        matchRank = matchRank,
    )

    private fun nameCandidate(
        symbol: String,
        companyName: String,
        matchRank: Int,
    ) = TickerSearchCandidate(
        symbol = symbol,
        companyName = companyName,
        matchRank = matchRank,
    )

    private fun remoteCandidate(
        symbol: String,
        companyName: String,
        matchRank: Int,
    ) = TickerSearchCandidate(
        symbol = symbol,
        companyName = companyName,
        exchange = "NASDAQ",
        matchRank = matchRank,
        isRemote = true,
    )
}