package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.CapturedProduct
import com.lisofer.smartsupermarketdeals.data.PromotionEvidence
import com.lisofer.smartsupermarketdeals.data.PromotionKind
import org.json.JSONArray
import org.json.JSONObject

object ProductJsonExtractor {
    fun extract(message: String): List<CapturedProduct> {
        return runCatching {
            val envelope = JSONObject(message)
            val sourceUrl = envelope.optString("url")
            val body = envelope.optString("body")
            if (body.isBlank()) return emptyList()

            val root: Any = if (body.trimStart().startsWith("[")) {
                JSONArray(body)
            } else {
                JSONObject(body)
            }
            ProductParserEngine(sourceUrl).extract(root)
        }.getOrDefault(emptyList())
    }

    fun prefer(existing: CapturedProduct?, incoming: CapturedProduct): CapturedProduct {
        if (existing == null) return incoming
        return if (qualityScore(incoming) > qualityScore(existing)) incoming else existing
    }

    private fun qualityScore(product: CapturedProduct): Double {
        val evidence = when (product.promotionEvidence) {
            PromotionEvidence.PRICE_PAIR -> 5_000.0
            PromotionEvidence.PRODUCT_TEXT -> 4_000.0
            PromotionEvidence.PRODUCT_STRUCTURE -> 3_000.0
            PromotionEvidence.INHERITED_SECTION -> 1_000.0
            null -> 0.0
        }
        // A specific mechanic such as "2DA AL 70% OFF" carries more information than the
        // ambiguous nested condition "1 ud. al 70% dto". This lets the richer badge capture
        // replace an earlier direct-percent interpretation of the same product.
        val specificity = when (product.promotionKind) {
            PromotionKind.SECOND_UNIT, PromotionKind.MULTIBUY -> 250.0
            PromotionKind.DIRECT_PERCENT, null -> 0.0
        }
        return evidence + specificity +
            if (product.promotionCategory != null) 500.0 else 0.0 +
            if (!product.promoLabel.isNullOrBlank()) 100.0 else 0.0 +
            if (product.originalPrice != null) 50.0 else 0.0 +
            (product.effectiveDiscountPercent ?: 0.0).coerceAtMost(100.0)
    }
}
