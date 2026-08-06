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
    fun scriptCoordinatesCompletionAndExhaustivePagination() {
        assertTrue(fastCoverageSearchScript.contains("coverage_started"))
        assertTrue(fastCoverageSearchScript.contains("coverage_complete"))
        assertTrue(fastCoverageSearchScript.contains("const CONCURRENCY = 7"))
        assertTrue(fastCoverageSearchScript.contains("MAX_PAGES_PER_QUERY"))
        assertTrue(fastCoverageSearchScript.contains("const exhaustPages"))
        assertTrue(fastCoverageSearchScript.contains("Agotando todas las páginas"))
        assertTrue(fastCoverageSearchScript.contains("fast-coverage-v16"))
        assertFalse(fastCoverageSearchScript.contains("SECOND_PAGE_LIMIT"))
    }
}
