package com.discountscreener.core.puml

import com.discountscreener.core.runtime.ModelInput
import com.discountscreener.core.runtime.ModelValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PumlEngineTest {

    @Test
    fun tiny_document_emits_done_and_binds_arithmetic() {
        var model = ActivityPumlModelFactory.load(tinySource(), SilentHost)
        var out = model.evaluate(ModelInput.of())
        assertEquals("Done", out.emission?.name)
    }

    @Test
    fun tiny_document_binds_the_sum() {
        var model = ActivityPumlModelFactory.load(tinySource(), SilentHost)
        var out = model.evaluate(ModelInput.of())
        assertEquals(3.0, out.num("x"))
    }

    @Test
    fun tiny_document_keeps_reason_from_the_box() {
        var model = ActivityPumlModelFactory.load(tinySource(), SilentHost)
        var out = model.evaluate(ModelInput.of())
        assertEquals("ok", out.emission?.fields?.get("reason"))
    }

    @Test
    fun if_false_takes_the_else_emit() {
        var model = ActivityPumlModelFactory.load(gateSource(), SilentHost)
        var out = model.evaluate(ModelInput.of())
        assertEquals("Keep", out.emission?.name)
    }

    @Test
    fun unknown_activity_syntax_fails_closed() {
        var error = assertFailsWith<IllegalStateException> {
            ActivityPumlModelFactory.load(
                PumlSource(
                    uri = "bad.puml",
                    text = """
                        @startuml
                        title Bad
                        start
                        partition "x" {
                          class Foo
                        }
                        @enduml
                    """.trimIndent(),
                ),
                SilentHost,
            )
        }
        assertEquals(true, error.message?.contains("unsupported") == true)
    }

    @Test
    fun first_match_table_comes_from_a_note() {
        var model = ActivityPumlModelFactory.load(tableSource(), SilentHost)
        assertEquals(listOf("alpha", "beta"), model.document.tables["reason"])
    }

    @Test
    fun pascal_case_box_is_an_emit_not_a_host_call() {
        var model = ActivityPumlModelFactory.load(tinySource(), object : PumlHost {
            override fun evaluate(
                phrase: String,
                env: MutableMap<String, ModelValue>,
            ): ModelValue = error("host must not see Done")
        })
        var out = model.evaluate(ModelInput.of())
        assertEquals("Done", out.emission?.name)
    }

    @Test
    fun host_may_fill_empty_emit_fields() {
        var host = object : PumlHost {
            override fun evaluate(
                phrase: String,
                env: MutableMap<String, ModelValue>,
            ): ModelValue = ModelValue.Missing

            override fun decorateEmit(
                name: String,
                fields: Map<String, String>,
                env: Map<String, ModelValue>,
                flags: Set<String>,
                document: PumlDocument,
            ): Map<String, String> {
                if (name == "Watch" && "reason" !in fields) {
                    return fields + ("reason" to document.tables["reason"]!!.first())
                }
                return fields
            }
        }
        var model = ActivityPumlModelFactory.load(tableSource(), host)
        var out = model.evaluate(ModelInput.of())
        assertEquals("alpha", out.emission?.fields?.get("reason"))
    }

    @Test
    fun engine_does_not_default_a_missing_reason() {
        var model = ActivityPumlModelFactory.load(tableSource(), SilentHost)
        var out = model.evaluate(ModelInput.of())
        assertNull(out.emission?.fields?.get("reason"))
    }

    @Test
    fun compare_inside_parentheses_stays_a_host_phrase() {
        var model = ActivityPumlModelFactory.load(
            PumlSource(
                uri = "paren.puml",
                text = """
                    @startuml
                    title TinyParen
                    start
                    partition "p" {
                      :x = keep (n > 1);
                      :Done;
                    }
                    stop
                    @enduml
                """.trimIndent(),
            ),
            SilentHost,
        )
        var out = model.evaluate(ModelInput.of())
        assertEquals(true, out.bindings["x"] is ModelValue.Missing)
    }

    private object SilentHost : PumlHost {
        override fun evaluate(
            phrase: String,
            env: MutableMap<String, ModelValue>,
        ): ModelValue = ModelValue.Missing
    }

    companion object {
        fun tinySource(): PumlSource = PumlSource(
            uri = "tiny.puml",
            text = """
                @startuml
                title TinySum
                start
                partition "add" {
                  :x = 1 + 2;
                  :Done reason=ok;
                }
                stop
                @enduml
            """.trimIndent(),
        )

        fun gateSource(): PumlSource = PumlSource(
            uri = "gate.puml",
            text = """
                @startuml
                title TinyGate
                start
                partition "gate" {
                  :n = 1;
                  if (n > 2?) then (yes)
                    :Skip;
                    stop
                  else (no)
                    :Keep reason=low;
                  endif
                }
                stop
                @enduml
            """.trimIndent(),
        )

        fun tableSource(): PumlSource = PumlSource(
            uri = "table.puml",
            text = """
                @startuml
                title TinyTable
                start
                partition "t" {
                  :Watch;
                  note right
                    reason, first match:
                    1  alpha
                    2  beta
                  end note
                }
                stop
                @enduml
            """.trimIndent(),
        )
    }
}
