package com.mantel.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Where the viewer's JavaScript and CSS actually live.
 *
 * Vite writes hashed filenames, so the OG shell cannot name them and the jar has to be asked. In
 * development the shell points at the Vite server instead, which is what makes `just dev` serve a
 * working album at :8080 rather than only at :5173.
 */
class WebAssets private constructor(
    private val devOrigin: String?,
    private val script: String?,
    private val styles: List<String>,
) {
    fun headTags(): String =
        if (devOrigin != null) {
            // Vite's React plugin injects this preamble into HTML it serves itself. This shell is
            // served by Ktor, so without these lines every component throws "can't detect preamble"
            // and the page renders nothing.
            """
            <script type="module">
              import RefreshRuntime from '$devOrigin/@react-refresh'
              RefreshRuntime.injectIntoGlobalHook(window)
              window.${'$'}RefreshReg${'$'} = () => {}
              window.${'$'}RefreshSig${'$'} = () => (type) => type
              window.__vite_plugin_react_preamble_installed__ = true
            </script>
            <script type="module" src="$devOrigin/@vite/client"></script>
            """.trimIndent()
        } else {
            styles.joinToString("\n") { """<link rel="stylesheet" href="/$it" />""" }
        }

    fun bodyTags(): String =
        if (devOrigin != null) {
            """<script type="module" src="$devOrigin/src/viewer/main.tsx"></script>"""
        } else {
            script?.let { """<script type="module" src="/$it"></script>""" } ?: ""
        }

    companion object {
        private const val ENTRY = "viewer.html"

        fun load(devOrigin: String?): WebAssets {
            if (devOrigin != null) return WebAssets(devOrigin, null, emptyList())

            val manifest =
                WebAssets::class.java.getResourceAsStream("/web/.vite/manifest.json")?.use {
                    Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
                }
            val entry = manifest?.get(ENTRY)?.jsonObject
            return WebAssets(
                devOrigin = null,
                script = entry?.get("file")?.jsonPrimitive?.content?.let { "assets/${it.substringAfterLast('/')}" },
                styles =
                    entry?.get("css")?.jsonArray.orEmpty().map {
                        "assets/${it.jsonPrimitive.content.substringAfterLast('/')}"
                    },
            )
        }
    }
}
