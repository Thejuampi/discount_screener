package com.discountscreener.android.domain.model

import com.discountscreener.core.plan.DipRowInput
import com.discountscreener.core.plan.DipSetup
import com.discountscreener.core.plan.LeftoverSignalEngine
import com.discountscreener.core.plan.PlanBoard

object LeftoverBoardAssembler {
    fun assemble(
        inputs: List<DipRowInput>,
        universeName: String,
        evaluate: (DipRowInput) -> DipSetup = LeftoverSignalEngine::evaluate,
    ): PlanBoard {
        var setups = inputs.map(evaluate)
        return LeftoverSignalEngine.rank(setups, universeName)
    }
}
