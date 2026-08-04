package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.CapturedProduct
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

object ProductJsonExtractor {
    fun extract(message: String): List<CapturedProduct> {
        return runCatching {
            val envelope = JSONObject(message)
            val sourceUrl = envelope.optString("url")
            val body = envelope.optString("body")
            if (body.isBlank()) return emptyList()
            val root: Any = when {
                body.trimStart().startsWith("[") -> JSONArray(body)
                else -> JSONObject(body)
            }
            val output = LinkedHashMap<String, CapturedProduct>()
            walk(root, sourceUrl, output, depth = 0)
            output.values.toList()
        }.getOrDefault(emptyList())
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
                candidate(node, sourceUrl)?.let { product -> output[product.key] = product }
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
        val name = firstText(json, NAME_KEYS)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.length in 3..180 }
            ?: return null

        val price = firstNumberDeep(json, PRICE_KEYS)
            ?.takeIf { it in MIN_PRICE..MAX_PRICE }
            ?: return null

        val explicitPercent = firstPercentDeep(json)
            ?.takeIf { it in 0.5..95.0 }
        val promoLabel = firstPromoText(json)
        val explicitOriginal = firstNumberDeep(json, ORIGINAL_PRICE_KEYS)
            ?.takeIf { it > price && it <= MAX_PRICE }
        val inferredOriginal = explicitPercent
            ?.let { percent -> price / (1.0 - percent / 100.0) }
            ?.takeIf { it > price && it <= MAX_PRICE }
        val original = explicitOriginal ?: inferredOriginal

        val hasProductSignal = ID_KEYS.any(json::has) ||
            IMAGE_KEYS.any(json::has) ||
            CATEGORY_KEYS.any(json::has) ||
            original != null ||
            explicitPercent != null ||
            promoLabel != null ||
            json.keys().asSequence().any { key ->
                key.contains("product", ignoreCase = true) ||
                    key.contains("item", ignoreCase = true) ||
                    key.contains("sku", ignoreCase = true)
            }
        if (!hasProductSignal) return null

        val explicitId = firstText(json, ID_KEYS)?.trim()?.takeIf { it.isNotBlank() }
        val normalizedName = name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9áéíóúüñ]+"), "-")
            .trim('-')
        val key = explicitId?.let { "id:${it.take(100)}" }
            ?: "name:${sha256(normalizedName)}"

        return CapturedProduct(
            key = key,
            name = name,
            price = price,
            originalPrice = original,
            advertisedDiscountPercent = explicitPercent,
            promoLabel = promoLabel,
            sourceUrl = sourceUrl,
        )
    }

    private fun firstText(json: JSONObject, keys: List<String>): String? {
        keys.forEach { key ->
            textFromValue(json.opt(key))?.let { return it }
        }
        return null
    }

    private fun textFromValue(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.isNotBlank() }
        is JSONObject -> listOf("name", "title", "label", "text", "description", "value")
            .firstNotNullOfOrNull { nested ->
                value.optString(nested).trim().takeIf { it.isNotBlank() }
            }
        else -> null
    }

    private fun firstNumberDeep(json: JSONObject, keys: List<String>): Double? {
        return findNumber(json, keys, depth = 0)
    }

    private fun findNumber(node: Any?, keys: List<String>, depth: Int): Double? {
        if (node == null || depth > PRICE_SEARCH_DEPTH) return null
        return when (node) {
            is JSONObject -> {
                keys.forEach { key ->
                    val value = node.opt(key)
                    parseNumber(value)?.let { return it }
                    if (value is JSONObject) {
                        listOf("amount", "value", "price", "current", "total").forEach { nested ->
                            parseNumber(value.opt(nested))?.let { return it }
                        }
                    }
                }
                val objectKeys = node.keys()
                while (objectKeys.hasNext()) {
                    val child = node.opt(objectKeys.next())
                    if (child is JSONObject) {
                        findNumber(child, keys, depth + 1)?.let { return it }
                    }
                }
                null
            }

            is JSONArray -> {
                for (index in 0 until node.length()) {
                    val child = node.opt(index)
                    if (child is JSONObject) {
                        findNumber(child, keys, depth + 1)?.let { return it }
                    }
                }
                null
            }

            else -> null
        }
    }

    private fun firstPercentDeep(json: JSONObject): Double? {
        return findPercent(json, depth = 0)
    }

    private fun findPercent(node: Any?, depth: Int): Double? {
        if (node == null || depth > PROMO_SEARCH_DEPTH) return null
        return when (node) {
            is JSONObject -> {
                DISCOUNT_PERCENT_KEYS.forEach { key ->
                    parsePercent(node.opt(key))?.let { return it }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    when (val child = node.opt(keys.next())) {
                        is JSONObject, is JSONArray ->
                            findPercent(child, depth + 1)?.let { return it }
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

    private fun firstPromoText(json: JSONObject): String? {
        PROMO_TEXT_KEYS.forEach { key ->
            promoTextFromValue(json.opt(key), depth = 0)?.let { text ->
                return sanitizePromoText(text)
            }
        }
        if (PROMO_BOOLEAN_KEYS.any { key -> json.optBoolean(key, false) }) {
            return "Promoción publicada"
        }
        return null
    }

    private fun promoTextFromValue(value: Any?, depth: Int): String? {
        if (value == null || depth > PROMO_SEARCH_DEPTH) return null
        return when (value) {
            is String -> value.takeIf { it.isNotBlank() }
            is Number -> null
            is JSONObject -> {
                listOf("name", "title", "label", "text", "description", "message")
                    .firstNotNullOfOrNull { key ->
                        value.optString(key).trim().takeIf { it.isNotBlank() }
                    }
                    ?: value.keys().asSequence().firstNotNullOfOrNull { key ->
                        promoTextFromValue(value.opt(key), depth + 1)
                    }
            }

            is JSONArray -> {
                (0 until value.length()).firstNotNullOfOrNull { index ->
                    promoTextFromValue(value.opt(index), depth + 1)
                }
            }

            else -> null
        }
    }

    private fun sanitizePromoText(raw: String): String? {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        return text.takeIf { it.length in 2..120 }
    }

    private fun parsePercent(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> Regex("(\\d{1,2}(?:[.,]\\d+)?)\\s*%?")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
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

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }

    private val NAME_KEYS = listOf(
        "name", "title", "productName", "product_name", "displayName", "display_name",
    )
    private val PRICE_KEYS = listOf(
        "price", "currentPrice", "current_price", "salePrice", "sale_price",
        "finalPrice", "final_price", "discountedPrice", "discounted_price",
        "promotionalPrice", "promotional_price", "priceWithDiscount", "price_with_discount",
        "unitPrice", "unit_price",
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
        "promoLabel", "promo_label", "promoText", "promo_text",
        "badge", "badges", "promotion", "promotions", "offer", "offers",
        "campaign", "campaigns", "tags",
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
    private const val MIN_PRICE = 20.0
    private const val MAX_PRICE = 100_000_000.0
    private const val MAX_DEPTH = 18
    private const val PRICE_SEARCH_DEPTH = 3
    private const val PROMO_SEARCH_DEPTH = 4
    private const val MAX_PRODUCTS_PER_MESSAGE = 2_000
}
