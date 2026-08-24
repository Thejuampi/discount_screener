package com.discountscreener.core.engine

import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.FundamentalSnapshot

/**
 * Whether this row reads as financial services for the scoring bucket.
 *
 * A bank's balance-sheet debt is dominated by customer deposits and an insurer's by policyholder
 * float. Both are the raw material of the business rather than borrowed money, so net debt over
 * EBITDA reads a deposit franchise as a levered industrial. When this answers true, V4 drops the
 * whole EBITDA-leverage vote; return on equity and price to book are the ratios that actually
 * rank these rows, and those stay.
 *
 * There is no second classification table here. This delegates to the canonical
 * [DcfAnalysisEngine.classifyBusiness] — the same closed-world policy the valuation family runs
 * on, healthcare plans and managed care included — so ranking and valuation can never disagree
 * about what a row is. An unclassified row is simply not financial services, which preserves the
 * pre-gate behaviour for anything the policy tables do not know.
 */
internal object FinancialClassPolicy {
    fun isFinancialServices(fundamentals: FundamentalSnapshot): Boolean =
        classify(fundamentals) == BusinessClass.FinancialServices

    /**
     * The full closed-world class, for models that must tell "operating" from "unknown".
     *
     * V4 only ever needed the boolean above, and its fail-open reading of `Unclassified` — not
     * financial services, therefore industrial — is exactly the guess V5 refuses.
     *
     * The ticker is required because Yahoo files fee networks as Credit Services.
     * [DcfAnalysisEngine.classifyBusiness] owns the payment-network industry table and the
     * issuer override. Do not add a second table here.
     */
    fun classify(fundamentals: FundamentalSnapshot): BusinessClass =
        DcfAnalysisEngine.classifyBusiness(
            fundamentals.sectorName,
            fundamentals.industryName,
            fundamentals.sectorKey,
            fundamentals.industryKey,
            symbol = fundamentals.symbol,
        )
}
