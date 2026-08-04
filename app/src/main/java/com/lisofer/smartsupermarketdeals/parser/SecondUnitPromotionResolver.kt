package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.PromotionKind
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reconstructs promotions whose mechanic and numeric benefit are stored in sibling nodes, for
 * example `mechanic.type = SECOND_UNIT` together with `benefit.type = PERCENTAGE, value = 50`.
 */
internal object SecondUnitPromotionResolver {
    fun fromProductSubtree(product: JSONObject): PromotionContext? {
        val candidates = mutableListOf<Candidate>()
        inspect(product, depth = 0, inheritedCommercial = false, candidates)

        val best = candidates.maxWithOrNull(
            compareBy<Candidate> { it.score }
                .thenBy { it.depth }
                .thenBy { it.label.length }
        ) ?: return null

        val percent = rounded(best.percent)
        val title = "2da unidad ${percentLabel(percent)}% OFF"
        return PromotionContext(
            normalized = NormalizedPromotion(
                categoryKey = "second:${percentKey(percent)}",
                title = title,
                effectivePercent = percent / 2.0,
                kind = PromotionKind.SECOND_UNIT,
                advertisedPercent = percent,
            ),
            displayLabel = best.label.takeIf(String::isNotBlank) ?: title,
            unambiguous = true,
        )
    }

    private data class Candidate(
        val percent: Double,
        val label: String,
        val score: Int,
        val depth: Int,
    )

    private data class Signals(
        var secondUnit: Boolean = false,
        var localSecondUnit: Boolean = false,
        var typedPercentage: Boolean = false,
        var visitedNodes: Int = 0,
        val percentages: LinkedHashSet<Double> = LinkedHashSet(),
        val humanTexts: LinkedHashSet<String> = LinkedHashSet(),
    )

    private fun inspect(
        node: Any?,
        depth: Int,
        inheritedCommercial: Boolean,
        output: MutableList<Candidate>,
    ) {
        if (node == null || depth > INSPECT_DEPTH || output.size >= MAX_CANDIDATES) return
        when (node) {
            is JSONObject -> {
                val commercialHere = inheritedCommercial || looksCommercial(node)
                val signals = Signals()
                collectScope(node, depth = 0, inheritedSecond = false, signals)

                if (signals.secondUnit && signals.percentages.size == 1) {
                    val percent = signals.percentages.single()
                    if (percent in 0.5..100.0) {
                        val score =
                            (if (commercialHere) 100 else 0) +
                                (if (signals.localSecondUnit) 50 else 0) +
                                (if (signals.typedPercentage) 35 else 0) +
                                depth * 5 -
                                signals.visitedNodes.coerceAtMost(50)
                        output += Candidate(
                            percent = percent,
                            label = humanLabel(signals.humanTexts, percent),
                            score = score,
                            depth = depth,
                        )
                    }
                }

                val keys = node.keys()
                while (keys.hasNext() && output.size < MAX_CANDIDATES) {
                    val key = keys.next()
                    if (isProductCollectionKey(key)) continue
                    val child = node.opt(key)
                    if (child is JSONObject || child is JSONArray) {
                        inspect(
                            node = child,
                            depth = depth + 1,
                            inheritedCommercial = commercialHere || isCommercialKey(key),
                            output = output,
                        )
                    }
                }
            }

            is JSONArray -> {
                for (index in 0 until node.length()) {
                    inspect(node.opt(index), depth + 1, inheritedCommercial, output)
                    if (output.size >= MAX_CANDIDATES) break
                }
            }
        }
    }

