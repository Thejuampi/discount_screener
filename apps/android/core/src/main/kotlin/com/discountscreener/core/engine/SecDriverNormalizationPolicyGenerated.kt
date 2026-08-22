// GENERATED FROM shared/contracts/sec-driver-normalization.json. DO NOT EDIT.
package com.discountscreener.core.engine

internal data class GeneratedSecDriverOperator(
    val qnames: List<String>,
    val unit: String,
    val periodShape: String,
    val operation: String,
)

internal object GeneratedSecDriverNormalizationPolicy {
    const val fingerprint = "sec-driver-normalization/11"
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
    val developmentWells = setOf(
        "PaymentsToAcquireOilAndGasPropertyAndEquipment",
    )
    val developmentIntangibles = setOf(
        "PaymentsToAcquireIntangibleAssets",
    )
    val developmentAggregate = setOf(
        "PaymentsToAcquireProductiveAssets",
    )
    val propertyAcquisition = setOf(
        "PaymentsToAcquireOilAndGasProperty",
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
    ),
        unit = "USD",
        periodShape = "duration",
        operation = "select_one_equivalent",
    )
    val totalDebt =     GeneratedSecDriverOperator(
        qnames = listOf(
        "DebtAndCapitalLeaseObligations",
        "LongTermDebtAndCapitalLeaseObligations",
        "LongTermDebtAndCapitalLeaseObligationsIncludingCurrentMaturities",
        "LongTermDebt",
        "DebtInstrumentCarryingAmount",
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
        unit = "USD",
        periodShape = "instant",
        operation = "sum_disjoint_components",
    )
    val nonCurrentDebt =     GeneratedSecDriverOperator(
        qnames = listOf(
        "LongTermDebtAndFinanceLeaseObligationsNoncurrent",
        "LongTermDebtNoncurrent",
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
        unit = "USD",
        periodShape = "instant",
        operation = "select_one_equivalent",
    )
    val taxExpense =     GeneratedSecDriverOperator(
        qnames = listOf(
        "IncomeTaxExpenseBenefit",
        "IncomeTaxExpenseBenefitContinuingOperations",
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
        unit = "pure",
        periodShape = "duration",
        operation = "reference_policy",
    )
    val dilutedAverageShares =     GeneratedSecDriverOperator(
        qnames = listOf(
        "WeightedAverageNumberOfDilutedSharesOutstanding",
    ),
        unit = "shares",
        periodShape = "duration",
        operation = "select_one_equivalent",
    )
}