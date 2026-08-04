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
        val rawTexts = LinkedHashSet<String>()
        val percentKeys = LinkedHashSet<Double>()
        val typed = mutableListOf<TypedPercent>()
        val promoValues = mutableListOf<Any?>()

        collectTyped(json, typed, depth = 0)
        collectDirectHumanTexts(json, rawTexts)

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

        promoValues.forEach { value ->
            collectTyped(value, typed, depth = 0)
            collectPercentKeys(value, percentKeys, depth = 0)
        }

        val text = normalize(rawTexts.joinToString(" · ")).replace('×', 'x')
        val typedSecond = typed.filter { it.secondUnit }.map { rounded(it.percent) }.distinct()
        val typedDirect = typed.filterNot { it.secondUnit }.map { rounded(it.percent) }.distinct()
        val textSecond = strictSecondPercent(text)
        val structuredMultibuy = structuredMultibuy(json)

        val promo = when {
            typedSecond.size == 1 -> secondUnit(typedSecond.single())
            textSecond != null -> secondUnit(textSecond)
            SECOND_FREE.containsMatchIn(text) -> multibuy(2, 1)
            structuredMultibuy != null -> structuredMultibuy
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

    fun fromProductSubtree(product: JSONObject): PromotionContext? {
        val contexts = mutableListOf<PromotionContext>()
        collectSubtreeContexts(product, depth = 0, isRoot = true, contexts)
        if (contexts.isEmpty()) return null

        val unique = contexts.distinctBy { it.normalized.categoryKey }
        if (unique.size == 1) return bestContext(unique)

        val specific = unique.filter {
            it.normalized.kind == PromotionKind.SECOND_UNIT ||
                it.normalized.kind == PromotionKind.MULTIBUY
        }
        if (specific.map { it.normalized.categoryKey }.distinct().size == 1) {
            return bestContext(specific)
        }

        val oneSpecific = specific.singleOrNull()
        if (oneSpecific != null) {
            val advertised = oneSpecific.normalized.advertisedPercent
            val compatibleDirects = unique
                .filter { it.normalized.kind == PromotionKind.DIRECT_PERCENT }
                .all { direct ->
                    advertised != null &&
                        abs((direct.normalized.advertisedPercent ?: -999.0) - advertised) < 0.05
                }
            if (compatibleDirects) return oneSpecific
        }

        return null
    }

    fun fromPrices(current: Double, original: Double): NormalizedPromotion? {
        if (original <= current || original <= 0.0) return null
        val percent = ((original - current) / original) * 100.0
        return percent.takeIf { it in 0.5..95.0 }?.let(::direct)
    }

    fun isPromoKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT)
        return PROMO_KEYS.any { it.equals(key, ignoreCase = true) } ||
            normalized.contains("promo") || normalized.contains("discount") ||
            normalized.contains("descuento") || normalized.contains("benefit") ||
            normalized.contains("badge") || normalized.contains("offer") ||
            normalized.contains("campaign") || normalized.contains("saving") ||
            normalized.contains("deal") || normalized.contains("commercial") ||
            normalized.contains("mechanic")
    }

    private data class TypedPercent(val percent: Double, val secondUnit: Boolean)

    private fun collectSubtreeContexts(
        node: Any?,
        depth: Int,
        isRoot: Boolean,
        output: MutableList<PromotionContext>,
    ) {
        if (node == null || depth > SUBTREE_DEPTH || output.size >= MAX_SUBTREE_CONTEXTS) return
        when (node) {
            is JSONObject -> {
                if (!isRoot) fromObject(node)?.let(output::add)
                val keys = node.keys()
                while (keys.hasNext() && output.size < MAX_SUBTREE_CONTEXTS) {
                    val key = keys.next()
                    if (isProductCollectionKey(key)) continue
                    val child = node.opt(key)
                    if (child is JSONObject || child is JSONArray) {
                        collectSubtreeContexts(child, depth + 1, false, output)
                    }
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    collectSubtreeContexts(node.opt(index), depth + 1, false, output)
                    if (output.size >= MAX_SUBTREE_CONTEXTS) break
                }
            }
        }
    }

    private fun bestContext(values: List<PromotionContext>): PromotionContext? {
        return values.maxWithOrNull(
            compareBy<PromotionContext> { if (it.unambiguous) 1 else 0 }
                .thenBy { if (it.displayLabel == it.normalized.title) 0 else 1 }
                .thenBy { it.displayLabel.length }
        )
    }

    private fun collectDirectHumanTexts(json: JSONObject, output: MutableSet<String>) {
        (TEXT_KEYS + TYPE_KEYS).distinct().forEach { key ->
            val value = json.opt(key)
            if (value is String) {
                val cleaned = cleanText(value) ?: return@forEach
                val normalized = normalize(cleaned).replace('×', 'x')
                if (HUMAN_SIGNAL.containsMatchIn(normalized)) output += cleaned
            }
        }
    }

    private fun collectTyped(value: Any?, output: MutableList<TypedPercent>, depth: Int) {
        if (value == null || depth > SEARCH_DEPTH || output.size >= MAX_TYPED) return
        when (value) {
            is JSONObject -> {
                val descriptor = TYPE_KEYS
                    .mapNotNull { value.opt(it).stringValue() }
                    .joinToString(" ")
                    .let(::normalize)
                val second = isSecondDescriptor(descriptor)
                val percentage = descriptor.contains("percentage") ||
                    descriptor.contains("percent") || descriptor.contains("porcentaje") ||
                    descriptor.contains("discount") || descriptor.contains("descuento")
                if (percentage || second) {
                    firstStrictNumber(value, PERCENT_VALUE_KEYS)
                        ?.takeIf { it in 0.5..100.0 }
                        ?.let { output += TypedPercent(it, second) }
                }

                val keys = value.keys()
                while (keys.hasNext() && output.size < MAX_TYPED) {
                    val key = keys.next()
                    val normalizedKey = key.lowercase(Locale.ROOT)
                    if (isPromoKey(key) || normalizedKey in META_WRAPPERS) {
                        collectTyped(value.opt(key), output, depth + 1)
                    }
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectTyped(value.opt(index), output, depth + 1)
                    if (output.size >= MAX_TYPED) break
                }
            }
        }
    }

    private fun isSecondDescriptor(descriptor: String): Boolean {
        return descriptor.contains("second unit") || descriptor.contains("second_unit") ||
            descriptor.contains("secondunit") || descriptor.contains("second item") ||
            descriptor.contains("second_item") || descriptor.contains("second product") ||
            descriptor.contains("second_product") || descriptor.contains("segunda unidad") ||
            descriptor.contains("segundo producto") || descriptor.contains("segunda") ||
            descriptor.contains("segundo") || descriptor.contains("2da") ||
            descriptor.contains("2do")
    }

    private fun collectPercentKeys(value: Any?, output: MutableSet<Double>, depth: Int) {
        if (value == null || depth > SEARCH_DEPTH || output.size >= MAX_PERCENTS) return
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (isPercentKey(key)) {
                        strictPercent(child, key)
                            ?.takeIf { it in 0.5..100.0 }
                            ?.let { output += rounded(it) }
                    }
                    if (child is JSONObject || child is JSONArray) {
                        collectPercentKeys(child, output, depth + 1)
                    }
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                collectPercentKeys(value.opt(index), output, depth + 1)
            }
        }
    }

    private fun collectStrings(value: Any?, output: MutableSet<String>, depth: Int) {
        if (value == null || depth > SEARCH_DEPTH || output.size >= MAX_TEXTS) return
        when (value) {
            is String -> cleanText(value)?.let(output::add)
            is JSONObject -> {
                (TEXT_KEYS + TYPE_KEYS).distinct().forEach { key ->
                    cleanText(value.optString(key))?.let(output::add)
                }
                val keys = value.keys()
                while (keys.hasNext() && output.size < MAX_TEXTS) {
                    collectStrings(value.opt(keys.next()), output, depth + 1)
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                collectStrings(value.opt(index), output, depth + 1)
                if (output.size >= MAX_TEXTS) break
            }
        }
    }

    private fun isPercentKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT)
        return PERCENT_KEYS.any { it.equals(key, ignoreCase = true) } ||
            ((normalized.contains("discount") || normalized.contains("saving") ||
                normalized.contains("benefit")) &&
                (normalized.contains("percent") || normalized.contains("percentage") ||
                    normalized.contains("rate") || normalized.contains("ratio")))
    }

    private fun structuredMultibuy(json: JSONObject): NormalizedPromotion? {
        val descriptor = TYPE_KEYS
            .mapNotNull { json.opt(it).stringValue() }
            .joinToString(" ")
            .let(::normalize)
            .replace('×', 'x')
        parseMultibuy(descriptor)?.let { return it }

        val quantity = firstInteger(json, TAKE_VALUE_KEYS)
        val paid = firstInteger(json, PAY_VALUE_KEYS)
        val hasMechanic = descriptor.contains("multibuy") ||
            descriptor.contains("multi buy") || descriptor.contains("take pay") ||
            descriptor.contains("buy pay") || descriptor.contains("quantity")
        return if (hasMechanic && quantity in 2..12 && paid in 1 until quantity) {
            multibuy(quantity, paid)
        } else null
    }

    private fun firstInteger(json: JSONObject, keys: List<String>): Int {
        keys.forEach { key ->
            val value = json.opt(key)
            val number = when (value) {
                is Number -> value.toInt()
                is String -> value.trim().toIntOrNull()
                else -> null
            }
            if (number != null) return number
        }
        return -1
    }

    private fun parseMultibuy(text: String): NormalizedPromotion? {
        val match = MULTIBUY.find(text) ?: return null
        val quantity = match.groupValues[1].toIntOrNull() ?: return null
        val paid = match.groupValues[2].toIntOrNull() ?: return null
        return if (quantity in 2..12 && paid in 1 until quantity) {
            multibuy(quantity, paid)
        } else null
    }

    private fun parseTakePay(text: String): NormalizedPromotion? {
        val match = TAKE_PAY.find(text) ?: return null
        val quantity = match.groupValues[1].toIntOrNull() ?: return null
        val paid = match.groupValues[2].toIntOrNull() ?: return null
        return if (quantity in 2..12 && paid in 1 until quantity) {
            multibuy(quantity, paid)
        } else null
    }

    private fun strictSecondPercent(text: String): Double? {
        return SECOND_FORWARD.find(text)?.groupValues?.getOrNull(1)?.toPercent()
            ?: SECOND_REVERSE.find(text)?.groupValues?.getOrNull(1)?.toPercent()
    }

    private fun directPercent(text: String): Double? {
        return DIRECT_PERCENT.find(text)?.groupValues?.getOrNull(1)?.toPercent()
            ?: DIRECT_OFF.find(text)?.groupValues?.getOrNull(1)?.toPercent()
    }

    private fun secondUnit(percent: Double): NormalizedPromotion {
        val value = rounded(percent)
        return NormalizedPromotion(
            categoryKey = "second:${key(value)}",
            title = "2da unidad ${label(value)}% OFF",
            effectivePercent = value / 2.0,
            kind = PromotionKind.SECOND_UNIT,
            advertisedPercent = value,
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
        val value = rounded(percent)
        return NormalizedPromotion(
            categoryKey = "percent:${key(value)}",
            title = "${label(value)}% OFF",
            effectivePercent = value,
            kind = PromotionKind.DIRECT_PERCENT,
            advertisedPercent = value,
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
        val normalized = normalize(value)
        return UUID.matches(value.trim()) || LONG_HEX.matches(value.trim()) ||
            normalized in INTERNAL_TOKENS ||
            (normalized.matches(Regex("^[a-z_]+$")) &&
                !HUMAN_SIGNAL.containsMatchIn(normalized))
    }

    private fun firstStrictNumber(json: JSONObject, keys: List<String>): Double? {
        keys.forEach { key -> strictPercent(json.opt(key), key)?.let { return it } }
        return null
    }

    private fun strictPercent(value: Any?, key: String): Double? {
        val raw = when (value) {
            is Number -> value.toDouble()
            is String -> {
                val match = STRICT_PERCENT.matchEntire(value.trim()) ?: return null
                match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            }
            else -> return null
        }
        return if (raw in 0.01..<1.0 &&
            (key.contains("rate", true) || key.contains("ratio", true))) {
            raw * 100.0
        } else raw
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
            .replace(Regex("\\s+"), " ")
            .trim()
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

    private fun String.toPercent(): Double? = replace(',', '.').toDoubleOrNull()
    private fun rounded(value: Double): Double = (value * 10.0).roundToInt() / 10.0
    private fun key(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun label(value: Double): String = if (abs(value - value.roundToInt()) < 0.05) {
        value.roundToInt().toString()
    } else String.format(Locale("es", "AR"), "%.1f", value)

    private val MULTIBUY = Regex("\\b(\\d{1,2})\\s*[x×]\\s*(\\d{1,2})\\b")
    private val TAKE_PAY = Regex(
        "lleva(?:ndo|te|á|a)?\\s*(\\d{1,2}).{0,35}?paga(?:ndo|á|a)?\\s*(\\d{1,2})"
    )
    private val SECOND_TOKEN = "(?:2\\s*\\.?\\s*(?:da|do|°|º)\\.?|segunda|segundo)"
    private val SECOND_FREE = Regex(
        "\\b$SECOND_TOKEN\\s*(?:unidad|producto|item)?\\s*(?:gratis|sin cargo)\\b"
    )
    private val SECOND_FORWARD = Regex(
        "\\b$SECOND_TOKEN\\s*(?:unidad|producto|item)?\\s*" +
            "(?:al|con|a|:|-)?\\s*(\\d{1,3}(?:[.,]\\d+)?)\\s*%?\\s*" +
            "(?:off|dto|de descuento|descuento)?\\b"
    )
    private val SECOND_REVERSE = Regex(
        "\\b(\\d{1,3}(?:[.,]\\d+)?)\\s*%?\\s*" +
            "(?:off|dto|de descuento|descuento).{0,35}?\\b$SECOND_TOKEN" +
            "\\s*(?:unidad|producto|item)?\\b"
    )
    private val DIRECT_PERCENT = Regex(
        "(?:-|ahorra|hasta|descuento)?\\s*(\\d{1,3}(?:[.,]\\d+)?)\\s*%"
    )
    private val DIRECT_OFF = Regex("\\b(\\d{1,3}(?:[.,]\\d+)?)\\s*(?:off|dto)\\b")
    private val HUMAN_SIGNAL = Regex(
        "(?:\\d{1,3}(?:[.,]\\d+)?\\s*%|\\d{1,2}\\s*[x×]\\s*\\d{1,2}|" +
            "segunda|segundo|2\\s*\\.?\\s*(?:da|do)|off|descuento|ahorra|" +
            "oferta|promo|gratis|lleva|paga)"
    )
    private val STRICT_PERCENT = Regex(
        "^\\s*(-?\\d{1,3}(?:[.,]\\d+)?)\\s*%?\\s*$"
    )
    private val UUID = Regex(
        "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    )
    private val LONG_HEX = Regex("(?i)^[0-9a-f]{18,}$")

    private val PERCENT_KEYS = listOf(
        "discountPercentage", "discount_percentage", "discountPercent", "discount_percent",
        "percentageOff", "percentage_off", "percentOff", "percent_off",
        "discountRate", "discount_rate", "savingsPercentage", "savings_percentage",
        "benefitPercentage", "benefit_percentage", "percentage", "percent",
    )
    private val PERCENT_VALUE_KEYS = listOf(
        "percentage", "percent", "value", "discountValue", "discount_value",
        "benefitValue", "benefit_value", "amount", "rate", "ratio",
    )
    private val TYPE_KEYS = listOf(
        "type", "kind", "mechanic", "mechanics", "scope", "mode", "target",
        "application", "promotionType", "promotion_type", "discountType", "discount_type",
        "benefitType", "benefit_type", "appliesTo", "applies_to", "ruleType", "rule_type",
    )
    private val PROMO_KEYS = listOf(
        "promotionLabel", "promotion_label", "promotionText", "promotion_text",
        "discountLabel", "discount_label", "discountText", "discount_text",
        "promoLabel", "promo_label", "promoText", "promo_text", "badge", "badges",
        "promotion", "promotions", "offer", "offers", "campaign", "campaigns",
        "tags", "benefit", "benefits", "deal", "deals", "commercial", "commercialData",
        "commercial_data", "mechanic", "mechanics",
    )
    private val TEXT_KEYS = listOf(
        "name", "title", "label", "text", "description", "message", "value",
        "type", "scope", "subtitle", "caption",
    )
    private val TAKE_VALUE_KEYS = listOf(
        "quantity", "take", "buy", "buyQuantity", "buy_quantity", "units", "requiredQuantity",
    )
    private val PAY_VALUE_KEYS = listOf(
        "paid", "pay", "payQuantity", "pay_quantity", "chargedQuantity", "charged_quantity",
    )
    private val META_WRAPPERS = setOf(
        "data", "metadata", "meta", "configuration", "config", "rules", "rule",
        "benefit", "benefits", "conditions", "condition", "commercial", "pricing",
        "mechanic", "mechanics", "campaign", "campaigns",
    )
    private val INTERNAL_TOKENS = setOf(
        "percentage", "percent", "all", "percentage all", "percentage_discount",
        "direct", "global", "uuid", "id", "second_unit", "second unit",
    )

    private const val SEARCH_DEPTH = 7
    private const val SUBTREE_DEPTH = 8
    private const val MAX_TEXTS = 32
    private const val MAX_TYPED = 40
    private const val MAX_PERCENTS = 40
    private const val MAX_SUBTREE_CONTEXTS = 48
}
