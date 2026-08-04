package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.PromotionKind
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fallback for DOM payloads such as "2DA UNIDAD · 50% OFF". */
internal object SplitSecondUnitTextResolver {
    fun fromProductSubtree(product: JSONObject): PromotionContext? {
        val texts = LinkedHashSet<String>()
        val percentages = LinkedHashSet<Double>()
        collect(product, depth = 0, texts = texts, percentages = percentages)

        val combined = normalize(texts.joinToString(" ")).replace('×', 'x')
        if (!SECOND_SIGNAL.containsMatchIn(combined)) return null

        TEXT_PERCENT.findAll(combined).forEach { match ->
            match.groupValues.getOrNull(1)
                ?.replace(',', '.')
                ?.toDoubleOrNull()
                ?.takeIf { it in 0.5..100.0 }
                ?.let(percentages::add)
        }
        if (percentages.size != 1) return null

        val percent = rounded(percentages.single())
        val title = "2da unidad ${label(percent)}% OFF"
        val display = texts.asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 2..240 }
            .filter { value ->
                val normalized = normalize(value)
                SECOND_SIGNAL.containsMatchIn(normalized) || TEXT_PERCENT.containsMatchIn(normalized)
            }
            .distinctBy(::normalize)
            .take(5)
            .joinToString(" · ")
            .takeIf(String::isNotBlank)
            ?: title

        return PromotionContext(
            normalized = NormalizedPromotion(
                categoryKey = "second:${String.format(Locale.US, "%.1f", percent)}",
                title = title,
                effectivePercent = percent / 2.0,
                kind = PromotionKind.SECOND_UNIT,
                advertisedPercent = percent,
            ),
            displayLabel = display,
            unambiguous = true,
        )
    }

    private fun collect(
        node: Any?,
        depth: Int,
        texts: MutableSet<String>,
        percentages: MutableSet<Double>,
    ) {
        if (node == null || depth > MAX_DEPTH || texts.size >= MAX_TEXTS) return
        when (node) {
            is String -> {
                val value = node.replace(Regex("\\s+"), " ").trim()
                if (value.length in 2..500 &&
                    (SECOND_SIGNAL.containsMatchIn(normalize(value)) ||
                        TEXT_PERCENT.containsMatchIn(normalize(value)))) {
                    texts += value
                }
            }

            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (isProductCollectionKey(key)) continue
                    val value = node.opt(key)
                    if (isPercentKey(key)) {
                        parsePercent(value, key)
                            ?.takeIf { it in 0.5..100.0 }
                            ?.let(percentages::add)
                    }
                    collect(value, depth + 1, texts, percentages)
                    if (texts.size >= MAX_TEXTS) break
                }
            }

            is JSONArray -> {
                for (index in 0 until node.length()) {
                    collect(node.opt(index), depth + 1, texts, percentages)
                    if (texts.size >= MAX_TEXTS) break
                }
            }
        }
    }

    private fun isPercentKey(key: String): Boolean {
        val value = key.lowercase(Locale.ROOT)
        return value == "percentage" || value == "percent" ||
            value.contains("discountpercentage") || value.contains("discount_percentage") ||
            value.contains("discountpercent") || value.contains("discount_percent") ||
            value.contains("percentageoff") || value.contains("percentage_off") ||
            value.contains("percentoff") || value.contains("percent_off") ||
            ((value.contains("discount") || value.contains("benefit") ||
                value.contains("saving")) &&
                (value.contains("percent") || value.contains("rate") || value.contains("ratio")))
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
            (key.contains("rate", true) || key.contains("ratio", true))) raw * 100.0 else raw
    }

    private fun isProductCollectionKey(key: String): Boolean {
        val value = key.lowercase(Locale.ROOT)
        return value == "products" || value == "items" || value == "productlist" ||
            value == "product_list" || value == "catalogitems" || value == "catalog_items" ||
            value == "results" || value == "entries" || value == "elements" ||
            value == "skus" || value == "variants" || value == "children" ||
            value.contains("products") || value.contains("product_list") ||
            value.contains("catalogitem")
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun rounded(value: Double): Double = (value * 10.0).roundToInt() / 10.0
    private fun label(value: Double): String = if (abs(value - value.roundToInt()) < 0.05) {
        value.roundToInt().toString()
    } else {
        String.format(Locale("es", "AR"), "%.1f", value)
    }

    private val SECOND_SIGNAL = Regex(
        "(?:\\bsecond[_\\s-]*(?:unit|item|product)\\b|" +
            "\\bsegunda\\s*(?:unidad|compra)?\\b|\\bsegundo\\s*(?:producto|item)?\\b|" +
            "\\b2\\s*\\.?\\s*(?:da|do|°|º)\\.?\\s*(?:unidad|producto|item)?\\b)"
    )
    private val TEXT_PERCENT = Regex(
        "\\b(\\d{1,3}(?:[.,]\\d+)?)\\s*(?:%|off|dto|de descuento)(?!\\w)"
    )
    private val STRICT_NUMBER = Regex("^\\s*(-?\\d{1,3}(?:[.,]\\d+)?)\\s*%?\\s*$")

    private const val MAX_DEPTH = 14
    private const val MAX_TEXTS = 100
}
