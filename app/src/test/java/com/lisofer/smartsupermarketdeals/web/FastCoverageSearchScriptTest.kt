package com.lisofer.smartsupermarketdeals.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastCoverageSearchScriptTest {
    @Test
    fun searchPlanCoversEveryTwoLetterPrefixAndImportantBrands() {
        assertEquals(fastCoverageSearchTerms.size, fastCoverageSearchTerms.distinct().size)
        assertTrue(fastCoverageSearchTerms.size in 850..1050)
        assertTrue(
            fastCoverageSearchTerms.containsAll(
                listOf("", "agua", "leche", "arroz", "carne", "detergente", "pañal")
            )
        )
        assertTrue(
            fastCoverageSearchTerms.containsAll(
                listOf("milka", "chocolate", "coca cola", "la serenisima", "skip", "pampers")
            )
        )
        val alphabet = "abcdefghijklmnopqrstuvwxyz"
        assertTrue(
            alphabet.all { first ->
                alphabet.all { second ->
                    fastCoverageSearchTerms.contains("$first$second")
                }
            }
        )
        assertTrue(('a'..'z').all { fastCoverageSearchTerms.contains(it.toString()) })
        assertTrue(('0'..'9').all { fastCoverageSearchTerms.contains(it.toString()) })
    }

    @Test
    fun scriptExhaustsEachQueryAndSendsEveryProduct() {
        assertTrue(fastCoverageSearchScript.contains("coverage_started"))
        assertTrue(fastCoverageSearchScript.contains("coverage_complete"))
        assertTrue(fastCoverageSearchScript.contains("const CONCURRENCY = 18"))
        assertTrue(fastCoverageSearchScript.contains("const MAX_REQUESTS = 4500"))
        assertTrue(fastCoverageSearchScript.contains("const MAX_PRIMARY_PAGES = 180"))
        assertTrue(fastCoverageSearchScript.contains("const MAX_PAGES_PER_QUERY = 60"))
        assertTrue(fastCoverageSearchScript.contains("const exhaustQuery"))
        assertTrue(fastCoverageSearchScript.contains("emitAllProducts"))
        assertTrue(fastCoverageSearchScript.contains("exhaustive-products-v19"))
        assertTrue(fastCoverageSearchScript.contains("completedTerms"))
        assertTrue(fastCoverageSearchScript.contains("allQueriesCompleted: true"))
        assertFalse(fastCoverageSearchScript.contains("NOVELTY_STOP_PAGES"))
        assertFalse(fastCoverageSearchScript.contains("newCatalogProducts"))
        assertFalse(fastCoverageSearchScript.contains("strategic-coverage-v18"))
    }
}
