package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertTrue

class SecDriverNormalizationPolicyTest {
    @Test
    fun retained_qnames_include_interest_expense() {
        assertTrue(SecDriverNormalizationPolicy.retainedQnames.contains("InterestExpense"))
    }
}
