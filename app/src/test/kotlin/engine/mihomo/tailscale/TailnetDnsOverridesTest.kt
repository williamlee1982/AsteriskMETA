// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

import app.AppState
import app.modes.RunModeTproxy
import app.modes.RunModeVpnService
import engine.mihomo.MihomoYamlLoadSettings
import engine.mihomo.toAsteriskRuntimeProfileYaml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.snakeyaml.engine.v2.api.Load

class TailnetDnsOverridesTest {
    private fun profile(vararg extra: Pair<String, Any?>): MutableMap<String, Any?> = linkedMapOf(
        "proxies" to listOf(
            mapOf("name" to "plain", "type" to "socks5"),
            mapOf("name" to "TS", "type" to "tailscale"),
            mapOf("name" to "TS-2", "type" to "tailscale"),
        ),
        "dns" to linkedMapOf("enable" to true, "nameserver" to listOf("https://dns.example.test/dns-query")),
        "rules" to listOf("AND,((IN-TYPE,SOCKS),(NETWORK,UDP),(DST-PORT,53)),dns-out", "MATCH,DIRECT"),
        *extra,
    )

    @Test
    fun defaultsRouteMagicDnsThroughTheFirstTailscaleOutbound() {
        val profile = profile().apply { putTailnetDnsOverrides() }
        assertEquals(mapOf("+.ts.net" to "tailscale://TS"), (profile["dns"] as Map<*, *>)["nameserver-policy"])
        assertEquals(
            listOf("AND,((IN-TYPE,SOCKS),(NETWORK,UDP),(DST-PORT,53)),dns-out", "DOMAIN-SUFFIX,ts.net,TS", "MATCH,DIRECT"),
            profile["rules"],
        )
    }

    @Test
    fun settingsAreConfigurableAndStripped() {
        val profile = profile(
            AsteriskTailnetDnsKey to mapOf("outbound" to "TS-2", "suffixes" to listOf("+.Tailnet.Example.test", ".corp.example", "bad suffix"), "rules" to false),
        ).apply { putTailnetDnsOverrides() }
        assertFalse(profile.containsKey(AsteriskTailnetDnsKey))
        assertEquals(
            mapOf("+.tailnet.example.test" to "tailscale://TS-2", "+.corp.example" to "tailscale://TS-2"),
            (profile["dns"] as Map<*, *>)["nameserver-policy"],
        )
        assertEquals(listOf("AND,((IN-TYPE,SOCKS),(NETWORK,UDP),(DST-PORT,53)),dns-out", "MATCH,DIRECT"), profile["rules"])
    }

    @Test
    fun userEntriesWin() {
        val profile = profile().apply {
            put("dns", linkedMapOf("enable" to true, "nameserver-policy" to mapOf("+.TS.net" to "100.100.100.100")))
            put("rules", listOf("DOMAIN-SUFFIX,ts.net,DIRECT", "MATCH,DIRECT"))
            putTailnetDnsOverrides()
        }
        assertEquals(mapOf("+.TS.net" to "100.100.100.100"), (profile["dns"] as Map<*, *>)["nameserver-policy"])
        assertEquals(listOf("DOMAIN-SUFFIX,ts.net,DIRECT", "MATCH,DIRECT"), profile["rules"])
    }

    @Test
    fun disabledOrInapplicableLeavesTheProfileAlone() {
        for (value in listOf(false, mapOf("enable" to false), mapOf("outbound" to "plain"), mapOf("outbound" to "missing"), mapOf("suffixes" to listOf("!!")))) {
            val profile = profile(AsteriskTailnetDnsKey to value).apply { putTailnetDnsOverrides() }
            assertNull("value=$value", (profile["dns"] as Map<*, *>)["nameserver-policy"])
            assertFalse(profile.containsKey(AsteriskTailnetDnsKey))
        }
        val noTailscale = linkedMapOf<String, Any?>("proxies" to emptyList<Any>(), "rules" to listOf("MATCH,DIRECT"))
        noTailscale.putTailnetDnsOverrides()
        assertEquals(listOf("MATCH,DIRECT"), noTailscale["rules"])
    }

    @Test
    fun appliesInVpnAndRootRuntimeProfiles() {
        for (runMode in listOf(RunModeVpnService, RunModeTproxy)) {
            val yaml = profile(AsteriskTailnetDnsKey to mapOf("suffixes" to listOf("ts.net")))
                .toAsteriskRuntimeProfileYaml(AppState(), runMode, tunApplicationUids = emptyList(), useRootProviderPaths = false)
            val runtime = Load(MihomoYamlLoadSettings).loadFromString(yaml) as Map<*, *>
            val policy = (runtime["dns"] as Map<*, *>)["nameserver-policy"] as Map<*, *>
            assertEquals("runMode=$runMode", "tailscale://TS", policy["+.ts.net"])
            val rules = runtime["rules"] as List<*>
            assertEquals("runMode=$runMode", true, "DOMAIN-SUFFIX,ts.net,TS" in rules)
            assertEquals("runMode=$runMode", true, rules.indexOf("DOMAIN-SUFFIX,ts.net,TS") > rules.indexOfLast { (it as String).endsWith(",dns-out") })
            assertFalse(runtime.containsKey(AsteriskTailnetDnsKey))
        }
    }
}
