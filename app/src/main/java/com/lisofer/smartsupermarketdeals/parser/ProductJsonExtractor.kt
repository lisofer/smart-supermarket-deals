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

        // An explicit product badge may correct an ambiguous direct condition, but only when its
        // evidence is at least as strong. This preserves the badge regression fix without giving
        // every SECOND_UNIT/MULTIBUY record a blanket priority over the broader 1.2.2 capture.
        if (explicitMechanicOverrides(incoming, existing)) return incoming
        if (explicitMechanicOverrides(existing, incoming)) return existing

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
     * "segunda unidad".
     */
    private fun qualityScore(product: CapturedProduct): Double {
        return evidenceRank(product.promotionEvidence) +
            (if (product.promotionCategory != null) 500.0 else 0.0) +
            (if (!product.promoLabel.isNullOrBlank()) 100.0 else 0.0) +
            (if (product.originalPrice != null) 50.0 else 0.0) +
            (product.effectiveDiscountPercent ?: 0.0).coerceAtMost(100.0)
    }

    private fun explicitMechanicOverrides(
        candidate: CapturedProduct,
        other: CapturedProduct,
    ): Boolean {
        if (candidate.promotionKind == PromotionKind.DIRECT_PERCENT ||
            candidate.promotionKind == null ||
            other.promotionKind != PromotionKind.DIRECT_PERCENT
        ) {
            return false
        }
        if (!hasExplicitMechanicMarker(candidate)) return false
        return evidenceRank(candidate.promotionEvidence) >= evidenceRank(other.promotionEvidence)
    }

    private fun hasExplicitMechanicMarker(product: CapturedProduct): Boolean {
        val text = listOfNotNull(product.promoLabel, product.promotionTitle)
            .joinToString(" ")
        return when (product.promotionKind) {
            PromotionKind.SECOND_UNIT -> SECOND_UNIT_MARKER.containsMatchIn(text)
            PromotionKind.MULTIBUY -> MULTIBUY_MARKER.containsMatchIn(text)
            PromotionKind.DIRECT_PERCENT, null -> false
        }
    }

    private fun evidenceRank(evidence: PromotionEvidence?): Double = when (evidence) {
        PromotionEvidence.PRICE_PAIR -> 5_000.0
        PromotionEvidence.PRODUCT_TEXT -> 4_000.0
        PromotionEvidence.PRODUCT_STRUCTURE -> 3_000.0
        PromotionEvidence.INHERITED_SECTION -> 1_000.0
        null -> 0.0
    }

    private fun mechanicSpecificity(product: CapturedProduct): Int = when (product.promotionKind) {
        PromotionKind.SECOND_UNIT, PromotionKind.MULTIBUY -> 1
        PromotionKind.DIRECT_PERCENT, null -> 0
    }

    private val SECOND_UNIT_MARKER = Regex(
        "\\b(?:2\\s*\\.?\\s*(?:da|do|a|o)|segunda|segundo)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val MULTIBUY_MARKER = Regex("\\b\\d+\\s*[xX]\\s*\\d+\\b")
}
