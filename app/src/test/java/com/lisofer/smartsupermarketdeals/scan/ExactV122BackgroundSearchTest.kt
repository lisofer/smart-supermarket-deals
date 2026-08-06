package com.lisofer.smartsupermarketdeals.scan

import com.lisofer.smartsupermarketdeals.web.promotionCardCaptureScript
import com.lisofer.smartsupermarketdeals.web.searchEndpointHarvesterScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactV122BackgroundSearchTest {
    @Test
    fun backgroundWrapperDoesNotRestartOrReplaceTheLegacyWebView() {
        assertEquals(1, LegacyV122ScanContract.WEBVIEW_INSTANCES_PER_STORE)
        assertFalse(LegacyV122ScanContract.RELOAD_ON_STALL)
        assertFalse(LegacyV122ScanContract.RESTART_AFTER_TIMEOUT)
        assertFalse(LegacyV122ScanContract.CATEGORY_FIRST)
        assertFalse(LegacyV122ScanContract.STRATEGIC_COVERAGE)
    }

    @Test
    fun searchEngineKeepsTheExactV122AdaptiveLimits() {
        assertTrue(searchEndpointHarvesterScript.contains("const MAX_REQUESTS = 360"))
        assertTrue(searchEndpointHarvesterScript.contains("const MAX_PAGES_PER_QUERY = 150"))
        assertTrue(searchEndpointHarvesterScript.contains("const MAX_PREFIX_DEPTH = 3"))
        assertTrue(searchEndpointHarvesterScript.contains("const PAGE_CONCURRENCY = 4"))
        assertTrue(searchEndpointHarvesterScript.contains("enqueue('')"))
        assertTrue(searchEndpointHarvesterScript.contains("const needsSplit = result.truncated"))
    }

    @Test
    fun laterPromotionBadgeEvidenceCaptureRemainsEnabled() {
        assertTrue(promotionCardCaptureScript.contains("promotion-card-v13"))
        assertTrue(promotionCardCaptureScript.contains("promoContainerKey"))
        assertTrue(promotionCardCaptureScript.contains("tags?"))
        assertTrue(promotionCardCaptureScript.contains("badges?"))
    }
}
