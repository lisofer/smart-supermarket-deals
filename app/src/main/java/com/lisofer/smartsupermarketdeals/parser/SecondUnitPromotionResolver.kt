package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.PromotionKind
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PedidosYa does not always keep the mechanic and its percentage in the same JSON object.
 * A common shape is `mechanic = SECOND_UNIT` in one node and `value = 50` in a sibling.
 * PromotionInterpreter intentionally treats an isolated percentage as a direct discount, so
 * this resolver reconstructs the commercial context at product-subtree level first.
 */
internal object SecondUnitPromotionResolver {
    fun fromProductSubtree(product: JSONObject): PromotionContext? {
        val candidates = mutableListOf<Candidate>()
        inspect(
            node = product,
            depth = 0,
            inheritedCommercial = false,
            output = candidates,
        )

        val best = candidates
            .filter { it.percent in 0.5..100.0 }
            .maxWithOrNull(
                compareBy<Candidate> { it.score }
                    .thenByDescending { it.depth }
                    .thenBy { it.label.length }
            )
            ?: return null

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
        var nodes: Int = 0,
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
                val signals = collectSignals(
                    node = node,
                    depth = 0,
                    inheritedSecond = false,
                    commercialOnly = !commercialHere,
                )
                if (signals.secondUnit && signals.percentages.size == 1) {
                    val percent = signals.percentages.single()
                    val label = humanLabel(signals.humanTexts, percent)
                    val score =
                        (if (commercialHere) 80 else 0) +
                            (if (signals.localSecondUnit) 45 else 0) +
                            depth * 4 -
                            signals.nodes.coerceAtMost(40)
                    output += Candidate(percent, label, score, depth)
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

    private fun collectSignals(
        node: Any?,
        depth: Int,
        inheritedSecond: Boolean,
        commercialOnly: Boolean,
    ): Signals {
        val output = Signals(secondUnit = inheritedSecond)
        collectInto(node, depth, inheritedSecond, commercialOnly, output)
        return output
    }

    private fun collectInto(
        node: Any?,
        depth: Int,
        inheritedSecond: Boolean,
        commercialOnly: Boolean,
        output: Signals,
    ) {
        if (node == null || depth > SIGNAL_DEPTH || output.nodes >= MAX_SIGNAL_NODES) return
        output.nodes += 1

        when (node) {
            is String -> {
                val cleaned = node.replace(Regex("\\s+"), " ").trim()
                if (cleaned.length !in 1..260) return
                val normalized = normalize(cleaned).replace('×', 'x')
                val secondHere = SECOND_TEXT.containsMatchIn(normalized)
                if (secondHere) {
                    output.secondUnit = true
                    output.localSecondUnit = true
                    output.humanTexts += cleaned
                }
                TEXT_PERCENT.findAll(normalized).forEach { match ->
                    match.groupValues.getOrNull(1)
                        ?.replace(',', '.')
                        ?.toDoubleOrNull()
                        ?.takeIf { it in 0.5..100.0 }
                        ?.let(output.percentages::add)
                }
                if (PROMO_HUMAN_TEXT.containsMatchIn(normalized)) {
                    output.humanTexts += cleaned
                }
            }

            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext() && output.nodes < MAX_SIGNAL_NODES) {
                    val key = keys.next()
                    if (isProductCollectionKey(key)) continue
                    val value = node.opt(key)
                    val keySecond = isSecondKey(key)
                    val valueSecond = when (value) {
                        is String -> SECOND_TEXT.containsMatchIn(normalize(value).replace('×', 'x'))
                        else -> false
                    }
                    val secondHere = inheritedSecond || keySecond || valueSecond
                    if (keySecond || valueSecond) {
                        output.secondUnit = true
                        output.localSecondUnit = true
                    }

                    if (isPercentKey(key)) {
                        parsePercent(value, key)
                            ?.takeIf { it in 0.5..100.0 }
                            ?.let(output.percentages::add)
                    }

                    val shouldTraverse = !commercialOnly ||
                        isCommercialKey(key) ||
                        secondHere ||
                        key.lowercase(Locale.ROOT) in GENERIC_WRAPPERS
                    if (shouldTraverse || value is String) {
                        collectInto(value, depth + 1, secondHere, commercialOnly, output)
                    }
                }
            }

            is JSONArray -> {
                for (index in 0 until node.length()) {
                    collectInto(node.opt(index), depth + 1, inheritedSecond, commercialOnly, output)
                    if (output.nodes >= MAX_SIGNAL_NODES) break
                }
            }
        }
    }

    private fun looksCommercial(json: JSONObject): Boolean {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (isCommercialKey(key) || isSecondKey(key)) return true
            val value = json.opt(key)
            if (value is String) {
                val normalized = normalize(value)
                if (SECOND_TEXT.containsMatchIn(normalized) ||
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
        val normalized = normalize(key.replace('_', ' ').replace('-', ' '))
        return SECOND_KEY.containsMatchIn(normalized)
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

    private fun humanLabel(texts: Set<String>, percent: Double): String {
        val selected = texts.asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 2..180 }
            .filterNot { UUID.matches(it) }
            .distinctBy(::normalize)
            .sortedByDescending { value ->
                val normalized = normalize(value)
                (if (SECOND_TEXT.containsMatchIn(normalized)) 10 else 0) +
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

    private fun normalize(value: String): String {
        return Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
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

    private val SECOND_TEXT = Regex(
        "(?:\\bsecond\\s*(?:unit|item|product)\\b|\\bsecond_(?:unit|item|product)\\b|" +
            "\\bsegunda\\s*(?:unidad|compra)?\\b|\\bsegundo\\s*(?:producto|item)?\\b|" +
            "\\b2\\s*\\.?\\s*(?:da|do|°|º)\\.?\\s*(?:unidad|producto|item)?\\b)"
    )
    private val SECOND_KEY = Regex(
        "(?:second\\s*(?:unit|item|product)|segunda\\s*(?:unidad|compra)?|" +
            "segundo\\s*(?:producto|item)?|2\\s*(?:da|do))"
    )
    private val TEXT_PERCENT = Regex(
        "\\b(\\d{1,3}(?:[.,]\\d+)?)\\s*(?:%|por ciento|off|dto|de descuento)\\b"
    )
    private val PROMO_HUMAN_TEXT = Regex(
        "(?:segunda|segundo|2\\s*\\.?\\s*(?:da|do)|%|off|dto|descuento|promo|oferta|beneficio)"
    )
    private val STRICT_NUMBER = Regex("^\\s*(-?\\d{1,3}(?:[.,]\\d+)?)\\s*%?\\s*$")
    private val UUID = Regex(
        "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    )
    private val GENERIC_WRAPPERS = setOf(
        "data", "metadata", "meta", "configuration", "config", "rules", "rule",
        "conditions", "condition", "content", "details", "attributes", "value", "values",
    )

    private const val INSPECT_DEPTH = 12
    private const val SIGNAL_DEPTH = 8
    private const val MAX_SIGNAL_NODES = 500
    private const val MAX_CANDIDATES = 80
}
