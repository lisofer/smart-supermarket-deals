package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.PromotionKind
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PromotionContext(
    val normalized: NormalizedPromotion,
    val displayLabel: String,
    val unambiguous: Boolean,
)

internal data class NormalizedPromotion(
    val categoryKey: String,
    val title: String,
    val effectivePercent: Double,
    val kind: PromotionKind,
    val advertisedPercent: Double?,
)

internal object PromotionInterpreter {
    fun fromObject(json: JSONObject): PromotionContext? {
        val promoValues = mutableListOf<Any?>()
        val rawTexts = LinkedHashSet<String>()
        val percentKeys = LinkedHashSet<Double>()

        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.opt(key)
            if (isPercentKey(key)) {
                strictPercent(value, key)
                    ?.takeIf { it in 0.5..100.0 }
                    ?.let { percentKeys += rounded(it) }
            }
            if (isPromoKey(key)) {
                promoValues += value
                collectStrings(value, rawTexts, depth = 0)
            }
        }

        val typed = mutableListOf<TypedPercent>()
        // Inspect the object itself as well as nested promotion wrappers. Some PedidosYa
        // payloads expose type/value directly instead of placing them under "promotion".
        collectTyped(json, typed, depth = 0)
        collectPercentKeys(json, percentKeys, depth = 0)

        val text = normalize(rawTexts.joinToString(" · ")).replace('×', 'x')
        val typedSecond = typed.filter { it.secondUnit }.map { rounded(it.percent) }.distinct()
        val typedDirect = typed.filterNot { it.secondUnit }.map { rounded(it.percent) }.distinct()
        val textSecond = strictSecondPercent(text)

        val promo = when {
            typedSecond.size == 1 -> secondUnit(typedSecond.single())
            textSecond != null -> secondUnit(textSecond)
            SECOND_FREE.containsMatchIn(text) -> multibuy(2, 1)
            MULTIBUY.containsMatchIn(text) -> parseMultibuy(text)
            TAKE_PAY.containsMatchIn(text) -> parseTakePay(text)
            typedDirect.size == 1 -> direct(typedDirect.single())
            percentKeys.size == 1 -> direct(percentKeys.single())
            else -> directPercent(text)?.let(::direct)
        } ?: return null

        val allPercents = buildList {
            addAll(typedSecond)
            addAll(typedDirect)
            addAll(percentKeys)
            textSecond?.let(::add)
            directPercent(text)?.let(::add)
        }.map(::rounded).distinct()

