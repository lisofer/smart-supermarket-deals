package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.PromotionKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PedidosYaPromotionCardRegressionTest {
    @Test
    fun explicitSecondUnitBadgeReplacesAmbiguousDirectCondition() {
        val body =
            """
            {
              "products":[
                {
                  "productId":"h2oh-manzana-500",
                  "name":"Agua Saborizada H2Oh! Manzana 500 ml",
                  "pricing":{"price":"$ 1.769","beforePrice":""},
                  "commercial":{"label":"1 ud. al 70% dto"},
                  "source":"search-endpoint-v12"
                },
                {
                  "productId":"h2oh-manzana-500",
                  "name":"Agua Saborizada H2Oh! Manzana 500 ml",
                  "pricing":{"price":"$ 1.769","beforePrice":""},
                  "tags":[{"label":"2DA AL 70% OFF"}],
                  "commercial":{"label":"1 ud. al 70% dto"},
                  "source":"promotion-card-v13"
                }
              ]
            }
            """.trimIndent()

        val product = extract(body, "Agua Saborizada H2Oh! Manzana 500 ml")

        assertEquals(PromotionKind.SECOND_UNIT, product.promotionKind)
        assertEquals(70.0, product.advertisedDiscountPercent!!, 0.01)
        assertEquals(35.0, product.effectiveDiscountPercent!!, 0.01)
        assertEquals("2da unidad 70% OFF", product.promotionTitle)
    }

    @Test
    fun directPriceReductionRemainsDirectDiscount() {
        val body =
            """
            {
              "products":[
                {
                  "productId":"h2oh-naranja-1500",
                  "name":"Agua H2Oh! Saborizada Sin Gas De Naranja 1.5 L",
                  "pricing":{
                    "price":"$ 1.883,75",
                    "beforePrice":"$ 3.425"
                  },
                  "tags":[{"label":"45% OFF"}],
                  "source":"promotion-card-v13"
                }
              ]
            }
            """.trimIndent()

        val product = extract(body, "Agua H2Oh! Saborizada Sin Gas De Naranja 1.5 L")

        assertEquals(PromotionKind.DIRECT_PERCENT, product.promotionKind)
        assertEquals(45.0, product.effectiveDiscountPercent!!, 0.01)
        assertEquals(3425.0, product.originalPrice!!, 0.01)
    }

    @Test
    fun ordinaryPercentageBadgeWithoutOldPriceIsStillCollected() {
        val body =
            """
            {
              "products":[
                {
                  "productId":"direct-badge-25",
                  "name":"Producto con descuento común",
                  "pricing":{"price":"$ 1.500"},
                  "tags":[{"text":"25% OFF"}],
                  "source":"promotion-card-v13"
                }
              ]
            }
            """.trimIndent()

        val product = extract(body, "Producto con descuento común")

        assertEquals(PromotionKind.DIRECT_PERCENT, product.promotionKind)
        assertEquals(25.0, product.effectiveDiscountPercent!!, 0.01)
        assertEquals("25% OFF", product.promotionTitle)
    }

    private fun extract(body: String, name: String) = ProductJsonExtractor.extract(
        JSONObject()
            .put("url", "https://www.pedidosya.com.ar/market-test")
            .put("body", body)
            .toString()
    ).firstOrNull { it.name == name }.also(::assertNotNull)!!
}
