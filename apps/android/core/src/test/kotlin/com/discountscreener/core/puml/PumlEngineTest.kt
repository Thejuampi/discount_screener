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
    fun emit_reason_keeps_parentheses() {
        var out = loadBody(
            """
            partition "t" {
              :Watch reason=Disputed(now);
            }
            """.trimIndent(),
        )
        assertEquals("Disputed(now)", out.emission?.fields?.get("reason"))
    }

    @Test
    fun unary_minus_in_the_document_negates() {
        var out = loadBody(
            """
            partition "a" {
              :x = -1;
              :Done;
            }
            """.trimIndent(),
        )
        assertEquals(-1.0, out.num("x"))
    }

    @Test
    fun parenthesized_negation_follows_the_document() {
        var out = loadBody(
            """
            partition "a" {
              :x = 0 - (2 × 3 - 1);
              :Done;
            }
            """.trimIndent(),
        )
        assertEquals(-5.0, out.num("x"))
    }

    @Test
    fun engine_does_not_default_a_missing_reason() {
        var model = ActivityPumlModelFactory.load(watchSource(), SilentHost)
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
    fun document_function_runs_on_call() {
        var out = loadBody(
            """
            partition "triple(n)" {
              :y = n × 3;
            }
            partition "main" {
              :x = triple(2);
              :Done;
            }
            """.trimIndent(),
        )
        assertEquals(6.0, out.num("x"))
    }

    @Test
    fun document_function_is_not_walked_as_main() {
        var out = loadBody(
            """
            partition "ghost(n)" {
              :x = 99;
            }
            partition "main" {
              :y = 1;
              :Done;
            }
            """.trimIndent(),
        )
        assertEquals(true, out.num("x") == null)
    }

    @Test
    fun document_function_locals_do_not_leak() {
        var out = loadBody(
            """
            partition "triple(n)" {
              :y = n × 3;
            }
            partition "main" {
              :x = triple(2);
              :Done;
            }
            """.trimIndent(),
        )
        assertEquals(true, out.num("y") == null)
    }

    @Test
    fun document_function_edit_changes_the_result() {
        var timesThree = loadBody(
            """
            partition "triple(n)" {
              :y = n × 3;
            }
            partition "main" {
              :x = triple(2);
              :Done;
            }
            """.trimIndent(),
        )
        var timesFour = loadBody(
            """
            partition "triple(n)" {
              :y = n × 4;
            }
            partition "main" {
              :x = triple(2);
              :Done;
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf(6.0, 8.0),
            listOf(timesThree.num("x"), timesFour.num("x")),
        )
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
    fun standard_host_does_not_own_cheapness() {
        var host = StandardPumlHost()
        var value = host.call(
            "cheapness",
            listOf(ModelValue.Num(8.0), ModelValue.Num(10.0), ModelValue.Num(0.7), ModelValue.Num(1.5)),
            mutableMapOf(),
            ActivityPumlModelFactory.load(tinySource(), SilentHost).document,
        )
        assertEquals(true, value is ModelValue.Missing)
    }

    @Test
    fun arithmetic_can_subtract_a_host_call() {
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
                "ArithCall",
                """
                partition "a" {
                  :x = 0 - triple(2);
                  :Done;
                }
                """.trimIndent(),
            ),
            host,
        )
        var out = model.evaluate(ModelInput.of())
        assertEquals(-6.0, out.num("x"))
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

        fun watchSource(): PumlSource = PumlSource(
            uri = "watch.puml",
            text = """
                @startuml
                title TinyWatch
                start
                partition "t" {
                  :Watch;
                }
                stop
                @enduml
            """.trimIndent(),
        )
    }
}
