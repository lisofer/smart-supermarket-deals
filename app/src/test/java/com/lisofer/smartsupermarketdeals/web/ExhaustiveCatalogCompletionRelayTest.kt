package com.lisofer.smartsupermarketdeals.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExhaustiveCatalogCompletionRelayTest {
    @Test
    fun finishedEndpointCoverageUsesServiceCompletionEvent() {
        assertTrue(exhaustiveCatalogScript.contains("coverageComplete: true"))
        assertTrue(exhaustiveCatalogScript.contains("event: 'explore_complete'"))
        assertFalse(exhaustiveCatalogScript.contains("event: 'coverage_complete'"))
    }
}
