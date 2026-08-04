package com.lisofer.smartsupermarketdeals.parser

import com.lisofer.smartsupermarketdeals.data.PromotionKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        val promo = PromotionInterpreter.fromObject(
            JSONObject("""{"badge":"2x1"}""")
        )
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
}
