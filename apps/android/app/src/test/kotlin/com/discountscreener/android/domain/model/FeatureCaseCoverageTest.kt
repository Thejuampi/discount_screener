package com.discountscreener.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FeatureCaseCoverageTest {
    @Test
    fun every_feature_case_has_a_test() {
        var cases = File("src/test/resources/features")
            .walkTopDown()
            .filter { file -> file.extension == "feature" }
            .flatMap { file ->
                Regex("""^\s+\| ([a-z0-9_]+) \|""", RegexOption.MULTILINE)
                    .findAll(file.readText())
                    .map { match -> match.groupValues[1] }
                    .filter { name -> name != "case" }
            }
            .toList()
        var sources = File("src/test/kotlin").walkTopDown()
            .filter { file -> file.extension == "kt" }
            .joinToString("\n") { file -> file.readText() }
        var missing = cases.filter { name -> !sources.contains("fun $name") }

        assertEquals(emptyList<String>(), missing)
    }
}
