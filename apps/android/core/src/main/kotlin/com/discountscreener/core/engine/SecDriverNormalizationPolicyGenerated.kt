// GENERATED FROM shared/contracts/sec-driver-normalization.json. DO NOT EDIT.
package com.discountscreener.core.engine

internal data class GeneratedSecDriverOperator(
    val qnames: List<String>,
    val qnameSigns: List<Int>,
    val unit: String,
    val periodShape: String,
    val operation: String,
)

internal object GeneratedSecDriverNormalizationPolicy {
    const val fingerprint = "sec-driver-normalization/9"
    const val requiredUnit = "USD"
    const val minimumDurationDays = 325
    const val maximumDurationDays = 380
    const val materialAcquisitionRevenueBps = 1000
    val acceptedForms = setOf(
        "10-K",
        "10-K/A",
    )
    val development = setOf(
        "PaymentsToAcquirePropertyPlantAndEquipment",
        "PaymentsToAcquireProductiveAssets",
        "PaymentsToAcquireOtherPropertyPlantAndEquipment",
        "PaymentsForCapitalImprovements",
        "PaymentsToExploreAndDevelopOilAndGasProperties",
    )
    val developmentSoftware = setOf(
        "PaymentsForSoftware",
        "PaymentsToDevelopSoftware",
    )
    val developmentAggregate = setOf(
        "PaymentsToAcquireProductiveAssets",
    )
    val propertyAcquisition = setOf(
        "PaymentsToAcquireOilAndGasProperty",
        "PaymentsToAcquireOilAndGasPropertyAndEquipment",
        "PaymentsToAcquireRoyaltyInterestsInMiningProperties",
        "PaymentsToAcquireMineralRights",
        "PaymentsToAcquireMiningAssets",
        "PaymentsToAcquireCommercialRealEstate",
        "PaymentsToAcquireAndDevelopRealEstate",
        "PaymentsToAcquireRealEstateHeldForInvestment",
        "PaymentsToAcquireHeldForSaleRealEstate",
        "PaymentsToAcquireOtherRealEstate",
        "PaymentsToAcquirePartnersInterestInRealEstatePartnershipNetOfCashAcquired",
        "PaymentsToAcquireWaterSystems",
        "PaymentsToAcquireWasteWaterSystems",
        "PaymentsToAcquireWaterAndWasteWaterSystems",
    )
    val businessAcquisition = setOf(
        "PaymentsToAcquireBusinessesNetOfCashAcquired",
        "PaymentsToAcquireBusinesses",
        "PaymentsToAcquireBusinessesGross",
        "PaymentsToAcquireBusinessesAndInterestInAffiliates",
        "OtherPaymentsToAcquireBusinesses",
        "PaymentsToAcquireBusinessInterests",
        "PaymentsToAcquireBusinessTwoNetOfCashAcquired",
        "PaymentsToAcquireBusinessThreeNetOfCashAcquired",
        "PaymentsToAcquireAdditionalInterestInSubsidiaries",
        "PaymentsToAcquireInterestInSubsidiariesAndAffiliates",
        "PaymentsToAcquireInterestInJointVenture",
        "PaymentsToAcquireLimitedPartnershipInterests",
    )
    val operatingCashFlow =     GeneratedSecDriverOperator(
        qnames = listOf(
        "NetCashProvidedByUsedInOperatingActivities",
        "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations",
        "NetCashProvidedByUsedInOperatingActivitiesContinuingOperationsIncludingDiscontinuedOperation",
    ),
        qnameSigns = listOf(
        1,
        1,
        1,
    ),
        unit = "USD",
        periodShape = "duration",
        operation = "select_one_equivalent",
    )
    val revenue =     GeneratedSecDriverOperator(
        qnames = listOf(
        "RevenueFromContractWithCustomerExcludingAssessedTax",
        "Revenues",
        "SalesRevenueNet",
        "SalesRevenueGoodsNet",
        "RevenueFromContractWithCustomerIncludingAssessedTax",
        "RevenuesFromExternalCustomers",
    ),
        qnameSigns = listOf(
        1,
        1,
        1,
        1,
        1,
        1,
    ),
        unit = "USD",
        periodShape = "duration",
        operation = "select_one_equivalent",
    )
    val interestExpense =     GeneratedSecDriverOperator(
        qnames = listOf(
        "InterestExpenseNonOperating",
        "InterestExpenseNonoperating",
        "InterestExpenseDebt",
        "InterestAndDebtExpense",
        "InterestExpense",
        "InterestExpenseOtherLongTermDebt",
        "InterestIncomeExpenseNet",
        "InterestIncomeExpenseNonoperatingNet",
        "FinanceLeaseInterestExpense",
    ),
        qnameSigns = listOf(
        1,
        1,
        1,
        1,
        1,
        1,
        -1,
        -1,
        1,
    ),
        unit = "USD",
        periodShape = "duration",
        operation = "select_one_equivalent",
    )
    val totalDebt =     GeneratedSecDriverOperator(
        qnames = listOf(
        "DebtAndCapitalLeaseObligations",
        "LongTermDebtAndCapitalLeaseObligations",
        "LongTermDebt",
        "DebtInstrumentCarryingAmount",
    ),
        qnameSigns = listOf(
        1,
        1,
        1,
        1,
    ),
        unit = "USD",
        periodShape = "instant",
        operation = "select_one_equivalent",
    )
    val currentDebt =     GeneratedSecDriverOperator(
        qnames = listOf(
        "LongTermDebtAndFinanceLeaseObligationsCurrent",
        "LongTermDebtCurrent",
        "DebtCurrent",
        "ShortTermBorrowings",
    ),
        qnameSigns = listOf(
        1,
        1,
        1,
        1,
    ),
        unit = "USD",
        periodShape = "instant",
        operation = "sum_disjoint_components",
    )
    val nonCurrentDebt =     GeneratedSecDriverOperator(
        qnames = listOf(
        "LongTermDebtAndFinanceLeaseObligationsNoncurrent",
        "LongTermDebtNoncurrent",
    ),
        qnameSigns = listOf(
        1,
        1,
    ),
        unit = "USD",
        periodShape = "instant",
        operation = "sum_disjoint_components",
    )
    val stockholdersEquity =     GeneratedSecDriverOperator(
        qnames = listOf(
        "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest",
        "StockholdersEquity",
        "PartnersCapitalIncludingPortionAttributableToNoncontrollingInterest",
        "PartnersCapital",
        "MembersEquity",
    ),
        qnameSigns = listOf(
        1,
        1,
        1,
        1,
        1,
    ),
        unit = "USD",
        periodShape = "instant",
        operation = "select_one_equivalent",
    )
    val taxExpense =     GeneratedSecDriverOperator(
        qnames = listOf(
        "IncomeTaxExpenseBenefit",
        "IncomeTaxExpenseBenefitContinuingOperations",
    ),
        qnameSigns = listOf(
        1,
        1,
    ),
        unit = "USD",
        periodShape = "duration",
        operation = "derive_effective_tax",
    )
    val pretaxIncome =     GeneratedSecDriverOperator(
        qnames = listOf(
        "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest",
        "IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments",
        "IncomeLossFromContinuingOperationsBeforeIncomeTaxes",
        "IncomeLossFromContinuingOperationsBeforeIncomeTaxesDomestic",
        "IncomeLossFromContinuingOperationsBeforeIncomeTaxesForeign",
    ),
        qnameSigns = listOf(
        1,
        1,
        1,
        1,
        1,
    ),
        unit = "USD",
        periodShape = "duration",
        operation = "derive_effective_tax",
    )
    val marginalTaxReference =     GeneratedSecDriverOperator(
        qnames = listOf(
        "IncomeTaxReconciliationAtFederalStatutoryIncomeTaxRate",
        "IncomeTaxReconciliationFederalStatutoryIncomeTaxRate",
        "EffectiveIncomeTaxRateReconciliationAtFederalStatutoryIncomeTaxRate",
        "StatutoryFederalIncomeTaxRate",
        "StatutoryIncomeTaxRate",
    ),
        qnameSigns = listOf(
        1,
        1,
        1,
        1,
        1,
    ),
        unit = "pure",
        periodShape = "duration",
        operation = "reference_policy",
    )
    val dilutedAverageShares =     GeneratedSecDriverOperator(
        qnames = listOf(
        "WeightedAverageNumberOfDilutedSharesOutstanding",
    ),
        qnameSigns = listOf(
        1,
    ),
        unit = "shares",
        periodShape = "duration",
        operation = "select_one_equivalent",
    )
}