package com.lisofer.smartsupermarketdeals.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryFirstCoverageTest {
    @Test
    fun categoryCrawlerPersistsAndDiscoversActualAisles() {
        assertTrue(exhaustiveCatalogScript.contains("__smartDealsCrawlerV14State"))
        assertTrue(exhaustiveCatalogScript.contains("localStorage.getItem(KEY)"))
        assertTrue(exhaustiveCatalogScript.contains("localStorage.setItem(KEY"))
        assertTrue(exhaustiveCatalogScript.contains("__smartDealsResetCatalogCrawler"))
        assertTrue(exhaustiveCatalogScript.contains("const MAX_PENDING_ROUTES = 500"))
        assertTrue(exhaustiveCatalogScript.contains("likelyCategoryLink"))
        assertTrue(exhaustiveCatalogScript.contains("categoryCrawler:true"))
        assertFalse(exhaustiveCatalogScript.contains("sessionStorage.getItem(KEY)"))
    }

    @Test
    fun categoryResponsesKeepEverySkuAndInheritedPromotion() {
        assertTrue(catalogResponseCaptureScript.contains("catalog-response-v14"))
        assertTrue(catalogResponseCaptureScript.contains("MAX_RESPONSE_CHARS = 12000000"))
        assertTrue(catalogResponseCaptureScript.contains("product.__smartDealsSectionPromotion"))
        assertTrue(catalogResponseCaptureScript.contains("products.push(product)"))
        assertTrue(catalogResponseCaptureScript.contains("event: 'catalog_response'"))
        assertTrue(catalogResponseCaptureScript.contains("BATCH_SIZE = 40"))
    }
}
