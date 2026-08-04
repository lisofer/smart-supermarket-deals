package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.PromotionEvidence
import com.lisofer.smartsupermarketdeals.data.PromotionKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromotionInterpreterTest {
    @Test
    fun percentageMetadataIsDirectDiscount() {
        val promo = PromotionInterpreter.fromObject(
            JSONObject("""{"type":"PERCENTAGE","value":15,"scope":"ALL"}""")
        )
        assertNotNull(promo)
        assertEquals(PromotionKind.DIRECT_PERCENT, promo!!.normalized.kind)
        assertEquals(15.0, promo.normalized.effectivePercent, 0.01)
    }

    @Test
    fun secondUnitLabelIsRecognized() {
        val promo = PromotionInterpreter.fromObject(
            JSONObject("""{"label":"2DA AL 60% OFF"}""")
        )
        assertNotNull(promo)
        assertEquals(PromotionKind.SECOND_UNIT, promo!!.normalized.kind)
        assertEquals(30.0, promo.normalized.effectivePercent, 0.01)
    }

    @Test
    fun twoForOneLabelIsRecognized() {
        val promo = PromotionInterpreter.fromObject(JSONObject("""{"badge":"2x1"}"""))
        assertNotNull(promo)
        assertEquals(PromotionKind.MULTIBUY, promo!!.normalized.kind)
        assertEquals(50.0, promo.normalized.effectivePercent, 0.01)
    }

    @Test
    fun uuidDoesNotBecomeSecondUnit() {
        val promo = PromotionInterpreter.fromObject(
            JSONObject(
                """{"promotion":{"id":"50b1e094-4f84-4257-b462-d659cfd37347","type":"PERCENTAGE","value":15,"scope":"ALL"}}"""
            )
        )
        assertNotNull(promo)
        assertEquals(PromotionKind.DIRECT_PERCENT, promo!!.normalized.kind)
        assertEquals(15.0, promo.normalized.effectivePercent, 0.01)
    }

    @Test
    fun bareNutritionalPercentageIsNotDiscount() {
        val promo = PromotionInterpreter.fromObject(
            JSONObject("""{"name":"Chocolate 50% cacao","description":"Contiene 50% cacao"}""")
        )
        assertNull(promo)
    }

    @Test
    fun splitSecondUnitStructureOverridesDirectPercentage() {
        val product = JSONObject(
            """
            {
              "id":"split-50",
              "name":"Producto con segunda unidad",
              "price":1200,
              "commercial":{
                "mechanic":{"type":"SECOND_UNIT"},
                "benefit":{"type":"PERCENTAGE","value":50}
              }
            }
            """.trimIndent()
        )

        val promo = SecondUnitPromotionResolver.fromProductSubtree(product)
        assertNotNull(promo)
        assertEquals(PromotionKind.SECOND_UNIT, promo!!.normalized.kind)
        assertEquals(50.0, promo.normalized.advertisedPercent!!, 0.01)
        assertEquals(25.0, promo.normalized.effectivePercent, 0.01)
    }

    @Test
    fun splitDomTextIsSecondUnitNotDirectFifty() {
        val product = JSONObject(
            """
            {
              "id":"dom-50",
              "name":"Producto detectado en pantalla",
              "price":"$ 1.500",
              "promotionText":"2DA UNIDAD · 50% OFF",
              "commercial":{"text":"2DA UNIDAD · 50% OFF"},
              "source":"exhaustive-dom"
            }
            """.trimIndent()
        )

        val promo = SplitSecondUnitTextResolver.fromProductSubtree(product)
        assertNotNull(promo)
        assertEquals(PromotionKind.SECOND_UNIT, promo!!.normalized.kind)
        assertEquals(50.0, promo.normalized.advertisedPercent!!, 0.01)
        assertEquals(25.0, promo.normalized.effectivePercent, 0.01)
    }

    @Test
    fun extractorKeepsSplitSecondUnitContext() {
        val body =
            """
            {
              "products":[
                {
                  "id":"extractor-50",
                  "name":"Yerba con promo",
                  "price":2400,
                  "commercial":{
                    "label":"2DA UNIDAD",
                    "benefit":{"type":"PERCENTAGE","value":50}
                  }
                }
              ]
            }
            """.trimIndent()
        val envelope = JSONObject()
            .put("url", "https://www.pedidosya.com.ar/store/test")
            .put("body", body)
            .toString()

        val products = ProductJsonExtractor.extract(envelope)
        assertTrue(products.isNotEmpty())
        val product = products.first { it.name == "Yerba con promo" }
        assertEquals(PromotionKind.SECOND_UNIT, product.promotionKind)
        assertEquals(50.0, product.advertisedDiscountPercent!!, 0.01)
        assertEquals(25.0, product.effectiveDiscountPercent!!, 0.01)
    }

    @Test
    fun directFiftyWithoutSecondSignalStaysDirect() {
        val product = JSONObject(
            """
            {
              "id":"direct-50",
              "name":"Producto con descuento directo",
              "price":1000,
              "promotion":{"type":"PERCENTAGE","value":50}
            }
            """.trimIndent()
        )

        val promo = PromotionInterpreter.fromProductSubtree(product)
        assertNotNull(promo)
        assertEquals(PromotionKind.DIRECT_PERCENT, promo!!.normalized.kind)
        assertEquals(50.0, promo.normalized.effectivePercent, 0.01)
    }

    @Test
    fun pageLevelDirectPercentageIsNotAppliedToEveryProduct() {
        val body =
            """
            {
              "promotion":{"type":"PERCENTAGE","value":50},
              "products":[
                {"id":"regular-1","name":"Producto regular","price":1000}
              ]
            }
            """.trimIndent()
        val envelope = JSONObject()
            .put("url", "https://www.pedidosya.com.ar/store/test")
            .put("body", body)
            .toString()

        val product = ProductJsonExtractor.extract(envelope)
            .first { it.name == "Producto regular" }
        assertNull(product.promotionKind)
        assertNull(product.promotionEvidence)
    }

    @Test
    fun inheritedSecondUnitSectionCanApplyToItsProducts() {
        val body =
            """
            {
              "sections":[
                {
                  "title":"2DA UNIDAD 50% OFF",
                  "commercial":{
                    "mechanic":{"type":"SECOND_UNIT"},
                    "benefit":{"type":"PERCENTAGE","value":50}
                  },
                  "products":[
                    {"id":"second-1","name":"Yerba de sección","price":2400}
                  ]
                }
              ]
            }
            """.trimIndent()
        val envelope = JSONObject()
            .put("url", "https://www.pedidosya.com.ar/store/test")
            .put("body", body)
            .toString()

        val product = ProductJsonExtractor.extract(envelope)
            .first { it.name == "Yerba de sección" }
        assertEquals(PromotionKind.SECOND_UNIT, product.promotionKind)
        assertEquals(25.0, product.effectiveDiscountPercent!!, 0.01)
        assertEquals(PromotionEvidence.INHERITED_SECTION, product.promotionEvidence)
    }

    @Test
    fun inheritedPayloadSecondUnitIsKeptWeakAndCorrect() {
        val body =
            """
            {
              "products":[
                {
                  "id":"payload-second-1",
                  "name":"Aceite con segunda unidad",
                  "price":3000,
                  "source":"search-endpoint-v12",
                  "__smartDealsSectionPromotion":{
                    "__smartDealsInherited":true,
                    "label":"2DA UNIDAD 60% OFF",
                    "commercial":{
                      "mechanic":{"type":"SECOND_UNIT"},
                      "benefit":{"type":"PERCENTAGE","value":60}
                    }
                  }
                }
              ]
            }
            """.trimIndent()
        val envelope = JSONObject()
            .put("url", "https://www.pedidosya.com.ar/store/test")
            .put("body", body)
            .toString()

        val product = ProductJsonExtractor.extract(envelope)
            .first { it.name == "Aceite con segunda unidad" }
        assertEquals(PromotionKind.SECOND_UNIT, product.promotionKind)
        assertEquals(30.0, product.effectiveDiscountPercent!!, 0.01)
        assertEquals(PromotionEvidence.INHERITED_SECTION, product.promotionEvidence)
    }

    @Test
    fun secondUnitMechanicDoesNotBorrowCacaoPercentage() {
        val body =
            """
            {
              "products":[
                {
                  "id":"cacao-1",
                  "name":"Chocolate 50% cacao",
                  "price":1800,
                  "description":"Chocolate elaborado con 50% cacao",
                  "commercial":{"mechanic":{"type":"SECOND_UNIT"}}
                }
              ]
            }
            """.trimIndent()
        val envelope = JSONObject()
            .put("url", "https://www.pedidosya.com.ar/store/test")
            .put("body", body)
            .toString()

        val product = ProductJsonExtractor.extract(envelope)
            .first { it.name == "Chocolate 50% cacao" }
        assertNull(product.promotionKind)
        assertNull(product.promotionEvidence)
    }

    @Test
    fun publishedOldPriceIsStrongEvidence() {
        val body =
            """
            {
              "products":[
                {
                  "id":"price-pair-1",
                  "name":"Producto con precio tachado",
                  "price":800,
                  "originalPrice":1000
                }
              ]
            }
            """.trimIndent()
        val envelope = JSONObject()
            .put("url", "https://www.pedidosya.com.ar/store/test")
            .put("body", body)
            .toString()

        val product = ProductJsonExtractor.extract(envelope)
            .first { it.name == "Producto con precio tachado" }
        assertEquals(PromotionKind.DIRECT_PERCENT, product.promotionKind)
        assertEquals(20.0, product.effectiveDiscountPercent!!, 0.01)
        assertEquals(PromotionEvidence.PRICE_PAIR, product.promotionEvidence)
    }
}
