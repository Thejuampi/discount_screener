package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sieve is allowed to drop bytes. It is not allowed to change an answer.
 *
 * Every cut it makes - the quarter, the form, the dimensional row, the dead field - is a cut the
 * reader made for itself after the parse. So a reader must reach the same drivers whether it reads
 * the source or the sieved copy. These fixtures are whole companyfacts documents, with the frames,
 * the accession numbers and the labels still in them.
 */
class SecCompanyFactsSieveParityTest {
    @Test
    fun the_residual_reader_reaches_the_same_drivers_through_the_sieve() {
        var sources = listOf("sec-companyfacts/JPM.json", "sec-companyfacts/ACGL.json").map { fixture(it) }
        assertEquals(
            sources.map { raw -> SecResidualFacts.extract(raw) },
            sources.map { raw -> SecResidualFacts.extract(SecCompanyFactsSieve.sieve(raw.reader())) },
        )
    }

    private fun fixture(path: String): String {
        var stream = requireNotNull(javaClass.classLoader.getResourceAsStream(path))
        return stream.bufferedReader().use { it.readText() }
    }
}
