package com.lisofer.smartsupermarketdeals.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCompletionPolicyTest {
    @Test
    fun finalEndpointProgressAlsoCountsAsCompletion() {
        assertTrue(
            ScanCompletionPolicy.isCompletionProgress(
                phase = "Catálogo interno terminado",
                endpointHarvestComplete = false,
            )
        )
        assertTrue(
            ScanCompletionPolicy.isCompletionProgress(
                phase = "cualquier texto",
                endpointHarvestComplete = true,
            )
        )
    }

    @Test
    fun normalCompletionWaitsForQueueAndQuietPeriod() {
        val now = 20_000L
        assertFalse(
            ScanCompletionPolicy.shouldFinish(
                searchFinishedAt = 15_000L,
                lastPayloadAt = 19_000L,
                pendingPayloads = 0,
                now = now,
            )
        )
        assertTrue(
            ScanCompletionPolicy.shouldFinish(
                searchFinishedAt = 15_000L,
                lastPayloadAt = now - ScanCompletionPolicy.FINAL_PAYLOAD_QUIET_MS,
                pendingPayloads = 0,
                now = now,
            )
        )
    }

    @Test
    fun repeatedLateMessagesCannotKeepFinishedSearchOpenForever() {
        val now = 30_000L
        assertTrue(
            ScanCompletionPolicy.shouldFinish(
                searchFinishedAt = now - ScanCompletionPolicy.FINAL_DRAIN_HARD_LIMIT_MS,
                lastPayloadAt = now - 100L,
                pendingPayloads = 0,
                now = now,
            )
        )
    }

    @Test
    fun pendingPayloadsAreAlwaysDrainedBeforeClosing() {
        val now = 30_000L
        assertFalse(
            ScanCompletionPolicy.shouldFinish(
                searchFinishedAt = 1L,
                lastPayloadAt = 1L,
                pendingPayloads = 1,
                now = now,
            )
        )
    }
}
