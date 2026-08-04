package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.PromotionEvidence
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
    val evidence: PromotionEvidence = PromotionEvidence.PRODUCT_STRUCTURE,
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
        val inherited = json.optBoolean("__smartDealsInherited", false)
        val rawTexts = LinkedHashSet<String>()
        collectHumanTexts(json, rawTexts, depth = 0, insidePromo = false)
        val text = normalize(rawTexts.joinToString(" · ")).replace('×', 'x')

        val signals = StructuredSignals()
        collectStructured(json, signals, depth = 0, insidePromo = false)

        val textSecond = strictSecondPercent(text)
        val textMultibuy = parseMultibuy(text) ?: parseTakePay(text)
        val textDirect = explicitDirectPercent(text)
        val structuredSecond = signals.percentages.singleOrNull()
            ?.takeIf { signals.secondUnit }
            ?.let(::secondUnit)
        val structuredDirect = signals.percentages.singleOrNull()
            ?.takeIf { signals.directPercentage && !signals.secondUnit }
            ?.let(::direct)

        val chosen = when {
            textSecond != null -> Detection(secondUnit(textSecond), PromotionEvidence.PRODUCT_TEXT)
            structuredSecond != null -> Detection(structuredSecond, PromotionEvidence.PRODUCT_STRUCTURE)
            SECOND_FREE.containsMatchIn(text) -> Detection(multibuy(2, 1), PromotionEvidence.PRODUCT_TEXT)
            textMultibuy != null -> Detection(textMultibuy, PromotionEvidence.PRODUCT_TEXT)
            signals.multibuy != null -> Detection(signals.multibuy!!, PromotionEvidence.PRODUCT_STRUCTURE)
            textDirect != null -> Detection(direct(textDirect), PromotionEvidence.PRODUCT_TEXT)
            structuredDirect != null -> Detection(structuredDirect, PromotionEvidence.PRODUCT_STRUCTURE)
            else -> null
        } ?: return null

        val evidence = if (inherited) PromotionEvidence.INHERITED_SECTION else chosen.evidence
        val allPercents = buildList {
            addAll(signals.percentages)
            textSecond?.let(::add)
            textDirect?.let(::add)
        }.map(::rounded).distinct()

        return PromotionContext(
            normalized = chosen.promotion,
            displayLabel = humanLabel(rawTexts.toList(), chosen.promotion),
            unambiguous = allPercents.size <= 1,
            evidence = evidence,
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

    private data class Detection(
        val promotion: NormalizedPromotion,
        val evidence: PromotionEvidence,
    )

    private data class StructuredSignals(
        var secondUnit: Boolean = false,
        var directPercentage: Boolean = false,
        var multibuy: NormalizedPromotion? = null,
        val percentages: LinkedHashSet<Double> = LinkedHashSet(),
    )

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
            compareBy<PromotionContext> { evidenceScore(it.evidence) }
                .thenBy { if (it.unambiguous) 1 else 0 }
                .thenBy { if (it.displayLabel == it.normalized.title) 0 else 1 }
                .thenBy { it.displayLabel.length }
        )
    }

    private fun evidenceScore(evidence: PromotionEvidence): Int = when (evidence) {
        PromotionEvidence.PRICE_PAIR -> 5
        PromotionEvidence.PRODUCT_TEXT -> 4
        PromotionEvidence.PRODUCT_STRUCTURE -> 3
        PromotionEvidence.INHERITED_SECTION -> 1
    }

    private fun collectHumanTexts(
        node: Any?,
        output: MutableSet<String>,
        depth: Int,
        insidePromo: Boolean,
    ) {
        if (node == null || depth > SEARCH_DEPTH || output.size >= MAX_TEXTS) return
        when (node) {
            is String -> {
                if (insidePromo) cleanText(node)?.takeIf(::hasExplicitHumanSignal)?.let(output::add)
            }
            is JSONObject -> {
                (TEXT_KEYS + TYPE_KEYS).distinct().forEach { key ->
                    val raw = node.opt(key)
                    if (raw is String) {
                        cleanText(raw)?.takeIf(::hasExplicitHumanSignal)?.let(output::add)
                    }
                }
                val keys = node.keys()
                while (keys.hasNext() && output.size < MAX_TEXTS) {
                    val key = keys.next()
                    if (key == "__smartDealsSectionPromotion" && !insidePromo) continue
                    val child = node.opt(key)
                    val childPromo = insidePromo || isPromoKey(key) || normalizedKey(key) in META_WRAPPERS
                    if (child is JSONObject || child is JSONArray || (child is String && childPromo)) {
                        collectHumanTexts(child, output, depth + 1, childPromo)
                    }
                }
            }
            is JSONArray -> for (index in 0 until node.length()) {
                collectHumanTexts(node.opt(index), output, depth + 1, insidePromo)
                if (output.size >= MAX_TEXTS) break
            }
        }
    }

    private fun collectStructured(
        node: Any?,
        output: StructuredSignals,
        depth: Int,
        insidePromo: Boolean,
    ) {
        if (node == null || depth > SEARCH_DEPTH) return
        when (node) {
            is JSONObject -> {
                val descriptor = TYPE_KEYS
                    .mapNotNull { node.opt(it).stringValue() }
                    .joinToString(" ")
                    .let(::normalize)
                    .replace('_', ' ')

                val secondHere = isSecondDescriptor(descriptor) ||
                    node.keys().asSequence().any { isSecondDescriptor(normalize(it).replace('_', ' ')) }
                val directHere = descriptor.contains("percentage") ||
                    descriptor.contains("percent") || descriptor.contains("porcentaje") ||
                    descriptor.contains("discount") || descriptor.contains("descuento")
                val explicitPromoObject = insidePromo ||
                    node.keys().asSequence().any(::isPromoKey) ||
                    node.keys().asSequence().any(::isStrongPercentKey)

                if (secondHere) output.secondUnit = true
                if (directHere) output.directPercentage = true

                structuredMultibuy(node)?.let { output.multibuy = it }

                if (secondHere || directHere || explicitPromoObject) {
                    PERCENT_VALUE_KEYS.forEach { key ->
                        strictPercent(node.opt(key), key)
                            ?.takeIf { it in 0.5..100.0 }
                            ?.let(output.percentages::add)
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (isStrongPercentKey(key)) {
                            strictPercent(node.opt(key), key)
                                ?.takeIf { it in 0.5..100.0 }
                                ?.let {
                                    output.percentages += rounded(it)
                                    output.directPercentage = true
                                }
                        }
                    }
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (isProductCollectionKey(key) ||
                        (key == "__smartDealsSectionPromotion" && !insidePromo)) continue
                    val child = node.opt(key)
                    val childPromo = insidePromo || isPromoKey(key) || normalizedKey(key) in META_WRAPPERS
                    if (child is JSONObject || child is JSONArray) {
                        collectStructured(child, output, depth + 1, childPromo)
                    }
                }
            }
            is JSONArray -> for (index in 0 until node.length()) {
                collectStructured(node.opt(index), output, depth + 1, insidePromo)
            }
        }
    }

    private fun isSecondDescriptor(descriptor: String): Boolean {
        return descriptor.contains("second unit") || descriptor.contains("second item") ||
            descriptor.contains("second product") || descriptor.contains("segunda unidad") ||
            descriptor.contains("segundo producto") || descriptor.contains("segunda") ||
            descriptor.contains("segundo") || descriptor.contains("2da") ||
            descriptor.contains("2do")
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
        return if (quantity in 2..12 && paid in 1 until quantity) multibuy(quantity, paid) else null
    }

    private fun parseTakePay(text: String): NormalizedPromotion? {
        val match = TAKE_PAY.find(text) ?: return null
        val quantity = match.groupValues[1].toIntOrNull() ?: return null
        val paid = match.groupValues[2].toIntOrNull() ?: return null
        return if (quantity in 2..12 && paid in 1 until quantity) multibuy(quantity, paid) else null
    }

    private fun strictSecondPercent(text: String): Double? {
        return SECOND_FORWARD.find(text)?.groupValues?.getOrNull(1)?.toPercent()
            ?: SECOND_REVERSE.find(text)?.groupValues?.getOrNull(1)?.toPercent()
    }

    private fun explicitDirectPercent(text: String): Double? {
        return DIRECT_FORWARD.find(text)?.groupValues?.getOrNull(1)?.toPercent()
            ?: DIRECT_REVERSE.find(text)?.groupValues?.getOrNull(1)?.toPercent()
            ?: DIRECT_MINUS.find(text)?.groupValues?.getOrNull(1)?.toPercent()
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
            .filter(::hasExplicitHumanSignal)
            .distinctBy(::normalize)
            .take(2)
            .toList()
        return candidates.joinToString(" · ").takeIf(String::isNotBlank) ?: promo.title
    }

    private fun hasExplicitHumanSignal(value: String): Boolean {
        val normalized = normalize(value).replace('×', 'x')
        return strictSecondPercent(normalized) != null ||
            SECOND_FREE.containsMatchIn(normalized) ||
            MULTIBUY.containsMatchIn(normalized) ||
            TAKE_PAY.containsMatchIn(normalized) ||
            explicitDirectPercent(normalized) != null
    }

    private fun internalMetadata(value: String): Boolean {
        val normalized = normalize(value)
        return UUID.matches(value.trim()) || LONG_HEX.matches(value.trim()) ||
            normalized in INTERNAL_TOKENS ||
            (normalized.matches(Regex("^[a-z_]+$")) && !hasExplicitHumanSignal(normalized))
    }

    private fun isStrongPercentKey(key: String): Boolean {
        val normalized = normalizedKey(key)
        return STRONG_PERCENT_KEYS.any { normalized == normalizedKey(it) } ||
            ((normalized.contains("discount") || normalized.contains("saving") ||
                normalized.contains("benefit") || normalized.contains("descuento")) &&
                (normalized.contains("percent") || normalized.contains("percentage") ||
                    normalized.contains("rate") || normalized.contains("ratio")))
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

    private fun normalizedKey(value: String): String = normalize(value).replace('-', '_').replace(' ', '_')

    private fun isProductCollectionKey(key: String): Boolean {
        val normalized = normalizedKey(key)
        return normalized == "products" || normalized == "items" ||
            normalized == "productlist" || normalized == "product_list" ||
            normalized == "catalogitems" || normalized == "catalog_items" ||
            normalized == "results" || normalized == "entries" ||
            normalized == "elements" || normalized == "skus" ||
            normalized == "variants" || normalized == "children" ||
            normalized.contains("products") || normalized.contains("product_list") ||
            normalized.contains("catalogitem") || normalized.contains("recommend") ||
            normalized.contains("related") || normalized.contains("similar")
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
    private val DIRECT_FORWARD = Regex(
        "\\b(?:descuento|ahorra|promo(?:cion)?|oferta|off|dto)\\b" +
            ".{0,20}?(\\d{1,3}(?:[.,]\\d+)?)\\s*%"
    )
    private val DIRECT_REVERSE = Regex(
        "\\b(\\d{1,3}(?:[.,]\\d+)?)\\s*%\\s*(?:off|dto|de descuento|descuento)\\b"
    )
    private val DIRECT_MINUS = Regex("(?:^|\\s)-(\\d{1,3}(?:[.,]\\d+)?)\\s*%")
    private val STRICT_PERCENT = Regex("^\\s*(-?\\d{1,3}(?:[.,]\\d+)?)\\s*%?\\s*$")
    private val UUID = Regex(
        "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    )
    private val LONG_HEX = Regex("(?i)^[0-9a-f]{18,}$")

    private val STRONG_PERCENT_KEYS = listOf(
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
        "benefitType", "benefit_type", "appliesTo", "applies_to", "ruleType", "rule_type",
    )
    private val PROMO_KEYS = listOf(
        "promotionLabel", "promotion_label", "promotionText", "promotion_text",
        "discountLabel", "discount_label", "discountText", "discount_text",
        "promoLabel", "promo_label", "promoText", "promo_text", "badge", "badges",
        "promotion", "promotions", "offer", "offers", "campaign", "campaigns",
        "tags", "benefit", "benefits", "deal", "deals", "commercial", "commercialData",
        "commercial_data", "mechanic", "mechanics", "__smartDealsSectionPromotion",
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
    private const val MAX_TEXTS = 40
    private const val MAX_SUBTREE_CONTEXTS = 64
}
