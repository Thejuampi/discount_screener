package com.discountscreener.android.domain.model

import com.discountscreener.core.plan.DipRowInput
import com.discountscreener.core.plan.LeftoverSignalEngine
import com.discountscreener.core.plan.PlanBoard

object LeftoverBoardAssembler {
    fun assemble(
        inputs: List<DipRowInput>,
        universeName: String,
    ): PlanBoard {
        var setups = inputs.map { input -> LeftoverSignalEngine.evaluate(input) }
        return LeftoverSignalEngine.rank(setups, universeName)
    }
}
