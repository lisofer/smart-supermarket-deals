package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.CapturedProduct
import com.lisofer.smartsupermarketdeals.data.PromotionEvidence
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
        val payloadInheritedPromotion = json.optJSONObject("__smartDealsSectionPromotion")
            ?.let(PromotionInterpreter::fromObject)
            ?.copy(evidence = PromotionEvidence.INHERITED_SECTION)
        candidate(
            json = json,
            ownPromotion = ownPromotion,
            inheritedPromotion = payloadInheritedPromotion ?: inheritedPromotion,
        )?.let { incoming ->
            output[incoming.key] = ProductJsonExtractor.prefer(output[incoming.key], incoming)
        }

        val sectionPromotion = ownPromotion
            ?.takeIf { canPropagate(it, json, depth) }
            ?.copy(evidence = PromotionEvidence.INHERITED_SECTION)

        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.opt(key)
            val childPromotion = when {
                isProductCollectionKey(key) -> sectionPromotion ?: inheritedPromotion
                isWrapperKey(key) -> sectionPromotion ?: inheritedPromotion
                else -> null
            }
            walk(value, depth + 1, childPromotion)
        }
    }

    private fun canPropagate(
        promotion: PromotionContext,
        json: JSONObject,
        depth: Int,
    ): Boolean {
        if (depth < 1 || !promotion.unambiguous || !hasProductCollection(json)) return false
        if (promotion.evidence == PromotionEvidence.INHERITED_SECTION) return false
        return promotion.normalized.kind == PromotionKind.SECOND_UNIT ||
            promotion.normalized.kind == PromotionKind.MULTIBUY
    }

    private fun candidate(
        json: JSONObject,
        ownPromotion: PromotionContext?,
        inheritedPromotion: PromotionContext?,
    ): CapturedProduct? {
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
        val pricePromotion = originalPrice?.let { PromotionInterpreter.fromPrices(price, it) }
        val sourceMarker = json.optString("source")

        val resolverProduct = if (json.has("__smartDealsSectionPromotion")) {
            JSONObject(json.toString()).apply { remove("__smartDealsSectionPromotion") }
        } else {
            json
        }
        val structuredSecond = SecondUnitPromotionResolver.fromProductSubtree(resolverProduct)
            ?.takeIf { hasTypedSecondUnitEvidence(resolverProduct, it) }
        val textSecond = SplitSecondUnitTextResolver.fromProductSubtree(resolverProduct)
            ?.takeIf { hasExplicitSecondUnitTextEvidence(resolverProduct) }
            ?.copy(evidence = PromotionEvidence.PRODUCT_TEXT)
        val reconstructedSecond = structuredSecond ?: textSecond
        val deepPromotion = PromotionInterpreter.fromProductSubtree(json)

        val specificPromotion = listOfNotNull(reconstructedSecond, deepPromotion, ownPromotion)
            .firstOrNull { context ->
                context.normalized.kind == PromotionKind.SECOND_UNIT ||
                    context.normalized.kind == PromotionKind.MULTIBUY
            }
        val directPromotion = listOfNotNull(deepPromotion, ownPromotion)
            .firstOrNull { context ->
                context.normalized.kind == PromotionKind.DIRECT_PERCENT &&
                    context.evidence != PromotionEvidence.INHERITED_SECTION &&
                    (sourceMarker !in DOM_SOURCES || originalPrice != null)
            }
        val acceptedInherited = inheritedPromotion?.takeIf { context ->
            context.normalized.kind == PromotionKind.SECOND_UNIT ||
                context.normalized.kind == PromotionKind.MULTIBUY
        }

        val selectedContext = specificPromotion ?: acceptedInherited
        val normalizedPromotion = selectedContext?.normalized
            ?: pricePromotion
            ?: directPromotion?.normalized
        val promotionEvidence = selectedContext?.evidence
            ?: pricePromotion?.let { PromotionEvidence.PRICE_PAIR }
            ?: directPromotion?.evidence
        val labelContext = selectedContext ?: directPromotion

        val strongId = firstText(json, STRONG_ID_KEYS)?.trim()?.takeIf(String::isNotBlank)
        val localId = json.opt("id").let(::textValue)?.trim()?.takeIf(String::isNotBlank)
        val explicitId = strongId ?: localId
        val imageIdentity = firstText(json, IMAGE_KEYS)?.trim().orEmpty()
        val variantIdentity = firstText(json, VARIANT_KEYS)?.trim().orEmpty()
        val linkIdentity = firstText(json, LINK_KEYS)?.trim().orEmpty()
        val brandIdentity = firstText(json, BRAND_KEYS)?.trim().orEmpty()
        val presentationIdentity = firstText(json, PRESENTATION_KEYS)?.trim().orEmpty()

        val hasProductSignal = sourceMarker in PRODUCT_SOURCES ||
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
        val advertised = normalizedPromotion
            ?.takeIf {
                it.kind == PromotionKind.DIRECT_PERCENT ||
                    it.kind == PromotionKind.SECOND_UNIT
            }
            ?.advertisedPercent

        val promoLabel = when {
            promotionEvidence == PromotionEvidence.PRICE_PAIR -> normalizedPromotion?.title
            labelContext != null -> labelContext.displayLabel
            else -> normalizedPromotion?.title
        }

        return CapturedProduct(
            key = "product:${sha256(fallbackIdentity)}",
            name = name,
            price = price,
            originalPrice = originalPrice,
            advertisedDiscountPercent = advertised,
            promoLabel = promoLabel,
            promotionCategory = normalizedPromotion?.categoryKey,
            promotionTitle = normalizedPromotion?.title,
            effectiveDiscountPercent = normalizedPromotion?.effectivePercent,
            promotionKind = normalizedPromotion?.kind,
            promotionEvidence = promotionEvidence,
            sourceUrl = sourceUrl,
        )
    }



    private fun hasExplicitSecondUnitTextEvidence(json: JSONObject): Boolean {
        var hasSecond = false
        var hasExplicitPercent = false
        var sameText = false

        fun inspect(node: Any?, depth: Int) {
            if (node == null || depth > 10 || sameText) return
            when (node) {
                is String -> {
                    val normalized = Normalizer.normalize(
                        node.lowercase(Locale.ROOT).replace('_', ' ').replace('-', ' '),
                        Normalizer.Form.NFD,
                    ).replace(Regex("\\p{M}+"), "")
                    val second = SECOND_UNIT_STRUCTURE.containsMatchIn(normalized)
                    val percent = EXPLICIT_PERCENT_TEXT.containsMatchIn(normalized)
                    hasSecond = hasSecond || second
                    hasExplicitPercent = hasExplicitPercent || percent
                    sameText = sameText || (second && NUMBER_PERCENT_TEXT.containsMatchIn(normalized))
                }
                is JSONObject -> {
                    val keys = node.keys()
                    while (keys.hasNext() && !sameText) {
                        val key = keys.next()
                        if (isProductCollectionKey(key) || key == "__smartDealsSectionPromotion") continue
                        inspect(node.opt(key), depth + 1)
                    }
                }
                is JSONArray -> for (index in 0 until node.length()) {
                    inspect(node.opt(index), depth + 1)
                    if (sameText) break
                }
            }
        }

        inspect(json, 0)
        return sameText || (hasSecond && hasExplicitPercent)
    }

    private fun hasTypedSecondUnitEvidence(
        json: JSONObject,
        context: PromotionContext,
    ): Boolean {
        if (context.normalized.kind != PromotionKind.SECOND_UNIT) return false
        val serialized = normalizeName(json.toString()).replace('-', ' ')
        val hasSecondMechanic = SECOND_UNIT_STRUCTURE.containsMatchIn(serialized)
        val hasPercentageMechanic = PERCENTAGE_STRUCTURE.containsMatchIn(serialized)
        val advertised = context.normalized.advertisedPercent ?: return false
        val number = if (kotlin.math.abs(advertised - advertised.toInt()) < 0.05) {
            advertised.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", advertised)
        }
        val normalizedNumber = number.replace('.', ' ')
        val hasValue = Regex(
            "(?:value|amount|percent|percentage|benefit)[^0-9]{0,25}${Regex.escape(normalizedNumber)}"
        ).containsMatchIn(serialized)
        return hasSecondMechanic && hasPercentageMechanic && hasValue
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
            n.contains("product_list") || n.contains("productlist") ||
            n.contains("recommend") || n.contains("related") || n.contains("similar")
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
            if (keys === PRICE_KEYS && containerKey in AMOUNT_PRICE_CONTAINERS) {
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
        "pricing", "priceInfo", "price_info", "prices", "sale",
        "product", "item", "content", "data", "details",
    )
    private val AMOUNT_PRICE_CONTAINERS = setOf(
        "pricing", "priceInfo", "price_info", "prices", "sale",
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
    private val DOM_SOURCES = setOf(
        "visible-dom", "exhaustive-dom", "catalog-route-dom",
    )
    private val PRODUCT_SOURCES = DOM_SOURCES + setOf(
        "large-json-fragment", "search-endpoint-fragment", "search-endpoint-v12",
    )

    private val EXPLICIT_PERCENT_TEXT = Regex(
        "(?:\\d{1,3}(?:[.,]\\d+)?\\s*%\\s*(?:off|dto|de descuento|descuento)|" +
            "(?:off|dto|descuento|ahorra|promo|oferta).{0,25}\\d{1,3}(?:[.,]\\d+)?\\s*%)"
    )
    private val NUMBER_PERCENT_TEXT = Regex("\\d{1,3}(?:[.,]\\d+)?\\s*%")

    private val SECOND_UNIT_STRUCTURE = Regex(
        "(?:second unit|second item|second product|segunda unidad|segundo producto|2da|2do)"
    )
    private val PERCENTAGE_STRUCTURE = Regex(
        "(?:percentage|percent|porcentaje|discount percentage|discount percent|benefit percentage)"
    )

    private companion object {
        const val MIN_PRICE = 20.0
        const val MAX_PRICE = 100_000_000.0
        const val MAX_DEPTH = 32
        const val MAX_PRODUCTS = 60_000
        const val LOCAL_SEARCH_DEPTH = 4
    }
}
