package com.discountscreener.android.performance

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Two costs that only ART pays, guarded at the source.
 *
 * A unit test runs on the JVM, where both of these are cheap. The device pays for them in native
 * memory the heap counters never show, and the app died with no Kotlin stack. A source scan is the
 * only instrument left that can fail on them, so this is where they are pinned.
 */
class HotPathSourceGuardTest {
    /**
     * `Regex` on ART copies the whole input into native memory for every match. `findAll` over a
     * long response body made one copy per match, and a `sp500` refresh killed the process.
     *
     * Scan the text with `indexOf` and `startsWith` instead.
     */
    @Test
    fun no_android_main_source_uses_regex() {
        assertEquals(emptyList<String>(), mainSourcesThatUse("Regex"))
    }

    /**
     * `String.toDoubleOrNull` screens the text with a `Regex` before it parses. Every number of
     * every candle of every symbol went through it. `plainDoubleOrNull` in `YahooFinanceClient`
     * reads the same numbers without one.
     */
    @Test
    fun no_android_main_source_parses_doubles_through_the_screening_regex() {
        assertEquals(emptyList<String>(), mainSourcesThatUse("toDoubleOrNull"))
    }

    /** The files whose code names [token]. Comments are dropped, so a note about it is free. */
    private fun mainSourcesThatUse(token: String): List<String> = File("src/main/kotlin")
        .walkTopDown()
        .filter { file -> file.extension == "kt" }
        .filter { file -> codeOf(file.readText()).contains(token) }
        .map { file -> file.name }
        .sorted()
        .toList()

    /** [text] without its block comments and its line comments. */
    private fun codeOf(text: String): String {
        var code = StringBuilder()
        var index = 0
        while (index < text.length) {
            var block = text.startsWith("/*", index)
            var line = text.startsWith("//", index)
            if (!block && !line) {
                code.append(text[index])
                index += 1
                continue
            }
            var closer = if (block) "*/" else "\n"
            var end = text.indexOf(closer, index + 2)
            index = if (end < 0) text.length else end + closer.length
        }
        return code.toString()
    }
}
