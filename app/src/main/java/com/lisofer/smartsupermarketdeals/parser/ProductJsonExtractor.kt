package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.CapturedProduct
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
        return (product.effectiveDiscountPercent ?: 0.0) * 100.0 +
            if (product.promotionCategory != null) 1_000.0 else 0.0 +
            if (!product.promoLabel.isNullOrBlank()) 100.0 else 0.0 +
            if (product.originalPrice != null) 10.0 else 0.0
    }
}