        return PromotionContext(
            normalized = promo,
            displayLabel = humanLabel(rawTexts.toList(), promo),
            unambiguous = allPercents.size <= 1,
        )
    }

    fun fromPrices(current: Double, original: Double): NormalizedPromotion? {
        if (original <= current || original <= 0.0) return null
        val percent = ((original - current) / original) * 100.0
        return percent.takeIf { it in 0.5..95.0 }?.let(::direct)
    }

    fun isPromoKey(key: String): Boolean {
        val n = key.lowercase(Locale.ROOT)
        return PROMO_KEYS.any { it.equals(key, ignoreCase = true) } ||
            n.contains("promo") || n.contains("discount") || n.contains("descuento") ||
            n.contains("benefit") || n.contains("badge") || n.contains("offer") ||
            n.contains("campaign") || n.contains("saving") || n.contains("deal")
    }

    private data class TypedPercent(val percent: Double, val secondUnit: Boolean)

    private fun collectTyped(value: Any?, out: MutableList<TypedPercent>, depth: Int) {
        if (value == null || depth > SEARCH_DEPTH || out.size >= MAX_TYPED) return
        when (value) {
            is JSONObject -> {
                val descriptor = TYPE_KEYS.mapNotNull { value.opt(it).stringValue() }
                    .joinToString(" ").let(::normalize)
                val percentage = descriptor.contains("percentage") ||
                    descriptor.contains("percent") || descriptor.contains("porcentaje")
                if (percentage) {
                    firstStrictNumber(value, PERCENT_VALUE_KEYS)
                        ?.takeIf { it in 0.5..100.0 }
                        ?.let { p ->
                            out += TypedPercent(
                                percent = p,
                                secondUnit = SECOND_DESCRIPTOR.containsMatchIn(descriptor),
                            )
                        }
                }
                val keys = value.keys()
                while (keys.hasNext() && out.size < MAX_TYPED) {
                    val key = keys.next()
                    if (isPromoKey(key) || key.lowercase(Locale.ROOT) in META_WRAPPERS) {
                        collectTyped(value.opt(key), out, depth + 1)
                    }
                }
            }
            is JSONArray -> for (i in 0 until value.length()) {
                collectTyped(value.opt(i), out, depth + 1)
                if (out.size >= MAX_TYPED) break
            }
        }
    }

    private fun collectPercentKeys(value: Any?, out: MutableSet<Double>, depth: Int) {
        if (value == null || depth > SEARCH_DEPTH || out.size >= MAX_PERCENTS) return
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (isPercentKey(key)) {
                        strictPercent(child, key)
                            ?.takeIf { it in 0.5..100.0 }
                            ?.let { out += rounded(it) }
                    }
                    if (child is JSONObject || child is JSONArray) {
                        collectPercentKeys(child, out, depth + 1)
                    }
                }
            }
            is JSONArray -> for (i in 0 until value.length()) {
                collectPercentKeys(value.opt(i), out, depth + 1)
            }
        }
    }

    private fun collectStrings(value: Any?, out: MutableSet<String>, depth: Int) {
        if (value == null || depth > SEARCH_DEPTH || out.size >= MAX_TEXTS) return
        when (value) {
            is String -> cleanText(value)?.let(out::add)
            is JSONObject -> {
                TEXT_KEYS.forEach { key -> cleanText(value.optString(key))?.let(out::add) }
                val keys = value.keys()
                while (keys.hasNext() && out.size < MAX_TEXTS) {
                    collectStrings(value.opt(keys.next()), out, depth + 1)
                }
            }
            is JSONArray -> for (i in 0 until value.length()) {
                collectStrings(value.opt(i), out, depth + 1)
                if (out.size >= MAX_TEXTS) break
            }
        }
    }

    private fun isPercentKey(key: String): Boolean {
        val n = key.lowercase(Locale.ROOT)
        return PERCENT_KEYS.any { it.equals(key, ignoreCase = true) } ||
            ((n.contains("discount") || n.contains("saving") || n.contains("benefit")) &&
                (n.contains("percent") || n.contains("percentage") ||
                    n.contains("rate") || n.contains("ratio")))
    }

    private fun parseMultibuy(text: String): NormalizedPromotion? {
        val m = MULTIBUY.find(text) ?: return null
        val quantity = m.groupValues[1].toIntOrNull() ?: return null
        val paid = m.groupValues[2].toIntOrNull() ?: return null
        return if (quantity in 2..12 && paid in 1 until quantity) multibuy(quantity, paid) else null
    }

    private fun parseTakePay(text: String): NormalizedPromotion? {
        val m = TAKE_PAY.find(text) ?: return null
        val quantity = m.groupValues[1].toIntOrNull() ?: return null
        val paid = m.groupValues[2].toIntOrNull() ?: return null
        return if (quantity in 2..12 && paid in 1 until quantity) multibuy(quantity, paid) else null
    }

    private fun strictSecondPercent(text: String): Double? {
        return SECOND_FORWARD.find(text)?.groupValues?.getOrNull(1)?.toPercent()
            ?: SECOND_FORWARD_OFF.find(text)?.groupValues?.getOrNull(1)?.toPercent()
            ?: SECOND_REVERSE.find(text)?.groupValues?.getOrNull(1)?.toPercent()
    }

    private fun directPercent(text: String): Double? {
        return DIRECT_PERCENT.find(text)?.groupValues?.getOrNull(1)?.toPercent()
            ?: DIRECT_OFF.find(text)?.groupValues?.getOrNull(1)?.toPercent()
    }

    private fun secondUnit(percent: Double): NormalizedPromotion {
        val p = rounded(percent)
        return NormalizedPromotion(
            categoryKey = "second:${key(p)}",
            title = "2da unidad ${label(p)}% OFF",
            effectivePercent = p / 2.0,
            kind = PromotionKind.SECOND_UNIT,
            advertisedPercent = p,
        )
    }

    private fun multibuy(quantity: Int, paid: Int): NormalizedPromotion {
        return NormalizedPromotion(
            categoryKey = "multibuy:${quantity}x$paid",
            title = "${quantity}x$paid",
            effectivePercent = ((quantity - paid).toDouble() / quantity) * 100.0,
            kind = PromotionKind.MULTIBUY,
            advertisedPercent = null,
        )
    }

    private fun direct(percent: Double): NormalizedPromotion {
        val p = rounded(percent)
        return NormalizedPromotion(
            categoryKey = "percent:${key(p)}",
            title = "${label(p)}% OFF",
            effectivePercent = p,
            kind = PromotionKind.DIRECT_PERCENT,
            advertisedPercent = p,
        )
    }

    private fun humanLabel(raw: List<String>, promo: NormalizedPromotion): String {
        val candidates = raw.asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 2..180 }
            .filterNot(::internalMetadata)
            .filter { HUMAN_SIGNAL.containsMatchIn(normalize(it).replace('×', 'x')) }
            .distinctBy(::normalize)
            .take(2)
            .toList()
        return candidates.joinToString(" · ").takeIf(String::isNotBlank) ?: promo.title
    }

    private fun internalMetadata(value: String): Boolean {
        val n = normalize(value)
        return UUID.matches(value.trim()) || LONG_HEX.matches(value.trim()) ||
            n in INTERNAL_TOKENS ||
            (n.matches(Regex("^[a-z_]+$")) && !HUMAN_SIGNAL.containsMatchIn(n))
    }

    private fun firstStrictNumber(json: JSONObject, keys: List<String>): Double? {
        keys.forEach { key -> strictPercent(json.opt(key), key)?.let { return it } }
        return null
    }

    private fun strictPercent(value: Any?, key: String): Double? {
        val raw = when (value) {
            is Number -> value.toDouble()
            is String -> {
                val m = STRICT_PERCENT.matchEntire(value.trim()) ?: return null
                m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            }
            else -> return null
        }
        return if (raw in 0.01..<1.0 &&
            (key.contains("rate", true) || key.contains("ratio", true))) raw * 100.0 else raw
    }

    private fun Any?.stringValue(): String? = when (this) {
        is String -> trim().takeIf(String::isNotBlank)
        is Number -> toString()
        else -> null
    }

    private fun cleanText(raw: String): String? {
        val value = raw.replace(Regex("\\s+"), " ").trim()
        return value.takeIf { it.length in 2..220 }
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+"), " ").trim()
    }

    private fun String.toPercent(): Double? = replace(',', '.').toDoubleOrNull()
    private fun rounded(value: Double): Double = (value * 10.0).roundToInt() / 10.0
    private fun key(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun label(value: Double): String = if (abs(value - value.roundToInt()) < 0.05) {
        value.roundToInt().toString()
    } else {
        String.format(Locale("es", "AR"), "%.1f", value)
    }

    private val MULTIBUY = Regex("\\b(\\d{1,2})\\s*x\\s*(\\d{1,2})\\b")
    private val TAKE_PAY = Regex("lleva(?:ndo)?\\s*(\\d{1,2}).{0,30}?paga(?:ndo)?\\s*(\\d{1,2})")
    private val SECOND_FREE = Regex(
        "\\b(?:2\\s*(?:da|do)\\.?|2\\s*(?:°|º)|segunda|segundo)\\s*" +
            "(?:unidad|producto|articulo|item)?\\s*(?:gratis|sin cargo)\\b"
    )
    private val SECOND_FORWARD = Regex(
        "\\b(?:2\\s*(?:da|do)\\.?|2\\s*(?:°|º)|segunda|segundo)\\s*" +
            "(?:unidad|producto|articulo|item)?\\s*(?:al|a|con|:)?\\s*" +
            "(\\d{1,3}(?:[.,]\\d+)?)\\s*%\\s*(?:off|dto|de descuento|descuento)?\\b"
    )
    private val SECOND_FORWARD_OFF = Regex(
        "\\b(?:2\\s*(?:da|do)\\.?|2\\s*(?:°|º)|segunda|segundo)\\s*" +
            "(?:unidad|producto|articulo|item)?\\s*(?:al|a|con|:)?\\s*" +
            "(\\d{1,3}(?:[.,]\\d+)?)\\s*(?:off|dto|de descuento|descuento)\\b"
    )
    private val SECOND_REVERSE = Regex(
        "\\b(\\d{1,3}(?:[.,]\\d+)?)\\s*%\\s*(?:off|dto|de descuento|descuento)?" +
            ".{0,30}?\\b(?:2\\s*(?:da|do)\\.?|2\\s*(?:°|º)|segunda|segundo)\\s*" +
            "(?:unidad|producto|articulo|item)?\\b"
    )
    private val SECOND_DESCRIPTOR = Regex(
        "(?:second[_\\s-]*(?:unit|item|product)|segunda(?:\\s+unidad|\\s+compra)?|" +
            "segundo(?:\\s+producto)?|2da|2do)"
    )
    private val DIRECT_PERCENT = Regex("(?:-|ahorra|hasta|descuento)?\\s*(\\d{1,3}(?:[.,]\\d+)?)\\s*%")
    private val DIRECT_OFF = Regex("\\b(\\d{1,3}(?:[.,]\\d+)?)\\s*(?:off|dto)\\b")
    private val HUMAN_SIGNAL = Regex(
        "(?:\\d{1,3}(?:[.,]\\d+)?\\s*%|\\d{1,2}\\s*x\\s*\\d{1,2}|" +
            "segunda|segundo|2da|2do|off|descuento|ahorra|oferta|promo)"
    )
    private val STRICT_PERCENT = Regex("^\\s*(-?\\d{1,3}(?:[.,]\\d+)?)\\s*%?\\s*$")
    private val UUID = Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val LONG_HEX = Regex("(?i)^[0-9a-f]{18,}$")

    private val PERCENT_KEYS = listOf(
        "discountPercentage", "discount_percentage", "discountPercent", "discount_percent",
        "percentageOff", "percentage_off", "percentOff", "percent_off",
        "discountRate", "discount_rate", "savingsPercentage", "savings_percentage",
        "benefitPercentage", "benefit_percentage",
    )
    private val PERCENT_VALUE_KEYS = listOf(
        "percentage", "percent", "value", "discountValue", "discount_value",
        "benefitValue", "benefit_value", "amount", "rate", "ratio",
    )
    private val TYPE_KEYS = listOf(
        "type", "kind", "mechanic", "mechanics", "scope", "mode", "target",
        "application", "promotionType", "promotion_type", "discountType", "discount_type",
        "benefitType", "benefit_type", "appliesTo", "applies_to",
    )
    private val PROMO_KEYS = listOf(
        "promotionLabel", "promotion_label", "promotionText", "promotion_text",
        "discountLabel", "discount_label", "discountText", "discount_text",
        "promoLabel", "promo_label", "promoText", "promo_text", "badge", "badges",
        "promotion", "promotions", "offer", "offers", "campaign", "campaigns",
        "tags", "benefit", "benefits", "deal", "deals",
    )
    private val TEXT_KEYS = listOf(
        "name", "title", "label", "text", "description", "message", "value", "type", "scope",
    )
    private val META_WRAPPERS = setOf(
        "data", "metadata", "meta", "configuration", "config", "rules", "rule",
        "benefit", "benefits", "conditions", "condition",
    )
    private val INTERNAL_TOKENS = setOf(
        "percentage", "percent", "all", "percentage all", "percentage_discount",
        "direct", "global", "uuid", "id",
    )
    private const val SEARCH_DEPTH = 6
    private const val MAX_TEXTS = 20
    private const val MAX_TYPED = 24
    private const val MAX_PERCENTS = 24
}
