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
                candidate(node, sourceUrl)?.let { product ->
                    output[product.key] = product
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
        val name = firstText(json, NAME_KEYS)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.length in 3..180 }
            ?: return null

        val price = firstNumber(json, PRICE_KEYS)
            ?.takeIf { it in MIN_PRICE..MAX_PRICE }
            ?: return null

        val hasProductSignal = ID_KEYS.any(json::has) ||
            IMAGE_KEYS.any(json::has) ||
            CATEGORY_KEYS.any(json::has) ||
            json.keys().asSequence().any { key ->
                key.contains("product", ignoreCase = true) ||
                    key.contains("item", ignoreCase = true) ||
                    key.contains("sku", ignoreCase = true)
            }
        if (!hasProductSignal) return null

        val original = firstNumber(json, ORIGINAL_PRICE_KEYS)
            ?.takeIf { it > price && it <= MAX_PRICE }

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
            sourceUrl = sourceUrl,
        )
    }

    private fun firstText(json: JSONObject, keys: List<String>): String? {
        keys.forEach { key ->
            val value = json.opt(key)
            when (value) {
                is String -> if (value.isNotBlank()) return value
                is JSONObject -> {
                    listOf("name", "title", "label", "text").forEach { nested ->
                        val nestedValue = value.optString(nested)
                        if (nestedValue.isNotBlank()) return nestedValue
                    }
                }
            }
        }
        return null
    }

    private fun firstNumber(json: JSONObject, keys: List<String>): Double? {
        keys.forEach { key ->
            val value = json.opt(key)
            parseNumber(value)?.let { return it }
            if (value is JSONObject) {
                listOf("amount", "value", "price", "current", "total").forEach { nested ->
                    parseNumber(value.opt(nested))?.let { return it }
                }
            }
        }
        return null
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
        "finalPrice", "final_price", "unitPrice", "unit_price",
    )
    private val ORIGINAL_PRICE_KEYS = listOf(
        "originalPrice", "original_price", "regularPrice", "regular_price",
        "previousPrice", "previous_price", "listPrice", "list_price",
        "priceWithoutDiscount", "price_without_discount",
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
    private const val MAX_PRODUCTS_PER_MESSAGE = 2_000
}
