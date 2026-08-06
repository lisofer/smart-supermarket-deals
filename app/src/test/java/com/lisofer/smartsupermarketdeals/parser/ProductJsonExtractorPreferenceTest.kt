package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.CapturedProduct
import com.lisofer.smartsupermarketdeals.data.PromotionEvidence
import com.lisofer.smartsupermarketdeals.data.PromotionKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductJsonExtractorPreferenceTest {
    @Test
    fun weakerInheritedMechanicDoesNotReplaceRicherLegacyRecord() {
        val direct = product(
            kind = PromotionKind.DIRECT_PERCENT,
            effectivePercent = 70.0,
            label = "70% OFF",
            evidence = PromotionEvidence.PRODUCT_TEXT,
        )
        val secondUnit = product(
            kind = PromotionKind.SECOND_UNIT,
            effectivePercent = 35.0,
            label = "2da unidad 70% OFF",
            evidence = PromotionEvidence.INHERITED_SECTION,
        )

        val selected = ProductJsonExtractor.prefer(direct, secondUnit)

        assertEquals(PromotionKind.DIRECT_PERCENT, selected.promotionKind)
        assertEquals(70.0, selected.effectiveDiscountPercent!!, 0.01)
    }

    @Test
    fun explicitMechanicWithEqualEvidenceCorrectsAmbiguousDirectRecord() {
        val direct = product(
            kind = PromotionKind.DIRECT_PERCENT,
            effectivePercent = 70.0,
            label = "1 ud. al 70% dto",
            evidence = PromotionEvidence.PRODUCT_TEXT,
        )
        val secondUnit = product(
            kind = PromotionKind.SECOND_UNIT,
            effectivePercent = 35.0,
            label = "2da unidad 70% OFF",
            evidence = PromotionEvidence.PRODUCT_TEXT,
        )

        val selected = ProductJsonExtractor.prefer(direct, secondUnit)

        assertEquals(PromotionKind.SECOND_UNIT, selected.promotionKind)
        assertEquals("2da unidad 70% OFF", selected.promoLabel)
    }

    private fun product(
        kind: PromotionKind,
        effectivePercent: Double,
        label: String,
        evidence: PromotionEvidence,
    ) = CapturedProduct(
        key = "product-1",
        name = "Producto",
        price = 1_000.0,
        originalPrice = null,
        advertisedDiscountPercent = 70.0,
        promoLabel = label,
        promotionCategory = if (kind == PromotionKind.SECOND_UNIT) "second:70.0" else "percent:70.0",
        promotionTitle = label,
        effectiveDiscountPercent = effectivePercent,
        promotionKind = kind,
        sourceUrl = "https://www.pedidosya.com.ar/",
        promotionEvidence = evidence,
    )
}
