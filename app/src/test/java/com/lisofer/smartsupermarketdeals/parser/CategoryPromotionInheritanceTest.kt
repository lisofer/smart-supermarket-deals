package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.PromotionKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryPromotionInheritanceTest {
    @Test
    fun sharedTwoForOneAppliesToEverySiblingVariant() {
        val inheritedPromotion = JSONObject()
            .put("label", "2x1")
            .put("promotionText", "2x1")
            .put("__smartDealsInherited", true)

        val products = JSONArray()
            .put(
                JSONObject()
                    .put("id", "milka-white")
                    .put("name", "Milka chocolate blanco")
                    .put("price", 3000)
                    .put("source", "catalog-response-v14")
                    .put("__smartDealsSectionPromotion", JSONObject(inheritedPromotion.toString()))
            )
            .put(
                JSONObject()
                    .put("id", "milka-common")
                    .put("name", "Milka chocolate común")
                    .put("price", 3000)
                    .put("source", "catalog-response-v14")
                    .put("__smartDealsSectionPromotion", JSONObject(inheritedPromotion.toString()))
            )

        val message = JSONObject()
            .put("url", "https://www.pedidosya.com.ar/market/chocolates#catalog-response-v14")
            .put("body", JSONObject().put("products", products).toString())
            .toString()

        val parsed = ProductJsonExtractor.extract(message)

        assertEquals(2, parsed.size)
        assertTrue(parsed.any { it.name == "Milka chocolate blanco" })
        assertTrue(parsed.any { it.name == "Milka chocolate común" })
        assertTrue(parsed.all { it.promotionKind == PromotionKind.MULTIBUY })
        assertTrue(parsed.all { it.effectiveDiscountPercent == 50.0 })
    }
}
