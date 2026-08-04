package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.CapturedProduct
import com.lisofer.smartsupermarketdeals.data.PromotionKind
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
            val output = LinkedHashMap<String, CapturedProduct>()
            walk(root, sourceUrl, output, depth = 0)
            output.values.toList()
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

    private fun walk(
        node: Any?,
        sourceUrl: String,
        output: MutableMap<String, CapturedProduct>,
        depth: Int,
    ) {
        if (node == null || depth > MAX_DEPTH || output.size >= MAX_PRODUCTS_PER_MESSAGE) return
        when (node) {
            is JSONObject -> {
                candidate(node, sourceUrl)?.let { product ->
                    output[product.key] = prefer(output[product.key], product)
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    walk(node.opt(keys.next()), sourceUrl, output, depth + 1)
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    walk(node.opt(index), sourceUrl, output, depth + 1)
                }
            }
        }
    }

    private fun candidate(json: JSONObject, sourceUrl: String): CapturedProduct? {
        val name = firstTextLocal(json, NAME_KEYS)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.length in 3..200 }
            ?: return null

        val price = firstNumberLocal(json, PRICE_KEYS)
            ?.takeIf { it in MIN_PRICE..MAX_PRICE }
            ?: return null

        val promoTexts = collectPromoTexts(json)
        val promoLabel = promoTexts
            .joinToString(" · ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(260)
            .takeIf { it.isNotBlank() }

        val explicitPercent = firstPercentDeep(json)
            ?.takeIf { it in 0.5..100.0 }
        val originalPrice = firstNumberLocal(json, ORIGINAL_PRICE_KEYS)
            ?.takeIf { it > price && it <= MAX_PRICE }

        val normalizedPromotion = normalizePromotion(
            price = price,
            originalPrice = originalPrice,
            explicitPercent = explicitPercent,
            promoText = promoLabel.orEmpty(),
        )

        val explicitId = firstTextLocal(json, ID_KEYS)?.trim()?.takeIf { it.isNotBlank() }
        val imageIdentity = firstTextLocal(json, IMAGE_KEYS)?.trim().orEmpty()
        val sourceMarker = json.optString("source")
        val hasProductSignal = sourceMarker == "visible-dom" ||
            explicitId != null ||
            IMAGE_KEYS.any(json::has) ||
            CATEGORY_KEYS.any(json::has) ||
            originalPrice != null ||
            normalizedPromotion != null ||
            json.keys().asSequence().any { key ->
                key.contains("product", ignoreCase = true) ||
                    key.contains("item", ignoreCase = true) ||
                    key.contains("sku", ignoreCase = true)
            }
        if (!hasProductSignal) return null

        val normalizedName = normalizeText(name)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        val identity = listOf(explicitId.orEmpty(), normalizedName, imageIdentity)
            .joinToString("|")
        val key = "product:${sha256(identity)}"

        return CapturedProduct(
            key = key,
            name = name,
            price = price,
            originalPrice = originalPrice,
            advertisedDiscountPercent = explicitPercent,
            promoLabel = promoLabel,
            promotionCategory = normalizedPromotion?.categoryKey,
            promotionTitle = normalizedPromotion?.title,
            effectiveDiscountPercent = normalizedPromotion?.effectivePercent,
            promotionKind = normalizedPromotion?.kind,
            sourceUrl = sourceUrl,
        )
    }

    private data class NormalizedPromotion(
        val categoryKey: String,
        val title: String,
        val effectivePercent: Double,
        val kind: PromotionKind,
    )

    private fun normalizePromotion(
        price: Double,
        originalPrice: Double?,
        explicitPercent: Double?,
        promoText: String,
    ): NormalizedPromotion? {
        val text = normalizeText(promoText).replace('×', 'x')

        if (SECOND_FREE_REGEX.containsMatchIn(text)) {
            return multibuyPromotion(quantity = 2, paid = 1)
        }

        MULTIBUY_REGEX.find(text)?.let { match ->
            val quantity = match.groupValues[1].toIntOrNull() ?: return@let
            val paid = match.groupValues[2].toIntOrNull() ?: return@let
            if (quantity in 2..12 && paid in 1 until quantity) {
                return multibuyPromotion(quantity, paid)
            }
        }

        TAKE_PAY_REGEX.find(text)?.let { match ->
            val quantity = match.groupValues[1].toIntOrNull() ?: return@let
            val paid = match.groupValues[2].toIntOrNull() ?: return@let
            if (quantity in 2..12 && paid in 1 until quantity) {
                return multibuyPromotion(quantity, paid)
            }
        }

        val secondPercent = SECOND_UNIT_FORWARD_REGEX.find(text)
            ?.groupValues?.getOrNull(1)?.toPercent()
            ?: SECOND_UNIT_REVERSE_REGEX.find(text)
                ?.groupValues?.getOrNull(1)?.toPercent()
        if (secondPercent != null && secondPercent in 0.5..100.0) {
            return NormalizedPromotion(
                categoryKey = "second:${percentKey(secondPercent)}",
                title = "2da unidad ${percentLabel(secondPercent)}% OFF",
                effectivePercent = secondPercent / 2.0,
                kind = PromotionKind.SECOND_UNIT,
            )
        }

        val textPercent = DIRECT_PERCENT_REGEX.find(text)
            ?.groupValues?.getOrNull(1)?.toPercent()
            ?: DIRECT_OFF_REGEX.find(text)
                ?.groupValues?.getOrNull(1)?.toPercent()
        val directPercent = (explicitPercent ?: textPercent)
            ?.takeIf { it in 0.5..95.0 }
        if (directPercent != null) return directPromotion(directPercent)

        if (originalPrice != null && originalPrice > price) {
            val calculated = ((originalPrice - price) / originalPrice) * 100.0
            if (calculated in 0.5..95.0) return directPromotion(calculated)
        }

        return null
    }

    private fun multibuyPromotion(quantity: Int, paid: Int): NormalizedPromotion {
        val effective = ((quantity - paid).toDouble() / quantity.toDouble()) * 100.0
        return NormalizedPromotion(
            categoryKey = "multibuy:${quantity}x$paid",
            title = "${quantity}x$paid",
            effectivePercent = effective,
            kind = PromotionKind.MULTIBUY,
        )
    }

    private fun directPromotion(percent: Double): NormalizedPromotion {
        val rounded = (percent * 10.0).roundToInt() / 10.0
        return NormalizedPromotion(
            categoryKey = "percent:${percentKey(rounded)}",
            title = "${percentLabel(rounded)}% OFF",
            effectivePercent = rounded,
            kind = PromotionKind.DIRECT_PERCENT,
        )
    }

    private fun firstTextLocal(json: JSONObject, keys: List<String>): String? {
        keys.forEach { key -> textFromValue(json.opt(key))?.let { return it } }
        TEXT_CONTAINER_KEYS.forEach { containerKey ->
            val container = json.optJSONObject(containerKey) ?: return@forEach
            keys.forEach { key -> textFromValue(container.opt(key))?.let { return it } }
        }
        return null
    }

    private fun textFromValue(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.isNotBlank() }
        is Number -> value.toString()
        is JSONObject -> TEXT_VALUE_KEYS.firstNotNullOfOrNull { key ->
            value.optString(key).trim().takeIf { it.isNotBlank() }
        }
        else -> null
    }

    private fun firstNumberLocal(json: JSONObject, keys: List<String>): Double? {
        keys.forEach { key ->
            numberFromValue(json.opt(key))?.let { return it }
        }
        PRICE_CONTAINER_KEYS.forEach { containerKey ->
            val container = json.optJSONObject(containerKey) ?: return@forEach
            keys.forEach { key -> numberFromValue(container.opt(key))?.let { return it } }
            NUMBER_VALUE_KEYS.forEach { key ->
                if (key in keys || keys === PRICE_KEYS) {
                    numberFromValue(container.opt(key))?.let { return it }
                }
            }
        }
        return null
    }

    private fun numberFromValue(value: Any?): Double? {
        parseNumber(value)?.let { return it }
        if (value is JSONObject) {
            NUMBER_VALUE_KEYS.forEach { key -> parseNumber(value.opt(key))?.let { return it } }
        }
        return null
    }

    private fun firstPercentDeep(json: JSONObject): Double? {
        return findPercent(json, depth = 0)
    }

    private fun findPercent(node: Any?, depth: Int): Double? {
        if (node == null || depth > PROMO_SEARCH_DEPTH) return null
        return when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (isPercentKey(key)) {
                        parsePercent(value, key)?.let { return it }
                    }
                    if (value is JSONObject || value is JSONArray) {
                        findPercent(value, depth + 1)?.let { return it }
                    }
                }
                null
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    findPercent(node.opt(index), depth + 1)?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun collectPromoTexts(json: JSONObject): List<String> {
        val output = LinkedHashSet<String>()
        val keys = json.keys()
        while (keys.hasNext() && output.size < MAX_PROMO_TEXTS) {
            val key = keys.next()
            if (isPromoKey(key)) {
                collectAllStrings(json.opt(key), output, depth = 0)
            }
        }
        if (PROMO_BOOLEAN_KEYS.any { key -> json.optBoolean(key, false) }) {
            output += "Promoción publicada"
        }
        return output.take(MAX_PROMO_TEXTS)
    }

    private fun collectAllStrings(value: Any?, output: MutableSet<String>, depth: Int) {
        if (value == null || depth > PROMO_SEARCH_DEPTH || output.size >= MAX_PROMO_TEXTS) return
        when (value) {
            is String -> sanitizePromoText(value)?.let(output::add)
            is Number -> Unit
            is JSONObject -> {
                PROMO_OBJECT_TEXT_KEYS.forEach { key ->
                    sanitizePromoText(value.optString(key))?.let(output::add)
                }
                val keys = value.keys()
                while (keys.hasNext() && output.size < MAX_PROMO_TEXTS) {
                    collectAllStrings(value.opt(keys.next()), output, depth + 1)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectAllStrings(value.opt(index), output, depth + 1)
                    if (output.size >= MAX_PROMO_TEXTS) break
                }
            }
        }
    }

    private fun isPromoKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT)
        return PROMO_TEXT_KEYS.any { it.equals(key, ignoreCase = true) } ||
            normalized.contains("promo") ||
            normalized.contains("discount") ||
            normalized.contains("descuento") ||
            normalized.contains("benefit") ||
            normalized.contains("badge") ||
            normalized.contains("offer") ||
            normalized.contains("campaign") ||
            normalized.contains("saving")
    }

    private fun isPercentKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT)
        return DISCOUNT_PERCENT_KEYS.any { it.equals(key, ignoreCase = true) } ||
            ((normalized.contains("discount") || normalized.contains("saving")) &&
                (normalized.contains("percent") || normalized.contains("percentage") ||
                    normalized.contains("rate") || normalized.contains("ratio")))
    }

    private fun sanitizePromoText(raw: String): String? {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        return text.takeIf { it.length in 2..220 }
    }

    private fun parsePercent(value: Any?, key: String): Double? = when (value) {
        is Number -> {
            val raw = value.toDouble()
            if (raw in 0.01..<1.0 &&
                (key.contains("rate", true) || key.contains("ratio", true))) {
                raw * 100.0
            } else {
                raw
            }
        }
        is String -> Regex("(\\d{1,3}(?:[.,]\\d+)?)")
            .find(value)
            ?.groupValues?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
        else -> null
    }

    private fun parseNumber(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> parseLocalizedNumber(value)
        else -> null
    }

    private fun parseLocalizedNumber(raw: String): Double? {
        val cleaned = raw
            .replace("$", "")
            .replace("ARS", "", ignoreCase = true)
            .filter { it.isDigit() || it == '.' || it == ',' || it == '-' }
            .trim()
        if (cleaned.isBlank()) return null

        val normalized = when {
            cleaned.contains('.') && cleaned.contains(',') ->
                cleaned.replace(".", "").replace(',', '.')
            cleaned.contains(',') -> cleaned.replace(',', '.')
            cleaned.matches(Regex("-?\\d{1,3}(\\.\\d{3})+")) -> cleaned.replace(".", "")
            else -> cleaned
        }
        return normalized.toDoubleOrNull()
    }

    private fun normalizeText(value: String): String {
        return Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.toPercent(): Double? = replace(',', '.').toDoubleOrNull()

    private fun percentKey(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun percentLabel(value: Double): String {
        return if (abs(value - value.roundToInt()) < 0.05) {
            value.roundToInt().toString()
        } else {
            String.format(Locale("es", "AR"), "%.1f", value)
        }
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }

    private val MULTIBUY_REGEX = Regex("\\b(\\d{1,2})\\s*x\\s*(\\d{1,2})\\b")
    private val TAKE_PAY_REGEX = Regex("lleva(?:ndo)?\\s*(\\d{1,2}).{0,30}?paga(?:ndo)?\\s*(\\d{1,2})")
    private val SECOND_FREE_REGEX = Regex(
        "(?:2\\s*(?:da|do|°|º)?|segunda|segundo)\\s*(?:unidad)?\\s*(?:gratis|sin cargo)"
    )
    private val SECOND_UNIT_FORWARD_REGEX = Regex(
        "(?:2\\s*(?:da|do|°|º)?|segunda|segundo)\\s*(?:unidad)?\\s*" +
            "(?:al|con|a|:)?\\s*(\\d{1,3}(?:[.,]\\d+)?)\\s*%?\\s*" +
            "(?:off|dto|de descuento|descuento)?"
    )
    private val SECOND_UNIT_REVERSE_REGEX = Regex(
        "(\\d{1,3}(?:[.,]\\d+)?)\\s*%?\\s*(?:off|dto|de descuento|descuento)?" +
            ".{0,30}?(?:2\\s*(?:da|do|°|º)?|segunda|segundo)\\s*(?:unidad)?"
    )
    private val DIRECT_PERCENT_REGEX = Regex("(?:-|ahorra|hasta|descuento)?\\s*(\\d{1,3}(?:[.,]\\d+)?)\\s*%")
    private val DIRECT_OFF_REGEX = Regex("\\b(\\d{1,3}(?:[.,]\\d+)?)\\s*(?:off|dto)\\b")

    private val NAME_KEYS = listOf(
        "name", "title", "productName", "product_name", "displayName", "display_name",
    )
    private val PRICE_KEYS = listOf(
        "price", "currentPrice", "current_price", "salePrice", "sale_price",
        "finalPrice", "final_price", "discountedPrice", "discounted_price",
        "promotionalPrice", "promotional_price", "priceWithDiscount", "price_with_discount",
        "unitPrice", "unit_price", "amount",
    )
    private val ORIGINAL_PRICE_KEYS = listOf(
        "originalPrice", "original_price", "regularPrice", "regular_price",
        "previousPrice", "previous_price", "listPrice", "list_price",
        "priceWithoutDiscount", "price_without_discount", "basePrice", "base_price",
        "beforePrice", "before_price", "oldPrice", "old_price", "strikePrice",
        "strike_price", "crossedPrice", "crossed_price", "retailPrice", "retail_price",
        "priceBeforeDiscount", "price_before_discount",
    )
    private val DISCOUNT_PERCENT_KEYS = listOf(
        "discountPercentage", "discount_percentage", "discountPercent", "discount_percent",
        "percentageOff", "percentage_off", "percentOff", "percent_off",
        "discountRate", "discount_rate", "savingsPercentage", "savings_percentage",
    )
    private val PROMO_TEXT_KEYS = listOf(
        "promotionLabel", "promotion_label", "promotionText", "promotion_text",
        "discountLabel", "discount_label", "discountText", "discount_text",
        "promoLabel", "promo_label", "promoText", "promo_text", "badge", "badges",
        "promotion", "promotions", "offer", "offers", "campaign", "campaigns",
        "tags", "benefit", "benefits", "deal", "deals",
    )
    private val PROMO_OBJECT_TEXT_KEYS = listOf(
        "name", "title", "label", "text", "description", "message", "value",
    )
    private val PROMO_BOOLEAN_KEYS = listOf(
        "hasPromotion", "has_promotion", "isPromotional", "is_promotional",
        "isDiscounted", "is_discounted", "onSale", "on_sale",
    )
    private val ID_KEYS = listOf(
        "id", "productId", "product_id", "sku", "barcode", "gtin", "ean",
    )
    private val IMAGE_KEYS = listOf(
        "image", "imageUrl", "image_url", "picture", "thumbnail",
    )
    private val CATEGORY_KEYS = listOf(
        "category", "categoryId", "category_id", "section", "aisle",
    )
    private val TEXT_CONTAINER_KEYS = listOf("product", "item", "content", "data")
    private val TEXT_VALUE_KEYS = listOf("name", "title", "label", "text", "description", "value", "url")
    private val PRICE_CONTAINER_KEYS = listOf(
        "pricing", "priceInfo", "price_info", "prices", "commercial", "sale", "product", "item",
    )
    private val NUMBER_VALUE_KEYS = listOf("amount", "value", "price", "current", "total", "units")

    private const val MIN_PRICE = 20.0
    private const val MAX_PRICE = 100_000_000.0
    private const val MAX_DEPTH = 18
    private const val PROMO_SEARCH_DEPTH = 5
    private const val MAX_PROMO_TEXTS = 16
    private const val MAX_PRODUCTS_PER_MESSAGE = 5_000
}
