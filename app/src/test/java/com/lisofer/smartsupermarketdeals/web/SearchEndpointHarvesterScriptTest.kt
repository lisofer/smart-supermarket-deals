package com.lisofer.smartsupermarketdeals.web

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchEndpointHarvesterScriptTest {
    @Test
    fun `endpoint harvester is valid modern JavaScript`() {
        val script = Files.createTempFile("smart-deals-endpoint-harvester", ".js")
        try {
            Files.write(
                script,
                searchEndpointHarvesterScript.toByteArray(Charsets.UTF_8),
            )
            val process = ProcessBuilder("node", "--check", script.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            assertEquals(
                "El parser V8 rechazó el JavaScript:\n$output",
                0,
                exitCode,
            )
        } finally {
            Files.deleteIfExists(script)
        }
    }
}
