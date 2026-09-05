package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComponentFamilyPolicyTest {
    @Test
    fun financial_member_from_financial_token() {
        assertEquals(ComponentEconomy.Financial, ComponentFamilyPolicy.economy("gm:GMFinancialSegmentMember"))
    }

    @Test
    fun financial_member_from_credit_token() {
        assertEquals(ComponentEconomy.Financial, ComponentFamilyPolicy.economy("f:FordCreditMember"))
    }

    @Test
    fun operating_member_from_automotive_token() {
        assertEquals(ComponentEconomy.Operating, ComponentFamilyPolicy.economy("gm:AutomotiveMember"))
    }

    @Test
    fun structural_operating_segments_is_not_an_economy() {
        assertNull(ComponentFamilyPolicy.economy("us-gaap:OperatingSegmentsMember"))
    }

    @Test
    fun revolving_credit_facility_is_not_a_finance_arm() {
        assertNull(ComponentFamilyPolicy.economy("gm:TenBillionDollarRevolvingCreditFacilityMember"))
    }

    @Test
    fun credit_loss_allowance_is_not_a_finance_arm() {
        assertNull(ComponentFamilyPolicy.economy("gm:AccountsReceivableAfterAllowanceForCreditLossCurrentMember"))
    }

    @Test
    fun machinery_parent_hosts_a_captive() {
        assertEquals(
            true,
            ComponentFamilyPolicy.parentHostsCaptive(
                "Farm & Heavy Construction Machinery",
                "Industrials",
            ),
        )
    }

    @Test
    fun software_parent_does_not_host_a_captive() {
        assertEquals(
            false,
            ComponentFamilyPolicy.parentHostsCaptive("Software - Application", "Technology"),
        )
    }

    @Test
    fun non_us_lender_slice_is_ignored() {
        assertEquals(
            ComponentFactRole.Ignore,
            ComponentFamilyPolicy.role(listOf("gm:GmFinancialMember", "us-gaap:NonUsMember")),
        )
    }
}
