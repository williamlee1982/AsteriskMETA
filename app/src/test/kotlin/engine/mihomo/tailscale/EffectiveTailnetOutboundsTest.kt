// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

import app.MihomoOverrideScriptState
import app.MihomoProfileState
import engine.mihomo.MihomoProfileScriptEngine
import engine.mihomo.effectiveMihomoTailscaleOutbounds
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveTailnetOutboundsTest {
    private val profile = MihomoProfileState(id = 1, name = "Example", overrideScriptId = 7)
    private val script = MihomoOverrideScriptState(
        id = 7,
        name = "Add Tailnet",
        content = "function main(config) { config.proxies = [{ name: 'TS', type: 'tailscale' }]; return config; }",
    )
    private val mustNotEvaluate = MihomoProfileScriptEngine { error("Script must not run") }

    @Test
    fun appliesTheBoundScriptBeforeExtractingOutbounds() {
        val outbounds = effectiveMihomoTailscaleOutbounds(
            "proxies: []",
            profile,
            listOf(script.copy(id = 8, content = "wrong script"), script),
            // QuickJS is Android-native; inject only its evaluation boundary on the JVM.
            MihomoProfileScriptEngine { code ->
                assertTrue(code.contains(script.content))
                assertTrue(code.contains("\\\"proxies\\\":[]"))
                """{"proxies":[{"name":"TS","type":"tailscale","hostname":"example-node","state-dir":"./ts"}]}"""
            },
        )

        assertEquals(listOf("TS"), outbounds.map { it.name })
        assertEquals("example-node", outbounds.single().hostname)
        assertEquals("./ts", outbounds.single().stateDir)
    }

    @Test
    fun effectiveConfigReplacesRawOutboundsRatherThanMergingThem() {
        val outbounds = effectiveMihomoTailscaleOutbounds(
            "proxies: [{ name: raw, type: tailscale }]",
            profile,
            listOf(script),
            MihomoProfileScriptEngine { """{"proxies":[]}""" },
        )

        assertTrue(outbounds.isEmpty())
    }

    @Test
    fun failedOrMissingScriptsFallBackToRawOutbounds() {
        val raw = "proxies: [{ name: raw, type: tailscale }]"
        for (scripts in listOf(listOf(script), emptyList())) {
            val outbounds = effectiveMihomoTailscaleOutbounds(raw, profile, scripts, mustNotEvaluate)
            assertEquals(listOf("raw"), outbounds.map { it.name })
        }
    }

    @Test
    fun disabledUnboundOrBlankScriptsKeepTheRawProfile() {
        val raw = "proxies: [{ name: raw, type: tailscale }]"
        var evaluations = 0
        val engine = MihomoProfileScriptEngine {
            evaluations++
            """{"proxies":[]}"""
        }
        for ((selected, scripts) in listOf(
            profile.copy(disableOverrides = true) to listOf(script),
            profile.copy(overrideScriptId = 0) to listOf(script),
            profile to listOf(script.copy(content = "")),
            null to listOf(script),
        )) {
            assertEquals(
                listOf("raw"),
                effectiveMihomoTailscaleOutbounds(raw, selected, scripts, engine).map { it.name },
            )
        }
        assertEquals(0, evaluations)
    }

    @Test
    fun invalidAndNonObjectYamlStayEmpty() {
        for (raw in listOf("", "proxies: [", "- list", "scalar")) {
            assertTrue(effectiveMihomoTailscaleOutbounds(raw, profile, listOf(script), mustNotEvaluate).isEmpty())
        }
    }

    @Test
    fun supplementaryNamesSurviveScriptApplication() {
        val outbounds = effectiveMihomoTailscaleOutbounds(
            "proxies: [{ name: 'TS-🚀', type: tailscale }]",
            profile,
            listOf(script.copy(content = "function main(config) { return config; }")),
            MihomoProfileScriptEngine { code ->
                // Return the same serialized config that the script receives.
                val argument = code.substringAfter("var config = JSON.parse(").substringBefore(")\n")
                kotlinx.serialization.json.Json.decodeFromString<String>(argument)
            },
        )

        assertEquals("TS-🚀", outbounds.single().name)
    }

    @Test
    fun cancellationIsNotTreatedAsScriptFailure() {
        assertThrows(CancellationException::class.java) {
            effectiveMihomoTailscaleOutbounds(
                "proxies: []", profile, listOf(script),
                MihomoProfileScriptEngine { throw CancellationException("Profile changed") },
            )
        }
    }
}
