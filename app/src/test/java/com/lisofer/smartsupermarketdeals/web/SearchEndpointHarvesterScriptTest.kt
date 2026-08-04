package com.lisofer.smartsupermarketdeals.web

import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.EvaluatorException

class SearchEndpointHarvesterScriptTest {
    @Test
    fun `endpoint harvester is valid JavaScript`() {
        val context = Context.enter()
        try {
            context.optimizationLevel = -1
            context.languageVersion = Context.VERSION_ES6
            try {
                context.compileString(
                    searchEndpointHarvesterScript,
                    "SearchEndpointHarvesterScript.js",
                    1,
                    null,
                )
            } catch (error: EvaluatorException) {
                throw AssertionError(
                    buildString {
                        append("JavaScript inválido: ")
                        append(error.message)
                        append(" · línea=")
                        append(error.lineNumber())
                        append(" · columna=")
                        append(error.columnNumber())
                        append(" · fragmento=")
                        append(error.lineSource())
                    },
                    error,
                )
            }
        } finally {
            Context.exit()
        }
    }
}
