package com.lisofer.smartsupermarketdeals.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromotionCanonicalizerTest {
    @Test
    fun splitSecondUnitLabelOverridesDirectClassification() {
        val product = CapturedProduct(
            key = "product-1",
            name = "Producto",
            price = 1_000.0,
            originalPrice = null,
            advertisedDiscountPercent = 70.0,
            promoLabel = "2DA UNIDAD · 70% OFF",
            promotionCategory = "percent:70.0",
            promotionTitle = "70% OFF",
            effectiveDiscountPercent = 70.0,
            promotionKind = PromotionKind.DIRECT_PERCENT,
            sourceUrl = "https://www.pedidosya.com.ar/",
            promotionEvidence = PromotionEvidence.PRODUCT_STRUCTURE,
        )

        val normalized = PromotionCanonicalizer.captured(product)

        assertEquals(PromotionKind.SECOND_UNIT, normalized.promotionKind)
        assertEquals("second:70.0", normalized.promotionCategory)
        assertEquals("2da unidad 70% OFF", normalized.promotionTitle)
        assertEquals(70.0, normalized.advertisedDiscountPercent!!, 0.01)
        assertEquals(35.0, normalized.effectiveDiscountPercent!!, 0.01)
        assertEquals(PromotionEvidence.PRODUCT_TEXT, normalized.promotionEvidence)
    }

    @Test
    fun mechanicAndPercentageMayArriveInSeparateFields() {
        val normalized = PromotionCanonicalizer.secondUnitPercent(
            "2DA UNIDAD",
            "70% OFF",
        )

        assertEquals(70.0, normalized!!, 0.01)
    }

    @Test
    fun directPercentageWithoutSecondUnitMarkerIsUntouched() {
        assertNull(PromotionCanonicalizer.secondUnitPercent("70% OFF"))
    }

    @Test
    fun existingStoredDealIsCorrectedWithoutRescan() {
        val deal = PromotionDeal(
            productKey = "product-2",
            productName = "Producto guardado",
            storeName = "PedidosYa Market",
            currentPrice = 2_000.0,
            originalPrice = null,
            promoLabel = "Segunda unidad al 60%",
            categoryKey = "percent:60.0",
            categoryTitle = "60% OFF",
            effectiveDiscountPercent = 60.0,
            promotionKind = PromotionKind.DIRECT_PERCENT,
        )

        val normalized = PromotionCanonicalizer.deal(deal)

        assertEquals(PromotionKind.SECOND_UNIT, normalized.promotionKind)
        assertEquals("second:60.0", normalized.categoryKey)
        assertEquals("2da unidad 60% OFF", normalized.categoryTitle)
        assertEquals(30.0, normalized.effectiveDiscountPercent, 0.01)
    }
}
