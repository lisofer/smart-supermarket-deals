package com.lisofer.smartsupermarketdeals.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryFirstCoverageTest {
    @Test
    fun backgroundBootstrapHandsControlDirectlyToV122Engine() {
        assertTrue(exhaustiveCatalogScript.contains("__smartDealsV122BackgroundBootstrap"))
        assertTrue(exhaustiveCatalogScript.contains("window.__smartDealsFastCoverageV19 = true"))
        assertTrue(exhaustiveCatalogScript.contains("window.__smartDealsCatalogResponseV14 = true"))
        assertTrue(exhaustiveCatalogScript.contains("v122BootstrapComplete"))
        assertTrue(exhaustiveCatalogScript.contains("window.__smartDealsSearchFinished"))
        assertTrue(exhaustiveCatalogScript.contains("event: 'coverage_complete'"))
        assertFalse(exhaustiveCatalogScript.contains("__smartDealsCrawlerV14State"))
        assertFalse(exhaustiveCatalogScript.contains("MAX_PENDING_ROUTES"))
    }

    @Test
    fun adaptiveEndpointEngineMatchesV122SearchPlan() {
        assertTrue(searchEndpointHarvesterScript.contains("const MAX_REQUESTS = 360"))
        assertTrue(searchEndpointHarvesterScript.contains("const MAX_PAGES_PER_QUERY = 150"))
        assertTrue(searchEndpointHarvesterScript.contains("const MAX_PREFIX_DEPTH = 3"))
        assertTrue(searchEndpointHarvesterScript.contains("const PAGE_CONCURRENCY = 4"))
        assertTrue(searchEndpointHarvesterScript.contains("enqueue('')"))
        assertTrue(searchEndpointHarvesterScript.contains("const needsSplit"))
        assertTrue(searchEndpointHarvesterScript.contains("query.length < MAX_PREFIX_DEPTH"))
    }

    @Test
    fun correctedPromotionBadgeCaptureRemainsEnabled() {
        assertTrue(promotionCardCaptureScript.contains("promotion-card-v13"))
        assertTrue(promotionCardCaptureScript.contains("tags?"))
        assertTrue(promotionCardCaptureScript.contains("badges?"))
        assertTrue(promotionCardCaptureScript.contains("second"))
    }
}
