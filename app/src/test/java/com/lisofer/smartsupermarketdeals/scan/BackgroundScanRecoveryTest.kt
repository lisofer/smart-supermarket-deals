package com.lisofer.smartsupermarketdeals.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundScanRecoveryTest {
    @Test
    fun completedScanIsPersisted() {
        assertTrue(shouldPersistIncompleteScan(completed = true, verifiedPromotionCount = 0))
    }

    @Test
    fun interruptedScanKeepsVerifiedPromotions() {
        assertTrue(shouldPersistIncompleteScan(completed = false, verifiedPromotionCount = 238))
    }

    @Test
    fun interruptedEmptyScanDoesNotReplacePreviousResults() {
        assertFalse(shouldPersistIncompleteScan(completed = false, verifiedPromotionCount = 0))
    }
}
