package com.lisofer.smartsupermarketdeals.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PedidosYaPayloadFilterTest {
    @Test
    fun directPromotionCardsRemainEnabledDuringFastCoverage() {
        assertFalse(
            shouldSuppressFastCoveragePayload(
                payloadUrl = "https://www.pedidosya.com.ar/market#promotion-card-v13-45",
                fastCoverageActive = true,
            )
        )
    }

    @Test
    fun duplicatedEndpointReplayIsSuppressedDuringFastCoverage() {
        assertTrue(
            shouldSuppressFastCoveragePayload(
                payloadUrl = "https://www.pedidosya.com.ar/market#endpoint-v12-45",
                fastCoverageActive = true,
            )
        )
    }

    @Test
    fun endpointPayloadIsNotSuppressedOutsideFastCoverage() {
        assertFalse(
            shouldSuppressFastCoveragePayload(
                payloadUrl = "https://www.pedidosya.com.ar/market#endpoint-v12-45",
                fastCoverageActive = false,
            )
        )
    }
}
