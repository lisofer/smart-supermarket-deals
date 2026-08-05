package com.lisofer.smartsupermarketdeals.data

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Final consistency layer for promotions whose commercial label explicitly says
 * "2da unidad al X%" but arrived classified as a direct X% discount.
 *
 * PedidosYa can split the mechanic and percentage across adjacent fields. Joining
 * the saved label and title lets us correct the classification without guessing
 * from an isolated percentage.
 */
internal object PromotionCanonicalizer {
    fun captured(product: CapturedProduct): CapturedProduct {
        val percent = secondUnitPercent(product.promoLabel, product.promotionTitle)
            ?: return product

        return product.copy(
            advertisedDiscountPercent = percent,
            promotionCategory = categoryKey(percent),
            promotionTitle = title(percent),
            effectiveDiscountPercent = percent / 2.0,
            promotionKind = PromotionKind.SECOND_UNIT,
            promotionEvidence = when (product.promotionEvidence) {
                PromotionEvidence.INHERITED_SECTION -> PromotionEvidence.INHERITED_SECTION
                else -> PromotionEvidence.PRODUCT_TEXT
            },
        )
    }

    fun deal(deal: PromotionDeal): PromotionDeal {
        val percent = secondUnitPercent(deal.promoLabel, deal.categoryTitle)
            ?: return deal

        return deal.copy(
            categoryKey = categoryKey(percent),
            categoryTitle = title(percent),
            effectiveDiscountPercent = percent / 2.0,
            promotionKind = PromotionKind.SECOND_UNIT,
        )
    }

    internal fun secondUnitPercent(vararg values: String?): Double? {
        val combined = values
            .filterNotNull()
            .filter(String::isNotBlank)
            .joinToString(" · ")
            .let(::normalize)

        if (combined.isBlank()) return null

        val raw = SECOND_UNIT_FORWARD.find(combined)?.groupValues?.getOrNull(1)
            ?: SECOND_UNIT_REVERSE.find(combined)?.groupValues?.getOrNull(1)
            ?: return null

        return raw
            .replace(',', '.')
            .toDoubleOrNull()
            ?.takeIf { it in 0.5..100.0 }
            ?.let(::rounded)
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('ª', 'a')
            .replace('º', 'o')
            .replace('°', 'o')
            .replace(Regex("[_–—-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun categoryKey(percent: Double): String =
        "second:${String.format(Locale.US, "%.1f", percent)}"

    private fun title(percent: Double): String =
        "2da unidad ${label(percent)}% OFF"

    private fun rounded(value: Double): Double = (value * 10.0).roundToInt() / 10.0

    private fun label(value: Double): String {
        return if (abs(value - value.roundToInt()) < 0.05) {
            value.roundToInt().toString()
        } else {
            String.format(Locale("es", "AR"), "%.1f", value)
        }
    }

    private const val SECOND_MARKER =
        "(?:\\b2\\s*\\.?\\s*(?:da|do|a|o)\\.?\\s*(?:unidad|producto|item)?\\b|" +
            "\\bsegunda\\s*(?:unidad|compra)?\\b|" +
            "\\bsegundo\\s*(?:producto|item)?\\b)"

    private const val PERCENT_VALUE = "(\\d{1,3}(?:[.,]\\d+)?)"
    private const val PERCENT_SUFFIX = "(?:\\s*%|\\s+off|\\s+dto|\\s+de descuento)"

    private val SECOND_UNIT_FORWARD = Regex(
        "$SECOND_MARKER[^0-9]{0,100}$PERCENT_VALUE$PERCENT_SUFFIX"
    )
    private val SECOND_UNIT_REVERSE = Regex(
        "$PERCENT_VALUE$PERCENT_SUFFIX[^0-9]{0,100}$SECOND_MARKER"
    )
}
