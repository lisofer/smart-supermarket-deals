package com.lisofer.smartsupermarketdeals.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadDeduplicatorTest {
    @Test
    fun keepsComplementaryCaptureSourcesAndDropsOnlyExactDuplicates() {
        val deduplicator = PayloadDeduplicator()
        val directDiscountBody = """
            {"products":[{"name":"Agua","tags":[{"label":"45% OFF"}]}]}
        """.trimIndent()
        val secondUnitBody = """
            {"products":[{"name":"Agua","tags":[{"label":"2DA AL 70% OFF"}]}]}
        """.trimIndent()

        assertTrue(
            deduplicator.accept(
                "https://www.pedidosya.com.ar/market#promotion-card-v13-1",
                directDiscountBody,
            )
        )
        assertTrue(
            deduplicator.accept(
                "https://www.pedidosya.com.ar/market#fast-coverage-1",
                secondUnitBody,
            )
        )
        assertFalse(
            deduplicator.accept(
                "https://www.pedidosya.com.ar/market#promotion-card-v13-1",
                directDiscountBody,
            )
        )
    }
}
