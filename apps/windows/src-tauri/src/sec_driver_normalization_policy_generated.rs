// GENERATED FROM shared/contracts/sec-driver-normalization.json. DO NOT EDIT.

pub const POLICY_FINGERPRINT: &str = "sec-driver-normalization/6";
pub const REQUIRED_UNIT: &str = "USD";
pub const MINIMUM_DURATION_DAYS: i64 = 325;
pub const MAXIMUM_DURATION_DAYS: i64 = 380;
pub const MATERIAL_ACQUISITION_REVENUE_BPS: i32 = 1000;
pub const ACCEPTED_FORMS: &[&str] = &["10-K", "10-K/A"];
pub const DEVELOPMENT: &[&str] = &[
    "PaymentsToAcquirePropertyPlantAndEquipment",
    "PaymentsToAcquireProductiveAssets",
    "PaymentsToAcquireOtherPropertyPlantAndEquipment",
    "PaymentsForCapitalImprovements",
    "PaymentsToExploreAndDevelopOilAndGasProperties",
];
pub const DEVELOPMENT_SOFTWARE: &[&str] = &["PaymentsForSoftware", "PaymentsToDevelopSoftware"];
pub const DEVELOPMENT_AGGREGATE: &[&str] = &["PaymentsToAcquireProductiveAssets"];
pub const PROPERTY_ACQUISITION: &[&str] = &[
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
];
pub const BUSINESS_ACQUISITION: &[&str] = &[
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
];
pub struct DriverOperator {
    pub qnames: &'static [&'static str],
    pub unit: &'static str,
    pub period_shape: &'static str,
    pub operation: &'static str,
}
pub const OPERATING_CASH_FLOW: DriverOperator = DriverOperator {
    qnames: &[
        "NetCashProvidedByUsedInOperatingActivities",
        "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations",
        "NetCashProvidedByUsedInOperatingActivitiesContinuingOperationsIncludingDiscontinuedOperation",
    ],
    unit: "USD",
    period_shape: "duration",
    operation: "select_one_equivalent",
};
pub const REVENUE: DriverOperator = DriverOperator {
    qnames: &[
        "RevenueFromContractWithCustomerExcludingAssessedTax",
        "Revenues",
        "SalesRevenueNet",
        "SalesRevenueGoodsNet",
        "RevenueFromContractWithCustomerIncludingAssessedTax",
        "RevenuesFromExternalCustomers",
    ],
    unit: "USD",
    period_shape: "duration",
    operation: "select_one_equivalent",
};
pub const INTEREST_EXPENSE: DriverOperator = DriverOperator {
    qnames: &[
        "InterestExpenseNonOperating",
        "InterestExpenseNonoperating",
        "InterestExpenseDebt",
        "InterestAndDebtExpense",
        "InterestExpense",
        "InterestExpenseOtherLongTermDebt",
        "InterestIncomeExpenseNet",
        "InterestIncomeExpenseNonoperatingNet",
        "FinanceLeaseInterestExpense",
        "InterestPaidNet",
    ],
    unit: "USD",
    period_shape: "duration",
    operation: "select_one_equivalent",
};
pub const TOTAL_DEBT: DriverOperator = DriverOperator {
    qnames: &[
        "DebtAndCapitalLeaseObligations",
        "LongTermDebtAndCapitalLeaseObligations",
        "LongTermDebt",
        "DebtInstrumentCarryingAmount",
    ],
    unit: "USD",
    period_shape: "instant",
    operation: "select_one_equivalent",
};
pub const CURRENT_DEBT: DriverOperator = DriverOperator {
    qnames: &[
        "LongTermDebtAndFinanceLeaseObligationsCurrent",
        "LongTermDebtCurrent",
        "DebtCurrent",
        "ShortTermBorrowings",
    ],
    unit: "USD",
    period_shape: "instant",
    operation: "sum_disjoint_components",
};
pub const NON_CURRENT_DEBT: DriverOperator = DriverOperator {
    qnames: &[
        "LongTermDebtAndFinanceLeaseObligationsNoncurrent",
        "LongTermDebtNoncurrent",
    ],
    unit: "USD",
    period_shape: "instant",
    operation: "sum_disjoint_components",
};
pub const TAX_EXPENSE: DriverOperator = DriverOperator {
    qnames: &[
        "IncomeTaxExpenseBenefit",
        "IncomeTaxExpenseBenefitContinuingOperations",
    ],
    unit: "USD",
    period_shape: "duration",
    operation: "derive_effective_tax",
};
pub const PRETAX_INCOME: DriverOperator = DriverOperator {
    qnames: &[
        "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest",
        "IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments",
        "IncomeLossFromContinuingOperationsBeforeIncomeTaxes",
        "IncomeLossFromContinuingOperationsBeforeIncomeTaxesDomestic",
        "IncomeLossFromContinuingOperationsBeforeIncomeTaxesForeign",
    ],
    unit: "USD",
    period_shape: "duration",
    operation: "derive_effective_tax",
};
pub const MARGINAL_TAX_REFERENCE: DriverOperator = DriverOperator {
    qnames: &[
        "IncomeTaxReconciliationAtFederalStatutoryIncomeTaxRate",
        "IncomeTaxReconciliationFederalStatutoryIncomeTaxRate",
        "EffectiveIncomeTaxRateReconciliationAtFederalStatutoryIncomeTaxRate",
        "StatutoryFederalIncomeTaxRate",
        "StatutoryIncomeTaxRate",
    ],
    unit: "pure",
    period_shape: "duration",
    operation: "reference_policy",
};
pub const DILUTED_AVERAGE_SHARES: DriverOperator = DriverOperator {
    qnames: &["WeightedAverageNumberOfDilutedSharesOutstanding"],
    unit: "shares",
    period_shape: "duration",
    operation: "select_one_equivalent",
};
