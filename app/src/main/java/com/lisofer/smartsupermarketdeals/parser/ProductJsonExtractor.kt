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
            if (envelope.optString("event") == "payload_batch") {
                extractBatch(envelope.optJSONArray("payloads"))
            } else {
                extractEnvelope(envelope)
            }
        }.getOrDefault(emptyList())
    }

    private fun extractBatch(payloads: JSONArray?): List<CapturedProduct> {
        if (payloads == null || payloads.length() == 0) return emptyList()

        val bestProducts = LinkedHashMap<String, CapturedProduct>()
        for (index in 0 until payloads.length()) {
            val envelope = payloads.optJSONObject(index)
                ?: payloads.optString(index)
                    .takeIf(String::isNotBlank)
                    ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                ?: continue

            extractEnvelope(envelope).forEach { incoming ->
                bestProducts[incoming.key] = prefer(bestProducts[incoming.key], incoming)
            }
        }
        return bestProducts.values.toList()
    }

    private fun extractEnvelope(envelope: JSONObject): List<CapturedProduct> {
        return runCatching {
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

        val existingScore = qualityScore(existing)
        val incomingScore = qualityScore(incoming)
        return when {
            incomingScore > existingScore -> incoming
            incomingScore < existingScore -> existing
            mechanicSpecificity(incoming) > mechanicSpecificity(existing) -> incoming
            else -> existing
        }
    }

    /**
     * Uses the same evidence and information factors as version 1.2.2, now grouped explicitly so
     * Kotlin cannot associate an `else` with the following addition. A more specific mechanic may
     * break an exact tie, but it cannot replace a richer product record merely for saying
     * "segunda unidad". Badge capture and canonicalization still correct labels when their
     * evidence is equal or stronger.
     */
    private fun qualityScore(product: CapturedProduct): Double {
        val evidence = when (product.promotionEvidence) {
            PromotionEvidence.PRICE_PAIR -> 5_000.0
            PromotionEvidence.PRODUCT_TEXT -> 4_000.0
            PromotionEvidence.PRODUCT_STRUCTURE -> 3_000.0
            PromotionEvidence.INHERITED_SECTION -> 1_000.0
            null -> 0.0
        }
        return evidence +
            (if (product.promotionCategory != null) 500.0 else 0.0) +
            (if (!product.promoLabel.isNullOrBlank()) 100.0 else 0.0) +
            (if (product.originalPrice != null) 50.0 else 0.0) +
            (product.effectiveDiscountPercent ?: 0.0).coerceAtMost(100.0)
    }

    private fun mechanicSpecificity(product: CapturedProduct): Int = when (product.promotionKind) {
        PromotionKind.SECOND_UNIT, PromotionKind.MULTIBUY -> 1
        PromotionKind.DIRECT_PERCENT, null -> 0
    }
}
