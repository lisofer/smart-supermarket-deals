package com.lisofer.smartsupermarketdeals.scan

/**
 * Keeps the background wrapper from changing the search lifecycle used by version 1.2.2.
 * The WebView is created once and is never reloaded or replaced during a store scan.
 */
internal object LegacyV122ScanContract {
    const val WEBVIEW_INSTANCES_PER_STORE = 1
    const val RELOAD_ON_STALL = false
    const val RESTART_AFTER_TIMEOUT = false
    const val CATEGORY_FIRST = false
    const val STRATEGIC_COVERAGE = false
}
