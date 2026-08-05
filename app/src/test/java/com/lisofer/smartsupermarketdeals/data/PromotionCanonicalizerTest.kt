package com.lisofer.smartsupermarketdeals.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromotionCanonicalizerTest {
    @Test
    fun internalOneDiscountedUnitRuleBecomesSecondUnit() {
        val product = CapturedProduct(
            key = "product-1",
            name = "Agua saborizada",
            price = 1_769.0,
            originalPrice = null,
            advertisedDiscountPercent = 70.0,
            promoLabel = "1 ud. al 70% dto",
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
        assertEquals("2da al 70% OFF", normalized.promotionTitle)
        assertEquals(70.0, normalized.advertisedDiscountPercent!!, 0.01)
        assertEquals(35.0, normalized.effectiveDiscountPercent!!, 0.01)
    }

    @Test
    fun visibleSecondUnitBadgeAlsoBecomesSecondUnit() {
        val normalized = PromotionCanonicalizer.secondUnitPercent("2DA AL 60% OFF")
        assertEquals(60.0, normalized!!, 0.01)
    }

    @Test
    fun plainDirectPercentageRemainsDirect() {
        assertNull(PromotionCanonicalizer.secondUnitPercent("45% OFF"))
        assertNull(PromotionCanonicalizer.secondUnitPercent("25% OFF"))
    }

    @Test
    fun storedDealIsRelabeledWithoutAnotherScan() {
        val deal = PromotionDeal(
            productKey = "product-2",
            productName = "Producto guardado",
            storeName = "PedidosYa Market",
            currentPrice = 1_929.0,
            originalPrice = null,
            promoLabel = "1 ud. al 70% dto",
            categoryKey = "percent:70.0",
            categoryTitle = "70% OFF",
            effectiveDiscountPercent = 70.0,
            promotionKind = PromotionKind.DIRECT_PERCENT,
        )

        val normalized = PromotionCanonicalizer.deal(deal)

        assertEquals(PromotionKind.SECOND_UNIT, normalized.promotionKind)
        assertEquals("second:70.0", normalized.categoryKey)
        assertEquals("2da al 70% OFF", normalized.categoryTitle)
        assertEquals(35.0, normalized.effectiveDiscountPercent, 0.01)
    }
}
