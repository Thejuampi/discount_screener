package com.discountscreener.core.regime

/**
 * Asset classification for the market-regime engine, mirroring `fetcher.rs::is_crypto` /
 * `is_etf` / `asset_type`.
 *
 * Breadth asks how many *stocks* are participating, so ETFs and crypto are excluded from the
 * count. An ETF tracking the index would otherwise vote on whether the index is broad.
 *
 * The membership list is pinned by `shared/contracts/market-universe-classification-v1.json` and
 * asserted against it on both platforms. Edit the contract, not just this list — otherwise the
 * two sides measure breadth over different universes and every downstream score drifts with
 * nothing failing.
 */
object AssetClassification {
    /** Top US-listed ETFs by AUM. Kept in the contract's order, grouped as the contract groups them. */
    val ETF_SYMBOLS: Set<String> = linkedSetOf(
        // Broad market / S&P 500
        "SPY", "IVV", "VOO", "VTI", "SCHB",
        // Nasdaq / large growth
        "QQQ", "QQQM", "VUG",
        // Small / mid cap
        "IWM", "IJR", "VB",
        // Dow Jones / value
        "DIA", "VTV",
        // International
        "VEA", "VWO", "EFA", "EEM",
        // Bonds
        "AGG", "BND", "LQD", "HYG", "TLT", "IEF", "SHY",
        // Commodities
        "GLD", "SLV", "USO",
        // Real estate
        "VNQ", "IYR",
        // Sector SPDRs
        "XLE", "XLF", "XLK", "XLV", "XLI", "XLY", "XLP", "XLU", "XLB", "XLRE", "XLC",
        // Thematic / dividend
        "ARKK", "ARKG", "ARKQ", "VYM", "DVY", "SCHD",
    )

    fun isCrypto(symbol: String): Boolean = symbol.endsWith("-USD")

    fun isEtf(symbol: String): Boolean = symbol in ETF_SYMBOLS

    /** "crypto" | "etf" | "stock". */
    fun assetType(symbol: String): String = when {
        isCrypto(symbol) -> "crypto"
        isEtf(symbol) -> "etf"
        else -> "stock"
    }
}
