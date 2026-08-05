package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.PromotionKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProductJsonExtractorBatchTest {
    @Test
    fun batchKeepsDirectAndSecondUnitPromotionsSeparated() {
        val direct = envelope(
            """
            {
              "products":[
                {
                  "productId":"direct-45",
                  "name":"Agua naranja 1.5 L",
                  "pricing":{"price":"$ 1.883,75","beforePrice":"$ 3.425"},
                  "tags":[{"label":"45% OFF"}]
                }
              ]
            }
            """.trimIndent()
        )
        val secondUnit = envelope(
            """
            {
              "products":[
                {
                  "productId":"second-70",
                  "name":"Agua manzana 500 ml",
                  "pricing":{"price":"$ 1.769"},
                  "commercial":{"label":"1 ud. al 70% dto"}
                }
              ]
            }
            """.trimIndent()
        )

        val message = JSONObject()
            .put("event", "payload_batch")
            .put("payloads", JSONArray().put(direct).put(secondUnit))
            .toString()

        val products = ProductJsonExtractor.extract(message)
        val directProduct = products.firstOrNull { it.key.contains("direct-45") }
        val secondProduct = products.firstOrNull { it.key.contains("second-70") }

        assertNotNull(directProduct)
        assertNotNull(secondProduct)
        assertEquals(PromotionKind.DIRECT_PERCENT, directProduct!!.promotionKind)
        assertEquals(45.0, directProduct.effectiveDiscountPercent!!, 0.01)
        assertEquals(PromotionKind.SECOND_UNIT, secondProduct!!.promotionKind)
        assertEquals("2da al 70% OFF", secondProduct.promotionTitle)
        assertEquals(35.0, secondProduct.effectiveDiscountPercent!!, 0.01)
    }

    private fun envelope(body: String): JSONObject = JSONObject()
        .put("url", "https://www.pedidosya.com.ar/market-test#fast-coverage")
        .put("body", body)
}
