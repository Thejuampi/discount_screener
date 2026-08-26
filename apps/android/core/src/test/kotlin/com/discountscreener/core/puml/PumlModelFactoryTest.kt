package com.discountscreener.core.puml

import com.discountscreener.core.runtime.Model
import com.discountscreener.core.runtime.ModelValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PumlModelFactoryTest {

    @Test
    fun factory_loads_the_frozen_puml_as_a_puml_model() {
        var model = ActivityPumlModelFactory.load(frozenSource(), SilentHost)
        assertEquals("EarningsCheapness", model.identity.id)
    }

    @Test
    fun loaded_puml_model_is_a_model() {
        var model: Model = ActivityPumlModelFactory.load(frozenSource(), SilentHost)
        assertTrue(model is PumlModel)
    }

    @Test
    fun document_keeps_the_eps_now_identity() {
        var expr = assignNamed(frozenPumlText(), "eps_now")
        assertEquals("eps_basis + b × k_now", render(expr))
    }

    @Test
    fun document_keeps_the_eps_next_identity() {
        var expr = assignNamed(frozenPumlText(), "eps_next")
        assertEquals("eps_basis + b × k_next", render(expr))
    }

    @Test
    fun mutated_eps_now_identity_comes_from_the_source() {
        var mutated = frozenPumlText().replace("eps_basis + b × k_now", "eps_basis + b × k_mut")
        var expr = assignNamed(mutated, "eps_now")
        assertEquals("eps_basis + b × k_mut", render(expr))
    }

    @Test
    fun frozen_document_exposes_the_first_match_table() {
        var model = ActivityPumlModelFactory.load(frozenSource(), SilentHost)
        assertEquals(
            listOf("Disputed(now)", "Disputed(next)", "quality", "SingleSource", "eps_window_short"),
            model.document.tables["reason"],
        )
    }

    private fun assignNamed(pumlText: String, name: String): PumlExpr {
        var model = ActivityPumlModelFactory.load(PumlSource("earnings-cheapness.puml", pumlText), SilentHost)
        var assigns = flattenAssigns(model.document.partitions.flatMap { it.steps })
        return assigns.single { it.name == name }.expression
    }

    private fun flattenAssigns(steps: List<PumlStep>): List<PumlStep.Assign> {
        var out = ArrayList<PumlStep.Assign>()
        fun walk(list: List<PumlStep>) {
            list.forEach { step ->
                when (step) {
                    is PumlStep.Assign -> out.add(step)
                    is PumlStep.Branch -> {
                        walk(step.yes)
                        walk(step.no)
                    }
                    is PumlStep.Split -> step.arms.forEach(::walk)
                    else -> Unit
                }
            }
        }
        walk(steps)
        return out
    }

    private fun render(expr: PumlExpr): String = when (expr) {
        is PumlExpr.Ident -> expr.name
        is PumlExpr.Number -> if (expr.value == expr.value.toLong().toDouble()) {
            expr.value.toLong().toString()
        } else {
            expr.value.toString()
        }
        is PumlExpr.Bool -> expr.value.toString()
        is PumlExpr.Phrase -> expr.text
        is PumlExpr.Call -> "${expr.name}(${expr.args.joinToString(", ") { render(it) }})"
        is PumlExpr.Unary -> "${expr.op} ${render(expr.inner)}"
        is PumlExpr.Binary -> "${render(expr.left)} ${expr.op} ${render(expr.right)}"
    }

    private object SilentHost : PumlHost {
        override fun evaluate(
            phrase: String,
            env: MutableMap<String, ModelValue>,
        ): ModelValue = ModelValue.Missing
    }

    companion object {
        fun frozenSource(): PumlSource {
            return PumlSource(
                uri = "earnings-cheapness.puml",
                text = frozenPumlText(),
            )
        }

        fun frozenPumlText(): String {
            var resource = PumlModelFactoryTest::class.java.getResourceAsStream("/earnings-cheapness.puml")
            if (resource != null) {
                return resource.bufferedReader().use { it.readText() }
            }
            var candidates = listOf(
                Path.of("_bmad-output/planning-artifacts/earnings-cheapness.puml"),
                Path.of("../../_bmad-output/planning-artifacts/earnings-cheapness.puml"),
                Path.of("G:/dev/repos/discount_screener/_bmad-output/planning-artifacts/earnings-cheapness.puml"),
            )
            var path = candidates.firstOrNull { Files.isRegularFile(it) }
                ?: error("frozen earnings-cheapness.puml not found")
            return Files.readString(path)
        }
    }
}