    private fun collectScope(
        node: Any?,
        depth: Int,
        inheritedSecond: Boolean,
        output: Signals,
    ) {
        if (node == null || depth > SIGNAL_DEPTH || output.visitedNodes >= MAX_SIGNAL_NODES) return
        output.visitedNodes += 1

        when (node) {
            is String -> collectString(node, inheritedSecond, output)

            is JSONObject -> {
                val descriptorTexts = TYPE_KEYS.mapNotNull { key ->
                    node.opt(key).asText()?.takeIf(String::isNotBlank)
                }
                val descriptor = normalizeSemantic(descriptorTexts.joinToString(" "))
                val descriptorSecond = SECOND_SIGNAL.containsMatchIn(descriptor)
                val objectSecond = inheritedSecond || descriptorSecond ||
                    node.keys().asSequence().any(::isSecondKey)

                if (descriptorSecond) {
                    output.secondUnit = true
                    output.localSecondUnit = true
                    descriptorTexts.forEach(output.humanTexts::add)
                }

                val percentageTyped = PERCENTAGE_DESCRIPTOR.containsMatchIn(descriptor)
                if (percentageTyped) {
                    output.typedPercentage = true
                    PERCENT_VALUE_KEYS.firstNotNullOfOrNull { key ->
                        parsePercent(node.opt(key), key)
                    }?.takeIf { it in 0.5..100.0 }
                        ?.let(output.percentages::add)
                }

                val keys = node.keys()
                while (keys.hasNext() && output.visitedNodes < MAX_SIGNAL_NODES) {
                    val key = keys.next()
                    if (isProductCollectionKey(key)) continue
                    val value = node.opt(key)
                    val keySecond = isSecondKey(key)
                    val valueSecond = value is String &&
                        SECOND_SIGNAL.containsMatchIn(normalizeSemantic(value))
                    val childSecond = objectSecond || keySecond || valueSecond

                    if (keySecond || valueSecond) {
                        output.secondUnit = true
                        output.localSecondUnit = true
                        if (value is String) output.humanTexts += value
                    }

                    if (isPercentKey(key)) {
                        parsePercent(value, key)
                            ?.takeIf { it in 0.5..100.0 }
                            ?.let(output.percentages::add)
                    }

                    collectScope(value, depth + 1, childSecond, output)
                }
            }

            is JSONArray -> {
                for (index in 0 until node.length()) {
                    collectScope(node.opt(index), depth + 1, inheritedSecond, output)
                    if (output.visitedNodes >= MAX_SIGNAL_NODES) break
                }
            }
        }
    }

    private fun collectString(raw: String, inheritedSecond: Boolean, output: Signals) {
        val cleaned = raw.replace(Regex("\\s+"), " ").trim()
        if (cleaned.length !in 1..320) return
        val normalized = normalizeSemantic(cleaned).replace('×', 'x')
        val secondHere = SECOND_SIGNAL.containsMatchIn(normalized)
        if (secondHere || inheritedSecond) {
            output.secondUnit = true
            if (secondHere) output.localSecondUnit = true
        }
        TEXT_PERCENT.findAll(normalized).forEach { match ->
            match.groupValues.getOrNull(1)
                ?.replace(',', '.')
                ?.toDoubleOrNull()
                ?.takeIf { it in 0.5..100.0 }
                ?.let(output.percentages::add)
        }
        if (secondHere || PROMO_HUMAN_TEXT.containsMatchIn(normalized)) {
            output.humanTexts += cleaned
        }
    }

