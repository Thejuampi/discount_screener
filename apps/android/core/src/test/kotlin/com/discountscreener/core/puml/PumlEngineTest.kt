package com.discountscreener.core.puml

import com.discountscreener.core.runtime.ModelInput
import com.discountscreener.core.runtime.ModelOutput
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
    fun multiplier_edit_in_the_document_changes_the_result() {
        var timesTwo = loadBody(
            """
            partition "a" {
              :x = 2 × 3;
              :Done;
            }
            """.trimIndent(),
        )
        var timesFour = loadBody(
            """
            partition "a" {
              :x = 2 × 4;
              :Done;
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf(6.0, 8.0),
            listOf(timesTwo.num("x"), timesFour.num("x")),
        )
    }

    @Test
    fun new_if_in_the_document_is_honored() {
        var out = loadBody(
            """
            partition "a" {
              :x = 10;
              if (x > 5?) then (yes)
                :flag high;
              endif
              :Done;
            }
            """.trimIndent(),
        )
        assertEquals(true, "high" in out.flags)
    }

    @Test
    fun new_partition_in_the_document_is_walked() {
        var out = loadBody(
            """
            partition "a" {
              :x = 1;
            }
            partition "b" {
              :y = x + 4;
              :Done;
            }
            """.trimIndent(),
        )
        assertEquals(5.0, out.num("y"))
    }

    @Test
    fun host_call_uses_the_argument_from_the_document() {
        var host = object : PumlHost {
            override fun evaluate(
                phrase: String,
                env: MutableMap<String, ModelValue>,
            ): ModelValue = ModelValue.Missing

            override fun call(
                name: String,
                args: List<ModelValue>,
                env: MutableMap<String, ModelValue>,
                document: PumlDocument,
            ): ModelValue {
                var n = args.firstOrNull()?.asNum() ?: return ModelValue.Missing
                return ModelValue.Num(n * 3.0)
            }
        }
        var model = ActivityPumlModelFactory.load(
            wrap(
                "CallDoc",
                """
                partition "a" {
                  :x = triple(2);
                  :Done;
                }
                """.trimIndent(),
            ),
            host,
        )
        var out = model.evaluate(ModelInput.of())
        assertEquals(6.0, out.num("x"))
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

    private fun loadBody(body: String): ModelOutput {
        var model = ActivityPumlModelFactory.load(wrap("TinyEdit", body), SilentHost)
        return model.evaluate(ModelInput.of())
    }

    companion object {
        fun wrap(title: String, body: String): PumlSource = PumlSource(
            uri = "$title.puml",
            text = listOf(
                "@startuml",
                "title $title",
                "start",
                body.trim(),
                "stop",
                "@enduml",
            ).joinToString("\n"),
        )

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
