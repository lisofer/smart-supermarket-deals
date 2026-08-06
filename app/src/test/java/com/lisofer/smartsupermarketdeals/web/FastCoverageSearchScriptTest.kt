package com.lisofer.smartsupermarketdeals.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastCoverageSearchScriptTest {
    @Test
    fun searchPlanIsBroadBoundedAndUnique() {
        assertEquals(fastCoverageSearchTerms.size, fastCoverageSearchTerms.distinct().size)
        assertTrue(fastCoverageSearchTerms.size in 80..110)
        assertTrue(fastCoverageSearchTerms.containsAll(listOf("", "agua", "leche", "arroz")))
        assertTrue(fastCoverageSearchTerms.containsAll(listOf("fruta", "verdura", "detergente")))
        assertTrue(('a'..'z').all { fastCoverageSearchTerms.contains(it.toString()) })
        assertTrue(('0'..'9').all { fastCoverageSearchTerms.contains(it.toString()) })
    }

    @Test
    fun scriptUsesAdaptiveNoveltyPagination() {
        assertTrue(fastCoverageSearchScript.contains("coverage_started"))
        assertTrue(fastCoverageSearchScript.contains("coverage_complete"))
        assertTrue(fastCoverageSearchScript.contains("const CONCURRENCY = 10"))
        assertTrue(fastCoverageSearchScript.contains("const MAX_REQUESTS = 450"))
        assertTrue(fastCoverageSearchScript.contains("const NOVELTY_STOP_PAGES = 2"))
        assertTrue(fastCoverageSearchScript.contains("newCatalogProducts"))
        assertTrue(fastCoverageSearchScript.contains("const exhaustPrimary"))
        assertTrue(fastCoverageSearchScript.contains("const exhaustSecondary"))
        assertTrue(fastCoverageSearchScript.contains("adaptive-coverage-v17"))
        assertFalse(fastCoverageSearchScript.contains("const MAX_REQUESTS = 1500"))
        assertFalse(fastCoverageSearchScript.contains("MAX_PAGES_PER_QUERY"))
    }
}
