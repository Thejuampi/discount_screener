package com.discountscreener.core.regime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Breadth counts how many *stocks* participate, so both platforms must exclude the same ETFs and
 * the same crypto. Windows holds the list as a Rust constant and Android as a Kotlin one; without
 * this test the two are hand-copies that drift the moment either is edited, and nothing fails —
 * breadth just quietly starts measuring a different universe on each side.
 */
class AssetClassificationContractTest {
    private val contract: UniverseContract by lazy { JSON.decodeFromString(Files.readString(findFixture())) }

    @Test
    fun the_etf_universe_matches_the_shared_contract() {
        assertEquals(contract.etfSymbols, AssetClassification.ETF_SYMBOLS.toList())
    }

    @Test
    fun the_crypto_rule_matches_the_shared_contract() {
        assertEquals(
            listOf(true, false),
            listOf(
                AssetClassification.isCrypto("BTC${contract.cryptoRule.suffix}"),
                AssetClassification.isCrypto("BTC"),
            ),
        )
    }

    @Test
    fun every_contract_group_is_part_of_the_flat_list() {
        assertEquals(
            emptyList<String>(),
            contract.etfSymbolGroups.values.flatten().filterNot { it in contract.etfSymbols },
        )
    }

    @Test
    fun a_plain_ticker_is_neither_etf_nor_crypto() {
        assertEquals("stock", AssetClassification.assetType("TGNO4.BA"))
    }

    private fun findFixture(): Path {
        var current = Paths.get("").toAbsolutePath()
        repeat(8) {
            val candidate = current.resolve(FIXTURE).normalize()
            if (Files.exists(candidate)) return candidate
            current = current.parent ?: return@repeat
        }
        error("$FIXTURE not found from ${Paths.get("").toAbsolutePath()}")
    }

    private companion object {
        const val FIXTURE = "shared/contracts/market-universe-classification-v1.json"

        val JSON = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class UniverseContract(
    val crypto_rule: CryptoRule,
    val etf_symbols: List<String>,
    val etf_symbol_groups: Map<String, List<String>>,
) {
    val cryptoRule: CryptoRule get() = crypto_rule
    val etfSymbols: List<String> get() = etf_symbols
    val etfSymbolGroups: Map<String, List<String>> get() = etf_symbol_groups
}

@Serializable
private data class CryptoRule(val kind: String, val suffix: String)
