package com.lisofer.smartsupermarketdeals.scan

/**
 * Drops only byte-equivalent catalog responses. It deliberately does not filter by source name,
 * because different PedidosYa capture paths can contain complementary promotion metadata.
 */
internal class PayloadDeduplicator {
    private val signatures = HashSet<String>()

    fun accept(url: String, body: String): Boolean {
        val signature = buildString {
            append(url.length)
            append(':')
            append(url.hashCode())
            append(':')
            append(body.length)
            append(':')
            append(body.hashCode())
        }
        return signatures.add(signature)
    }
}
