package com.discountscreener.core.engine

/**
 * Semantic façade over the generated contract projection. QName aliases live
 * only in shared/contracts/sec-driver-normalization.json; regenerate the
 * projection with scripts/generate-sec-driver-normalization-policy.ps1.
 */
object SecDriverNormalizationPolicy {
    const val fingerprint = GeneratedSecDriverNormalizationPolicy.fingerprint

    enum class Driver {
        OperatingCashFlow,
        Revenue,
        InterestExpense,
        TotalDebt,
        CurrentDebt,
        NonCurrentDebt,
        TaxExpense,
        PretaxIncome,
        MarginalTaxReference,
        DilutedAverageShares,
    }

    enum class PeriodShape { Duration, Instant }

    data class DriverOperator(
        val qnames: List<String>,
        val unit: String,
        val periodShape: PeriodShape,
        val operation: String,
    )

    enum class InvestmentCategory {
        Development,
        PropertyAcquisition,
        BusinessAcquisition,
        UnclassifiedInvestment,
    }

    val recurringDevelopmentConcepts: List<String>
        get() = GeneratedSecDriverNormalizationPolicy.development.toList()

    val acquisitionInvestmentConcepts: List<String>
        get() = (GeneratedSecDriverNormalizationPolicy.propertyAcquisition +
            GeneratedSecDriverNormalizationPolicy.businessAcquisition).toList()

    val acceptedForms: Set<String>
        get() = GeneratedSecDriverNormalizationPolicy.acceptedForms

    val minimumDurationDays: Int
        get() = GeneratedSecDriverNormalizationPolicy.minimumDurationDays

    val maximumDurationDays: Int
        get() = GeneratedSecDriverNormalizationPolicy.maximumDurationDays

    val materialAcquisitionRevenueBps: Int
        get() = GeneratedSecDriverNormalizationPolicy.materialAcquisitionRevenueBps

    /** QNames the companyfacts sieve may keep. Everything else is skipped on the stream. */
    val retainedQnames: Set<String>
        get() = buildSet {
            Driver.entries.forEach { driver -> addAll(operator(driver).qnames) }
            addAll(recurringDevelopmentConcepts)
            addAll(acquisitionInvestmentConcepts)
        }

    fun operator(driver: Driver): DriverOperator = GeneratedSecDriverNormalizationPolicy.let { generated ->
        val source = when (driver) {
            Driver.OperatingCashFlow -> generated.operatingCashFlow
            Driver.Revenue -> generated.revenue
            Driver.InterestExpense -> generated.interestExpense
            Driver.TotalDebt -> generated.totalDebt
            Driver.CurrentDebt -> generated.currentDebt
            Driver.NonCurrentDebt -> generated.nonCurrentDebt
            Driver.TaxExpense -> generated.taxExpense
            Driver.PretaxIncome -> generated.pretaxIncome
            Driver.MarginalTaxReference -> generated.marginalTaxReference
            Driver.DilutedAverageShares -> generated.dilutedAverageShares
        }
        DriverOperator(
            qnames = source.qnames.toList(),
            unit = source.unit,
            periodShape = when (source.periodShape) {
                "duration" -> PeriodShape.Duration
                "instant" -> PeriodShape.Instant
                else -> error("unknown SEC driver period shape: ${source.periodShape}")
            },
            operation = source.operation,
        )
    }

    fun investmentCategory(concept: String): InvestmentCategory = when (concept) {
        in GeneratedSecDriverNormalizationPolicy.development -> InvestmentCategory.Development
        in GeneratedSecDriverNormalizationPolicy.propertyAcquisition -> InvestmentCategory.PropertyAcquisition
        in GeneratedSecDriverNormalizationPolicy.businessAcquisition -> InvestmentCategory.BusinessAcquisition
        else -> InvestmentCategory.UnclassifiedInvestment
    }

    /** First-version SEC scope: consolidated USD annual duration evidence. */
    fun isAcceptedRecurringDevelopment(
        concept: String,
        unit: String?,
        durationDays: Int?,
        form: String?,
        consolidated: Boolean,
    ): Boolean = investmentCategory(concept) == InvestmentCategory.Development &&
        unit == GeneratedSecDriverNormalizationPolicy.requiredUnit &&
        durationDays in GeneratedSecDriverNormalizationPolicy.minimumDurationDays..
            GeneratedSecDriverNormalizationPolicy.maximumDurationDays &&
        form in GeneratedSecDriverNormalizationPolicy.acceptedForms && consolidated
}
