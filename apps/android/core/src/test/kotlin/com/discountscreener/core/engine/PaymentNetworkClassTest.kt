package com.discountscreener.core.engine

import com.discountscreener.core.model.BusinessClass
import kotlin.test.Test
import kotlin.test.assertEquals

class PaymentNetworkClassTest {
    @Test
    fun visa_credit_services_is_operating() {
        assertEquals(
            BusinessClass.OperatingNonFinancial,
            DcfAnalysisEngine.classifyBusiness(
                "Financial Services",
                "Credit Services",
                "financial-services",
                "credit-services",
                symbol = "V",
            ),
        )
    }

    @Test
    fun payment_processing_industry_is_operating_without_a_ticker() {
        assertEquals(
            BusinessClass.OperatingNonFinancial,
            DcfAnalysisEngine.classifyBusiness(
                "Financial Services",
                "Payment Processing",
                "financial-services",
                "payment-processing",
            ),
        )
    }

    @Test
    fun capital_one_credit_services_stays_financial() {
        assertEquals(
            BusinessClass.FinancialServices,
            DcfAnalysisEngine.classifyBusiness(
                "Financial Services",
                "Credit Services",
                "financial-services",
                "credit-services",
                symbol = "COF",
            ),
        )
    }
}
