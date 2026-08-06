package com.lisofer.smartsupermarketdeals.scan

internal object ScanCompletionPolicy {
    const val FINAL_PAYLOAD_QUIET_MS = 2_500L
    const val FINAL_DRAIN_HARD_LIMIT_MS = 10_000L
    const val STORE_SCAN_TIMEOUT_MS = 20 * 60 * 1_000L

    fun isCompletionProgress(
        phase: String,
        endpointHarvestComplete: Boolean,
    ): Boolean {
        return endpointHarvestComplete ||
            phase.contains("catálogo interno terminado", ignoreCase = true) ||
            phase.contains("catalogo interno terminado", ignoreCase = true)
    }

    fun shouldFinish(
        searchFinishedAt: Long,
        lastPayloadAt: Long,
        pendingPayloads: Int,
        now: Long,
    ): Boolean {
        if (searchFinishedAt <= 0L || pendingPayloads > 0) return false
        val quietFor = now - lastPayloadAt
        val drainingFor = now - searchFinishedAt
        return quietFor >= FINAL_PAYLOAD_QUIET_MS ||
            drainingFor >= FINAL_DRAIN_HARD_LIMIT_MS
    }
}
