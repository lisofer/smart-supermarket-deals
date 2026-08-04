package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.CapturedProduct
import com.lisofer.smartsupermarketdeals.data.PromotionKind
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
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

            val root: Any = when {
                body.trimStart().startsWith("[") -> JSONArray(body)
                else -> JSONObject(body)
            }
            val output = LinkedHashMap<String, CapturedProduct>()
            walk(root, sourceUrl, output, depth = 0)
            output.values.toList()
        }.getOrDefault(emptyList())
    }

    /** Keeps the richest observation when the same product arrives in several payloads. */
    fun prefer(existing: CapturedProduct?, incoming: CapturedProduct): CapturedProduct {
        if (existing == null) return incoming
        val existingScore = qualityScore(existing)
        val incomingScore = qualityScore(incoming)
        return if (incomingScore > existingScore) incoming else existing
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
        val name = firstText(json, NAME_KEYS)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.length in 3..180 }
            ?: return null

        // Deliberately local: recursively reading the first child price made an entire
        // product list look like one product and caused many offers to collapse together.
        val price = firstNumberLocal(json, PRICE_KEYS)
            ?.takeIf { it in MIN_PRICE..MAX_PRICE }
            ?: return null

        val promoTexts = collectPromoTexts(json)
        val promoLabel = promoTexts
            .joinToString(" · ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(180)
            .takeIf { it.isNotBlank() }
        val explicitPercent = firstPercentLocal(json)
            ?.takeIf { it in 0.5..95.0 }
        val explicitOriginal = firstNumberLocal(json, ORIGINAL_PRICE_KEYS)
            ?.takeIf { it > price && it <= MAX_PRICE }

        val normalizedPromotion = normalizePromotion(
            price = price,
            originalPrice = explicitOriginal,
            explicitPercent = explicitPercent,
            promoText = promoLabel.orEmpty(),
        )

        val explicitId = firstText(json, ID_KEYS)?.trim()?.takeIf { it.isNotBlank() }
        val imageIdentity = firstText(json, IMAGE_KEYS)?.trim().orEmpty()
        val hasProductSignal = explicitId != null ||
            IMAGE_KEYS.any(json::has) ||
            CATEGORY_KEYS.any(json::has) ||
            json.keys().asSequence().any { key ->
                key.contains("product", ignoreCase = true) ||
                    key.contains("item", ignoreCase = true) ||
                    key.contains("sku", ignoreCase = true)
            }
        if (!hasProductSignal) return null

        val normalizedName = name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9áéíóúüñ]+"), "-")
            .trim('-')
        // Name is always part of the identity. Some PedidosYa payloads reuse a promotion
        // or container id for many products; using that id alone overwrote almost all of them.
        val identity = listOf(explicitId.orEmpty(), normalizedName, imageIdentity)
            .joinToString("|")
        val key = "product:${sha256(identity)}"

        return CapturedProduct(
            key = key,
            name = name,
            price = price,
            originalPrice = explicitOriginal,
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
        val normalizedText = promoText
            .lowercase(Locale.ROOT)
            .replace('×', 'x')
            .replace(Regex("\\s+"), " ")

        if (SECOND_FREE_REGEX.containsMatchIn(normalizedText)) {
            return NormalizedPromotion(
                categoryKey = "multibuy:2x1",
                title = "2x1",
                effectivePercent = 50.0,
                kind = PromotionKind.MULTIBUY,
            )
        }

        MULTIBUY_REGEX.find(normalizedText)?.let { match ->
            val quantity = match.groupValues[1].toIntOrNull() ?: return@let
            val paid = match.groupValues[2].toIntOrNull() ?: return@let
            if (quantity in 2..12 && paid in 1 until quantity) {
                val effective = ((quantity - paid).toDouble() / quantity.toDouble()) * 100.0
                return NormalizedPromotion(
                    categoryKey = "multibuy:${quantity}x$paid",
                    title = "${quantity}x$paid",
                    effectivePercent = effective,
                    kind = PromotionKind.MULTIBUY,
                )
            }
        }

        TAKE_PAY_REGEX.find(normalizedText)?.let { match ->
            val quantity = match.groupValues[1].toIntOrNull() ?: return@let
            val paid = match.groupValues[2].toIntOrNull() ?: return@let
            if (quantity in 2..12 && paid in 1 until quantity) {
                val effective = ((quantity - paid).toDouble() / quantity.toDouble()) * 100.0
                return NormalizedPromotion(
                    categoryKey = "multibuy:${quantity}x$paid",
                    title = "${quantity}x$paid",
                    effectivePercent = effective,
                    kind = PromotionKind.MULTIBUY,
                )
            }
        }

        val secondUnitPercent = SECOND_UNIT_FORWARD_REGEX.find(normalizedText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toPercent()
            ?: SECOND_UNIT_REVERSE_REGEX.find(normalizedText)
                ?.groupValues
                ?.getOrNull(1)
                ?.toPercent()
        if (secondUnitPercent != null && secondUnitPercent in 0.5..100.0) {
            return NormalizedPromotion(
                categoryKey = "second:${percentKey(secondUnitPercent)}",
                title = "2da unidad ${percentLabel(secondUnitPercent)}% OFF",
                effectivePercent = secondUnitPercent / 2.0,
                kind = PromotionKind.SECOND_UNIT,
            )
        }

        val textPercent = DIRECT_PERCENT_REGEX.find(normalizedText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toPercent()
            ?: DIRECT_OFF_REGEX.find(normalizedText)
                ?.groupValues
                ?.getOrNull(1)
                ?.toPercent()
        val directPercent = (explicitPercent ?: textPercent)
            ?.takeIf { it in 0.5..95.0 }
        if (directPercent != null) {
            return directPromotion(directPercent)
        }

        if (originalPrice != null && originalPrice > price) {
            val calculated = ((originalPrice - price) / originalPrice) * 100.0
            if (calculated in 0.5..95.0) return directPromotion(calculated)
        }

        return null
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

    private fun percentKey(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun percentLabel(value: Double): String {
        return if (abs(value - value.roundToInt()) < 0.05) {
            value.roundToInt().toString()
        } else {
            String.format(Locale("es", "AR"), "%.1f", value)
        }
    }

    private fun String.toPercent(): Double? = replace(',', '.').toDoubleOrNull()

    private fun firstText(json: JSONObject, keys: List<String>): String? {
        keys.forEach { key ->
            textFromValue(json.opt(key))?.let { return it }
        }
        return null
    }

    private fun textFromValue(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.isNotBlank() }
        is Number -> value.toString()
        is JSONObject -> listOf("name", "title", "label", "text", "description", "value", "url")
            .firstNotNullOfOrNull { nested ->
                value.optString(nested).trim().takeIf { it.isNotBlank() }
            }
        else -> null
    }

    private fun firstNumberLocal(json: JSONObject, keys: List<String>): Double? {
        keys.forEach { key ->
            val value = json.opt(key)
            parseNumber(value)?.let { return it }
            if (value is JSONObject) {
                NUMBER_VALUE_KEYS.forEach { nested ->
                    parseNumber(value.opt(nested))?.let { return it }
                }
            }
        }

        PRICE_CONTAINER_KEYS.forEach { containerKey ->
            val container = json.optJSONObject(containerKey) ?: return@forEach
            keys.forEach { key ->
                parseNumber(container.opt(key))?.let { return it }
                val value = container.optJSONObject(key)
                if (value != null) {
                    NUMBER_VALUE_KEYS.forEach { nested ->
                        parseNumber(value.opt(nested))?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun firstPercentLocal(json: JSONObject): Double? {
        DISCOUNT_PERCENT_KEYS.forEach { key ->
            parsePercent(json.opt(key))?.let { return it }
        }
        PROMO_TEXT_KEYS.forEach { key ->
            val value = json.optJSONObject(key) ?: return@forEach
            DISCOUNT_PERCENT_KEYS.forEach { nested ->
                parsePercent(value.opt(nested))?.let { return it }
            }
        }
        return null
    }

    private fun collectPromoTexts(json: JSONObject): List<String> {
        val output = LinkedHashSet<String>()
        PROMO_TEXT_KEYS.forEach { key ->
            collectStrings(json.opt(key), output, depth = 0)
        }
        if (PROMO_BOOLEAN_KEYS.any { key -> json.optBoolean(key, false) }) {
            output += "Promoción publicada"
        }
        return output.take(MAX_PROMO_TEXTS)
    }

    private fun collectStrings(value: Any?, output: MutableSet<String>, depth: Int) {
        if (value == null || depth > PROMO_SEARCH_DEPTH || output.size >= MAX_PROMO_TEXTS) return
        when (value) {
            is String -> sanitizePromoText(value)?.let(output::add)
            is JSONObject -> {
                PROMO_OBJECT_TEXT_KEYS.forEach { key ->
                    sanitizePromoText(value.optString(key))?.let(output::add)
                }
                val keys = value.keys()
                while (keys.hasNext() && output.size < MAX_PROMO_TEXTS) {
                    collectStrings(value.opt(keys.next()), output, depth + 1)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectStrings(value.opt(index), output, depth + 1)
                    if (output.size >= MAX_PROMO_TEXTS) break
                }
            }
        }
    }

    private fun sanitizePromoText(raw: String): String? {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        return text.takeIf { it.length in 2..160 }
    }

    private fun parsePercent(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> Regex("(\\d{1,3}(?:[.,]\\d+)?)")
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

    private val MULTIBUY_REGEX = Regex("(?i)\\b(\\d{1,2})\\s*x\\s*(\\d{1,2})\\b")
    private val TAKE_PAY_REGEX = Regex("(?i)lleva(?:ndo)?\\s*(\\d{1,2}).{0,20}?paga(?:ndo)?\\s*(\\d{1,2})")
    private val SECOND_FREE_REGEX = Regex("(?i)(?:2\\s*(?:da|do|°|º)?|segunda|segundo)\\s*(?:unidad)?\\s*(?:gratis|sin cargo)")
    private val SECOND_UNIT_FORWARD_REGEX = Regex(
        "(?i)(?:2\\s*(?:da|do|°|º)?|segunda|segundo)\\s*(?:unidad)?\\s*" +
            "(?:al|con|a|:)?\\s*(\\d{1,3}(?:[.,]\\d+)?)\\s*%?\\s*" +
            "(?:off|de descuento|descuento)?"
    )
    private val SECOND_UNIT_REVERSE_REGEX = Regex(
        "(?i)(\\d{1,3}(?:[.,]\\d+)?)\\s*%?\\s*(?:off|de descuento|descuento)?" +
            ".{0,20}?(?:2\\s*(?:da|do|°|º)?|segunda|segundo)\\s*(?:unidad)?"
    )
    private val DIRECT_PERCENT_REGEX = Regex(
        "(?i)(\\d{1,3}(?:[.,]\\d+)?)\\s*(?:%|por ciento)\\s*(?:off|de descuento|descuento)?"
    )
    private val DIRECT_OFF_REGEX = Regex("(?i)(\\d{1,3}(?:[.,]\\d+)?)\\s*off\\b")

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
    private val PRICE_CONTAINER_KEYS = listOf(
        "pricing", "priceInfo", "price_info", "prices", "commercial", "sale",
    )
    private val NUMBER_VALUE_KEYS = listOf(
        "amount", "value", "price", "current", "total", "units",
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
        "campaign", "campaigns", "tags", "benefit", "benefits",
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

    private const val MIN_PRICE = 20.0
    private const val MAX_PRICE = 100_000_000.0
    private const val MAX_DEPTH = 18
    private const val PROMO_SEARCH_DEPTH = 4
    private const val MAX_PROMO_TEXTS = 12
    private const val MAX_PRODUCTS_PER_MESSAGE = 4_000
}
