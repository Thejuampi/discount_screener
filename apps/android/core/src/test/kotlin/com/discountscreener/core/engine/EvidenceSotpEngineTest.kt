package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EvidenceSotpEngineTest {
    @Test
    fun financial_services_uses_residual_income_without_fcff() {
        val valuation = EvidenceSotpEngine.valueFinancialServices(
            FinancialServicesComponentInput(
                componentId = "ACGL",
                sourceRegime = SourceRegime.DomesticUsGaap,
                bookEquityCents = 6_511,
                shares = 100,
                roeBps = 2_000,
                retentionBps = 7_000,
                costOfEquityBps = 900,
                stableRoeBps = 900,
                explicitYears = 5,
                evidenceRefs = listOf("book", "roe"),
                evidencePeriods = 3,
                scenarioRoeSpreadBps = 200,
                scenarioRateSpreadBps = 75,
            ),
        )
        assertEquals(ComponentModel.ResidualIncomeEquity, valuation.model)
        assertEquals(DiscountRateKind.CostOfEquity, valuation.discountRateKind)
        assertTrue(valuation.reasonCodes.any { it.contains("not_ocf_minus_ppe_capex") })
    }

    @Test
    fun operating_terminal_and_sbc_rules_fail_closed() {
        val input = OperatingComponentInput(
            componentId = "OP",
            sourceRegime = SourceRegime.DomesticUsGaap,
            fcffByYear = listOf(AnnualFcff(2024, 10_000)),
            waccBps = 800,
            nearGrowthBps = 500,
            stableGrowthBps = 200,
            terminalNopatCents = 15_000,
            terminalRoicBps = 1_000,
            terminalReinvestmentBps = 2_000,
            explicitYears = 5,
            sbcTreatment = SbcTreatment.ExpenseIncluded,
            evidenceRefs = listOf("fcff"),
            evidencePeriods = 3,
            scenarioSpreadBps = 100,
        )
        assertEquals(ComponentModel.FcffWacc, EvidenceSotpEngine.valueOperatingComponent(input).model)
        val badTerminal = assertFailsWith<IllegalArgumentException> {
            EvidenceSotpEngine.valueOperatingComponent(input.copy(stableGrowthBps = 300))
        }
        assertTrue(badTerminal.message.orEmpty().contains("missing_terminal_reinvestment_link"))
        val badSbc = assertFailsWith<IllegalArgumentException> {
            EvidenceSotpEngine.valueOperatingComponent(input.copy(sbcTreatment = SbcTreatment.Unreconciled))
        }
        assertTrue(badSbc.message.orEmpty().contains("unreconciled_sbc_treatment"))
    }

    @Test
    fun resource_driver_requires_reconciled_volume_base_and_finite_reserves() {
        val driver = CommodityDriver(
            commodity = "oil",
            volumeMillis = listOf(AnnualQuantity(2024, 100_000)),
            volumetricBase = VolumetricBase.Gross,
            priceCentsPerUnit = 200,
            hedgeCentsPerUnit = 5,
            hedgeIsUnrealized = false,
            cashCostCentsPerUnit = 50,
            sustainingCapexCentsPerUnit = 20,
            reservesMillis = 300_000,
            declineBps = 500,
            developmentCapexCentsPerYear = 100,
            finiteHorizonYears = 3,
            evidenceRefs = listOf("reserve", "volume", "price"),
        )
        val valuation = EvidenceSotpEngine.valueResourceProducer(
            ResourceProducerInput("RESOURCE", SourceRegime.DomesticUsGaap, listOf(driver), 800, false, evidencePeriods = 3),
        )
        assertEquals(ComponentModel.ResourceFinite, valuation.model)
        val mismatch = assertFailsWith<IllegalArgumentException> {
            EvidenceSotpEngine.valueResourceProducer(
                ResourceProducerInput(
                    "MIXED",
                    SourceRegime.DomesticUsGaap,
                    listOf(driver, driver.copy(commodity = "gas", volumetricBase = VolumetricBase.NetRevenueInterest)),
                    800,
                    false,
                    evidencePeriods = 3,
                ),
            )
        }
        assertTrue(mismatch.message.orEmpty().contains("volumetric_base_mismatch"))
    }

    @Test
    fun unsupported_source_and_contract_exposure_refuse() {
        val operating = OperatingComponentInput(
            componentId = "IFRS",
            sourceRegime = SourceRegime.Ifrs,
            fcffByYear = listOf(AnnualFcff(2024, 10_000)),
            waccBps = 800,
            nearGrowthBps = 500,
            stableGrowthBps = 200,
            terminalNopatCents = 15_000,
            terminalRoicBps = 1_000,
            terminalReinvestmentBps = 2_000,
            explicitYears = 5,
            sbcTreatment = SbcTreatment.ExpenseIncluded,
            evidenceRefs = listOf("fcff"),
            evidencePeriods = 3,
            scenarioSpreadBps = 100,
        )
        val sourceError = assertFailsWith<IllegalArgumentException> { EvidenceSotpEngine.valueOperatingComponent(operating) }
        assertTrue(sourceError.message.orEmpty().contains("source_regime_unsupported"))
        val contractError = assertFailsWith<IllegalArgumentException> {
            EvidenceSotpEngine.valueContractedInfrastructure(
                ContractedInfrastructureInput(
                    componentId = "PIPE",
                    sourceRegime = SourceRegime.DomesticUsGaap,
                    exposures = listOf(ContractExposure("unknown", ContractExposureKind.Unsupported, 10_000, 0, 2, evidenceRefs = listOf("contract"), material = true)),
                    operatingCostCentsPerYear = 100,
                    maintenanceCapexCentsPerYear = 100,
                    discountRateBps = 800,
                    evidencePeriods = 3,
                ),
            )
        }
        assertTrue(contractError.message.orEmpty().contains("unsupported_contract_exposure"))
    }

    @Test
    fun closed_world_classifier_refuses_unclassified_valuation() {
        val refusal = assertFailsWith<ValuationRefusalException> {
            EvidenceSotpEngine.requireEligibleComponent(
                ClassificationInput("Unknown", "Moon Cheese", AssetClass.Equity),
            )
        }
        assertEquals("unclassified_sector", refusal.reasonCode)
    }
}