    private fun looksCommercial(json: JSONObject): Boolean {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (isCommercialKey(key) || isSecondKey(key)) return true
            val value = json.opt(key)
            if (value is String) {
                val normalized = normalizeSemantic(value)
                if (SECOND_SIGNAL.containsMatchIn(normalized) ||
                    PROMO_HUMAN_TEXT.containsMatchIn(normalized)) {
                    return true
                }
            }
        }
        return false
    }

    private fun isCommercialKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT)
        return normalized.contains("promo") || normalized.contains("discount") ||
            normalized.contains("descuento") || normalized.contains("benefit") ||
            normalized.contains("badge") || normalized.contains("offer") ||
            normalized.contains("campaign") || normalized.contains("saving") ||
            normalized.contains("deal") || normalized.contains("commercial") ||
            normalized.contains("mechanic") || normalized.contains("condition") ||
            normalized.contains("rule") || normalized.contains("pricing")
    }

    private fun isSecondKey(key: String): Boolean {
        return SECOND_SIGNAL.containsMatchIn(normalizeSemantic(key))
    }

    private fun isPercentKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT)
        return normalized == "percentage" || normalized == "percent" ||
            normalized == "percentageoff" || normalized == "percentage_off" ||
            normalized == "percentoff" || normalized == "percent_off" ||
            normalized.contains("discountpercentage") ||
            normalized.contains("discount_percentage") ||
            normalized.contains("discountpercent") ||
            normalized.contains("discount_percent") ||
            ((normalized.contains("discount") || normalized.contains("benefit") ||
                normalized.contains("saving")) &&
                (normalized.contains("rate") || normalized.contains("ratio") ||
                    normalized.contains("percent")))
    }

    private fun parsePercent(value: Any?, key: String): Double? {
        val raw = when (value) {
            is Number -> value.toDouble()
            is String -> STRICT_NUMBER.matchEntire(value.trim())
                ?.groupValues?.getOrNull(1)
                ?.replace(',', '.')
                ?.toDoubleOrNull()
            else -> null
        } ?: return null
        return if (raw in 0.01..<1.0 &&
            (key.contains("rate", true) || key.contains("ratio", true))) {
            raw * 100.0
        } else raw
    }

    private fun Any?.asText(): String? = when (this) {
        is String -> trim().takeIf(String::isNotBlank)
        is Number -> toString()
        else -> null
    }

    private fun humanLabel(texts: Set<String>, percent: Double): String {
        val selected = texts.asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 2..180 }
            .filterNot { UUID.matches(it) }
            .distinctBy(::normalizeSemantic)
            .sortedByDescending { value ->
                val normalized = normalizeSemantic(value)
                (if (SECOND_SIGNAL.containsMatchIn(normalized)) 10 else 0) +
                    (if (TEXT_PERCENT.containsMatchIn(normalized)) 5 else 0)
            }
            .take(3)
            .toList()
        return selected.joinToString(" · ").takeIf(String::isNotBlank)
            ?: "2da unidad ${percentLabel(percent)}% OFF"
    }

    private fun isProductCollectionKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT)
        return normalized == "products" || normalized == "items" ||
            normalized == "productlist" || normalized == "product_list" ||
            normalized == "catalogitems" || normalized == "catalog_items" ||
            normalized == "results" || normalized == "entries" ||
            normalized == "elements" || normalized == "skus" ||
            normalized == "variants" || normalized == "children" ||
            normalized.contains("products") || normalized.contains("product_list") ||
            normalized.contains("catalogitem")
    }

    private fun normalizeSemantic(value: String): String {
        return Normalizer.normalize(
            value.lowercase(Locale.ROOT).replace('_', ' ').replace('-', ' '),
            Normalizer.Form.NFD,
        )
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun rounded(value: Double): Double = (value * 10.0).roundToInt() / 10.0
    private fun percentKey(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun percentLabel(value: Double): String = if (abs(value - value.roundToInt()) < 0.05) {
        value.roundToInt().toString()
    } else {
        String.format(Locale("es", "AR"), "%.1f", value)
    }

    private val SECOND_SIGNAL = Regex(
        "(?:\\bsecond\\s*(?:unit|item|product)\\b|" +
            "\\bsegunda\\s*(?:unidad|compra)?\\b|\\bsegundo\\s*(?:producto|item)?\\b|" +
            "\\b2\\s*\\.?\\s*(?:da|do|°|º)\\.?\\s*(?:unidad|producto|item)?\\b)"
    )
    private val PERCENTAGE_DESCRIPTOR = Regex("(?:percentage|percent|porcentaje)")
    private val TEXT_PERCENT = Regex(
        "\\b(\\d{1,3}(?:[.,]\\d+)?)\\s*(?:%|por ciento|off|dto|de descuento)(?!\\w)"
    )
    private val PROMO_HUMAN_TEXT = Regex(
        "(?:segunda|segundo|2\\s*\\.?\\s*(?:da|do)|%|off|dto|descuento|promo|oferta|beneficio)"
    )
    private val STRICT_NUMBER = Regex("^\\s*(-?\\d{1,3}(?:[.,]\\d+)?)\\s*%?\\s*$")
    private val UUID = Regex(
        "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    )
    private val TYPE_KEYS = listOf(
        "type", "kind", "mechanic", "mechanicType", "mechanic_type",
        "promotionType", "promotion_type", "benefitType", "benefit_type",
        "discountType", "discount_type", "name", "label", "text",
    )
    private val PERCENT_VALUE_KEYS = listOf(
        "value", "amount", "percentage", "percent", "percentageOff", "percentage_off",
        "discountValue", "discount_value", "benefitValue", "benefit_value", "rate", "ratio",
    )

    private const val INSPECT_DEPTH = 14
    private const val SIGNAL_DEPTH = 12
    private const val MAX_SIGNAL_NODES = 1_200
    private const val MAX_CANDIDATES = 120
}
