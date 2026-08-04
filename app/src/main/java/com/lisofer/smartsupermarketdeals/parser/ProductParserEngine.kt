package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.CapturedProduct
import com.lisofer.smartsupermarketdeals.data.PromotionKind
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

internal class ProductParserEngine(private val sourceUrl: String) {
    private val output = LinkedHashMap<String, CapturedProduct>()

    fun extract(root: Any): List<CapturedProduct> {
        walk(root, depth = 0, inheritedPromotion = null)
        return output.values.toList()
    }

    private fun walk(node: Any?, depth: Int, inheritedPromotion: PromotionContext?) {
        if (node == null || depth > MAX_DEPTH || output.size >= MAX_PRODUCTS) return
        when (node) {
            is JSONObject -> walkObject(node, depth, inheritedPromotion)
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    walk(node.opt(index), depth + 1, inheritedPromotion)
                    if (output.size >= MAX_PRODUCTS) break
                }
            }
        }
    }

    private fun walkObject(
        json: JSONObject,
        depth: Int,
        inheritedPromotion: PromotionContext?,
    ) {
        val ownPromotion = PromotionInterpreter.fromObject(json)
        candidate(json, ownPromotion ?: inheritedPromotion)?.let { incoming ->
            output[incoming.key] = ProductJsonExtractor.prefer(output[incoming.key], incoming)
        }

        val promotionToPropagate = when {
            ownPromotion != null && ownPromotion.unambiguous && hasProductCollection(json) -> ownPromotion
            inheritedPromotion != null -> inheritedPromotion
            else -> null
        }

        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.opt(key)
            val childPromotion = when {
                ownPromotion != null && ownPromotion.unambiguous &&
                    (isProductCollectionKey(key) || isWrapperKey(key)) -> ownPromotion
                promotionToPropagate != null &&
                    (isProductCollectionKey(key) || isWrapperKey(key) || inheritedPromotion != null) ->
                    promotionToPropagate
                else -> null
            }
            walk(value, depth + 1, childPromotion)
        }
    }

    private fun candidate(json: JSONObject, suppliedPromotion: PromotionContext?): CapturedProduct? {
        val name = firstText(json, NAME_KEYS)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.length in 3..240 }
            ?: return null

        val price = firstNumber(json, PRICE_KEYS)
            ?.takeIf { it in MIN_PRICE..MAX_PRICE }
            ?: return null

        val originalPrice = firstNumber(json, ORIGINAL_PRICE_KEYS)
            ?.takeIf { it > price && it <= MAX_PRICE }

        // PedidosYa often separates the mechanic (SECOND_UNIT) from its numeric value.
        // Reconstruct that product-wide context before accepting an isolated 50 as 50% direct.
        val reconstructedSecond =
            SecondUnitPromotionResolver.fromProductSubtree(json)
                ?: SplitSecondUnitTextResolver.fromProductSubtree(json)
        val deepPromotion = PromotionInterpreter.fromProductSubtree(json)
        val promotion = when {
            reconstructedSecond != null -> reconstructedSecond
            suppliedPromotion?.normalized?.kind == PromotionKind.SECOND_UNIT -> suppliedPromotion
            deepPromotion?.normalized?.kind == PromotionKind.SECOND_UNIT -> deepPromotion
            suppliedPromotion != null -> suppliedPromotion
            else -> deepPromotion
        }
        val normalizedPromotion = promotion?.normalized
            ?: originalPrice?.let { PromotionInterpreter.fromPrices(price, it) }

        val strongId = firstText(json, STRONG_ID_KEYS)?.trim()?.takeIf(String::isNotBlank)
        val localId = json.opt("id").let(::textValue)?.trim()?.takeIf(String::isNotBlank)
        val explicitId = strongId ?: localId
        val imageIdentity = firstText(json, IMAGE_KEYS)?.trim().orEmpty()
        val variantIdentity = firstText(json, VARIANT_KEYS)?.trim().orEmpty()
        val linkIdentity = firstText(json, LINK_KEYS)?.trim().orEmpty()
        val brandIdentity = firstText(json, BRAND_KEYS)?.trim().orEmpty()
        val presentationIdentity = firstText(json, PRESENTATION_KEYS)?.trim().orEmpty()
        val sourceMarker = json.optString("source")

        val hasProductSignal = sourceMarker == "visible-dom" ||
            sourceMarker == "exhaustive-dom" ||
            sourceMarker == "large-json-fragment" ||
            explicitId != null ||
            IMAGE_KEYS.any(json::has) ||
            CATEGORY_KEYS.any(json::has) ||
            STRONG_ID_KEYS.any(json::has) ||
            originalPrice != null ||
            normalizedPromotion != null ||
            json.keys().asSequence().any { key ->
                key.contains("product", ignoreCase = true) ||
                    key.contains("item", ignoreCase = true) ||
                    key.contains("sku", ignoreCase = true) ||
                    key.contains("catalog", ignoreCase = true)
            }
        if (!hasProductSignal) return null

        val stableIdentity = listOf(
            explicitId.orEmpty(),
            normalizeName(name),
            normalizeName(brandIdentity),
            normalizeName(variantIdentity),
            normalizeName(presentationIdentity),
            imageIdentity,
            linkIdentity,
        ).joinToString("|")
        val fallbackIdentity = if (stableIdentity.replace("|", "").isBlank()) {
            "${normalizeName(name)}|$price"
        } else {
            stableIdentity
        }
        val categoryIdentity = normalizedPromotion?.categoryKey.orEmpty()
        val advertised = normalizedPromotion
            ?.takeIf {
                it.kind == PromotionKind.DIRECT_PERCENT ||
                    it.kind == PromotionKind.SECOND_UNIT
            }
            ?.advertisedPercent

        return CapturedProduct(
            key = "product:${sha256("$fallbackIdentity|$categoryIdentity")}",
            name = name,
            price = price,
            originalPrice = originalPrice,
            advertisedDiscountPercent = advertised,
            promoLabel = promotion?.displayLabel ?: normalizedPromotion?.title,
            promotionCategory = normalizedPromotion?.categoryKey,
            promotionTitle = normalizedPromotion?.title,
            effectiveDiscountPercent = normalizedPromotion?.effectivePercent,
            promotionKind = normalizedPromotion?.kind,
            sourceUrl = sourceUrl,
        )
    }

    private fun hasProductCollection(json: JSONObject): Boolean {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.opt(key)
            if (isProductCollectionKey(key) && (value is JSONArray || value is JSONObject)) {
                return true
            }
            if (isWrapperKey(key) && value is JSONObject) {
                val nested = value.keys()
                while (nested.hasNext()) {
                    val nestedKey = nested.next()
                    val nestedValue = value.opt(nestedKey)
                    if (isProductCollectionKey(nestedKey) &&
                        (nestedValue is JSONArray || nestedValue is JSONObject)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun isProductCollectionKey(key: String): Boolean {
        val n = key.lowercase(Locale.ROOT)
        return n == "products" || n == "items" || n == "productlist" ||
            n == "product_list" || n == "catalogitems" || n == "catalog_items" ||
            n == "results" || n == "entries" || n == "elements" ||
            n == "skus" || n == "variants" || n == "children" ||
            n.contains("products") || n.contains("catalogitem") ||
            n.contains("product_list") || n.contains("productlist")
    }

    private fun isWrapperKey(key: String): Boolean {
        val n = key.lowercase(Locale.ROOT)
        return n in WRAPPER_KEYS || n.contains("section") || n.contains("shelf") ||
            n.contains("carousel") || n.contains("collection") || n.contains("group") ||
            n.contains("catalog") || n.contains("aisle") || n.contains("category")
    }

    private fun firstText(json: JSONObject, keys: List<String>): String? {
        return firstTextRecursive(json, keys, depth = 0)
    }

    private fun firstTextRecursive(json: JSONObject, keys: List<String>, depth: Int): String? {
        keys.forEach { key -> textValue(json.opt(key))?.let { return it } }
        if (depth >= LOCAL_SEARCH_DEPTH) return null
        LOCAL_TEXT_CONTAINERS.forEach { containerKey ->
            val container = json.optJSONObject(containerKey) ?: return@forEach
            firstTextRecursive(container, keys, depth + 1)?.let { return it }
        }
        return null
    }

    private fun textValue(value: Any?): String? = when (value) {
        is String -> value.takeIf(String::isNotBlank)
        is Number -> value.toString()
        is JSONObject -> TEXT_VALUES.firstNotNullOfOrNull { key ->
            value.optString(key).trim().takeIf(String::isNotBlank)
        }
        else -> null
    }

    private fun firstNumber(json: JSONObject, keys: List<String>): Double? {
        return firstNumberRecursive(json, keys, depth = 0)
    }

    private fun firstNumberRecursive(json: JSONObject, keys: List<String>, depth: Int): Double? {
        keys.forEach { key -> numberValue(json.opt(key))?.let { return it } }
        if (depth >= LOCAL_SEARCH_DEPTH) return null
        LOCAL_PRICE_CONTAINERS.forEach { containerKey ->
            val container = json.optJSONObject(containerKey) ?: return@forEach
            firstNumberRecursive(container, keys, depth + 1)?.let { return it }
            if (keys === PRICE_KEYS) {
                NUMBER_VALUES.forEach { key ->
                    numberValue(container.opt(key))?.let { return it }
                }
            }
        }
        return null
    }

    private fun numberValue(value: Any?): Double? {
        parseNumber(value)?.let { return it }
        if (value is JSONObject) {
            NUMBER_VALUES.forEach { key -> parseNumber(value.opt(key))?.let { return it } }
        }
        return null
    }

    private fun parseNumber(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> localizedNumber(value)
        else -> null
    }

    private fun localizedNumber(raw: String): Double? {
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

    private fun normalizeName(value: String): String {
        return Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }

    private val NAME_KEYS = listOf(
        "productName", "product_name", "displayName", "display_name",
        "itemName", "item_name", "name", "title",
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
    private val STRONG_ID_KEYS = listOf(
        "productId", "product_id", "itemId", "item_id", "sku", "skuId", "sku_id",
        "barcode", "gtin", "ean",
    )
    private val IMAGE_KEYS = listOf(
        "image", "imageUrl", "image_url", "picture", "thumbnail", "photo", "photoUrl",
    )
    private val VARIANT_KEYS = listOf(
        "variant", "variantName", "variant_name", "size", "presentation", "pack", "unit",
    )
    private val BRAND_KEYS = listOf(
        "brand", "brandName", "brand_name", "manufacturer",
    )
    private val PRESENTATION_KEYS = listOf(
        "presentation", "package", "packageName", "package_name", "measurement",
        "weight", "volume", "description",
    )
    private val LINK_KEYS = listOf(
        "url", "href", "deeplink", "deepLink", "deep_link", "productUrl", "product_url",
    )
    private val CATEGORY_KEYS = listOf(
        "category", "categoryId", "category_id", "section", "aisle", "department",
    )
    private val LOCAL_TEXT_CONTAINERS = listOf(
        "product", "item", "content", "data", "details", "attributes", "metadata",
    )
    private val LOCAL_PRICE_CONTAINERS = listOf(
        "pricing", "priceInfo", "price_info", "prices", "commercial", "sale",
        "product", "item", "content", "data", "details",
    )
    private val TEXT_VALUES = listOf(
        "name", "title", "label", "text", "description", "value", "url",
    )
    private val NUMBER_VALUES = listOf(
        "amount", "value", "price", "current", "total", "units",
    )
    private val WRAPPER_KEYS = setOf(
        "data", "content", "payload", "result", "results", "body", "response",
        "catalog", "menu", "store", "vendor",
    )

    private companion object {
        const val MIN_PRICE = 20.0
        const val MAX_PRICE = 100_000_000.0
        const val MAX_DEPTH = 32
        const val MAX_PRODUCTS = 60_000
        const val LOCAL_SEARCH_DEPTH = 4
    }
}
