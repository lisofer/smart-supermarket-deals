package com.lisofer.smartsupermarketdeals.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastCoverageSearchScriptTest {
    @Test
    fun searchPlanIsDeepStrategicAndUnique() {
        assertEquals(fastCoverageSearchTerms.size, fastCoverageSearchTerms.distinct().size)
        assertTrue(fastCoverageSearchTerms.size in 330..430)
        assertTrue(
            fastCoverageSearchTerms.containsAll(
                listOf("", "agua", "leche", "arroz", "carne", "detergente", "pañal")
            )
        )
        assertTrue(
            fastCoverageSearchTerms.containsAll(
                listOf("coca cola", "la serenisima", "arcor", "skip", "pampers")
            )
        )
        assertTrue(fastCoverageSearchTerms.containsAll(listOf("ca", "ma", "pa", "te", "ch")))
        assertTrue(('a'..'z').all { fastCoverageSearchTerms.contains(it.toString()) })
        assertTrue(('0'..'9').all { fastCoverageSearchTerms.contains(it.toString()) })
    }

    @Test
    fun scriptUsesHighCoverageAdaptivePagination() {
        assertTrue(fastCoverageSearchScript.contains("coverage_started"))
        assertTrue(fastCoverageSearchScript.contains("coverage_complete"))
        assertTrue(fastCoverageSearchScript.contains("const CONCURRENCY = 12"))
        assertTrue(fastCoverageSearchScript.contains("const MAX_REQUESTS = 800"))
        assertTrue(fastCoverageSearchScript.contains("const MAX_PRIMARY_PAGES = 100"))
        assertTrue(fastCoverageSearchScript.contains("const MAX_SECONDARY_PAGES = 16"))
        assertTrue(fastCoverageSearchScript.contains("const NOVELTY_STOP_PAGES = 2"))
        assertTrue(fastCoverageSearchScript.contains("newCatalogProducts"))
        assertTrue(fastCoverageSearchScript.contains("const exhaustPrimary"))
        assertTrue(fastCoverageSearchScript.contains("const exhaustSecondary"))
        assertTrue(fastCoverageSearchScript.contains("strategic-coverage-v18"))
        assertTrue(fastCoverageSearchScript.contains("strategicQueries: SEARCH_TERMS.length"))
        assertFalse(fastCoverageSearchScript.contains("const MAX_REQUESTS = 1500"))
        assertFalse(fastCoverageSearchScript.contains("MAX_PAGES_PER_QUERY"))
    }
}
