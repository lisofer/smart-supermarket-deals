package com.lisofer.smartsupermarketdeals.web

import org.junit.Test
import org.mozilla.javascript.Context

class SearchEndpointHarvesterScriptTest {
    @Test
    fun `endpoint harvester is valid JavaScript`() {
        val context = Context.enter()
        try {
            context.optimizationLevel = -1
            context.languageVersion = Context.VERSION_ES6
            context.compileString(
                searchEndpointHarvesterScript,
                "SearchEndpointHarvesterScript.js",
                1,
                null,
            )
        } finally {
            Context.exit()
        }
    }
}
