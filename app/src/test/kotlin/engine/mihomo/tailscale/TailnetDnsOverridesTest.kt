// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

import app.AppState
import app.modes.RunModeTproxy
import app.modes.RunModeVpnService
import engine.mihomo.MihomoYamlLoadSettings
import engine.mihomo.toAsteriskRuntimeProfileYaml
import engine.network.TailnetIpv4Cidr
import engine.network.TailnetIpv6Cidr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        "rules" to listOf(DnsOutRule, "MATCH,DIRECT"),
        *extra,
    )

    @Test
    fun shortNamesAreOnByDefault() {
        assertTrue(AppState().tailnetShortNamesEnabled)
    }

    @Test
    fun defaultsRouteMagicDnsAndSingleLabelsThroughTheFirstTailscaleOutbound() {
        val profile = profile().apply { putTailnetDnsOverrides() }
        assertEquals(
            mapOf(
                "localhost" to "system",
                "*" to "tailscale://TS",
                "+.ts.net" to "tailscale://TS",
            ),
            (profile["dns"] as Map<*, *>)["nameserver-policy"],
        )
        assertEquals(listOf(DnsOutRule) + shortNameRules("TS") + "DOMAIN-SUFFIX,ts.net,TS" + "MATCH,DIRECT", profile["rules"])
    }

    @Test
    fun shortNamesCanBeDisabledWithoutDroppingSuffixRouting() {
        val profile = profile().apply { putTailnetDnsOverrides(shortNames = false) }
        assertEquals(mapOf("+.ts.net" to "tailscale://TS"), (profile["dns"] as Map<*, *>)["nameserver-policy"])
        assertEquals(listOf(DnsOutRule, "DOMAIN-SUFFIX,ts.net,TS", "MATCH,DIRECT"), profile["rules"])
    }

    @Test
    fun settingsAreConfigurableAndStripped() {
        val profile = profile(
            AsteriskTailnetDnsKey to mapOf(
                "outbound" to "TS-2",
                "suffixes" to listOf("+.Tailnet.Example.test", ".corp.example", "bad suffix"),
                "rules" to false,
            ),
        ).apply { putTailnetDnsOverrides() }
        assertFalse(profile.containsKey(AsteriskTailnetDnsKey))
        assertEquals(
            mapOf(
                "localhost" to "system",
                "*" to "tailscale://TS-2",
                "+.tailnet.example.test" to "tailscale://TS-2",
                "+.corp.example" to "tailscale://TS-2",
            ),
            (profile["dns"] as Map<*, *>)["nameserver-policy"],
        )
        assertEquals(listOf(DnsOutRule, "MATCH,DIRECT"), profile["rules"])
    }

    @Test
    fun userEntriesWin() {
        val profile = profile().apply {
            put("dns", linkedMapOf("enable" to true, "nameserver-policy" to mapOf("+.TS.net" to "100.100.100.100", "*" to "1.1.1.1")))
            put("rules", listOf("DOMAIN-SUFFIX,ts.net,DIRECT", "IP-CIDR,$TailnetIpv4Cidr,DIRECT,no-resolve", "MATCH,DIRECT"))
            putTailnetDnsOverrides()
        }
        val policy = (profile["dns"] as Map<*, *>)["nameserver-policy"] as Map<*, *>
        assertEquals("100.100.100.100", policy["+.TS.net"])
        assertEquals("1.1.1.1", policy["*"])
        assertEquals("system", policy["localhost"])
        val rules = profile["rules"] as List<*>
        assertEquals(1, rules.count { it.toString().startsWith("IP-CIDR,$TailnetIpv4Cidr,") })
        assertTrue(rules.contains("IP-CIDR,$TailnetIpv4Cidr,DIRECT,no-resolve"))
        assertTrue(rules.contains("DOMAIN-SUFFIX,ts.net,DIRECT"))
        assertFalse(rules.contains("DOMAIN-SUFFIX,ts.net,TS"))
    }

    @Test
    fun disabledOrInapplicableLeavesTheProfileAlone() {
        for (value in listOf(false, mapOf("enable" to false), mapOf("outbound" to "plain"), mapOf("outbound" to "missing"))) {
            val profile = profile(AsteriskTailnetDnsKey to value).apply { putTailnetDnsOverrides() }
            assertNull("value=$value", (profile["dns"] as Map<*, *>)["nameserver-policy"])
            assertFalse(profile.containsKey(AsteriskTailnetDnsKey))
            assertEquals(listOf(DnsOutRule, "MATCH,DIRECT"), profile["rules"])
        }
        val invalidSuffix = profile(AsteriskTailnetDnsKey to mapOf("suffixes" to listOf("!!"))).apply { putTailnetDnsOverrides() }
        val policy = (invalidSuffix["dns"] as Map<*, *>)["nameserver-policy"] as Map<*, *>
        assertEquals("tailscale://TS", policy["*"])
        assertFalse(policy.containsKey("+.ts.net"))
        val noTailscale = linkedMapOf<String, Any?>("proxies" to emptyList<Any>(), "rules" to listOf("MATCH,DIRECT"))
        noTailscale.putTailnetDnsOverrides()
        assertEquals(listOf("MATCH,DIRECT"), noTailscale["rules"])
    }

    @Test
    fun appliesInVpnAndRootRuntimeProfiles() {
        for (runMode in listOf(RunModeVpnService, RunModeTproxy)) {
            val yaml = profile(AsteriskTailnetDnsKey to mapOf("suffixes" to listOf("ts.net")))
                .toAsteriskRuntimeProfileYaml(
                    AppState(mihomoControlSecret = "fixture-secret"),
                    runMode,
                    tunApplicationUids = emptyList(),
                    useRootProviderPaths = false,
                )
            assertTrue(yaml.contains("'*'"))
            assertFalse(yaml.contains("*=>"))
            val runtime = Load(MihomoYamlLoadSettings).loadFromString(yaml) as Map<*, *>
            val policy = (runtime["dns"] as Map<*, *>)["nameserver-policy"] as Map<*, *>
            assertEquals("runMode=$runMode", "tailscale://TS", policy["+.ts.net"])
            assertEquals("runMode=$runMode", "tailscale://TS", policy["*"])
            val rules = runtime["rules"] as List<*>
            assertEquals("runMode=$runMode", true, "DOMAIN-SUFFIX,ts.net,TS" in rules)
            assertEquals("runMode=$runMode", true, "DOMAIN-REGEX,$SingleLabelDomainRegex,TS" in rules)
            assertEquals("runMode=$runMode", true, "IP-CIDR,$TailnetIpv4Cidr,TS,no-resolve" in rules)
            assertEquals(
                "runMode=$runMode",
                true,
                rules.indexOf("DOMAIN-SUFFIX,ts.net,TS") > rules.indexOfLast { (it as String).endsWith(",dns-out") },
            )
            assertFalse(runtime.containsKey(AsteriskTailnetDnsKey))
        }
    }

    @Test
    fun appSettingTurnsShortNamesOffInTheRuntimeProfile() {
        val yaml = profile().toAsteriskRuntimeProfileYaml(
            AppState(tailnetShortNamesEnabled = false),
            RunModeVpnService,
            tunApplicationUids = emptyList(),
            useRootProviderPaths = false,
        )
        val runtime = Load(MihomoYamlLoadSettings).loadFromString(yaml) as Map<*, *>
        val policy = (runtime["dns"] as Map<*, *>)["nameserver-policy"] as Map<*, *>
        assertEquals("tailscale://TS", policy["+.ts.net"])
        assertFalse(policy.containsKey("*"))
        val rules = runtime["rules"] as List<*>
        assertFalse(rules.any { it.toString().startsWith("DOMAIN-REGEX,") })
        assertFalse(rules.any { it.toString().startsWith("IP-CIDR,$TailnetIpv4Cidr,") })
    }

    private fun shortNameRules(outbound: String): List<String> = listOf(
        "DOMAIN,localhost,DIRECT",
        "DOMAIN-REGEX,$SingleLabelDomainRegex,$outbound",
        "IP-CIDR,$TailnetIpv4Cidr,$outbound,no-resolve",
        "IP-CIDR6,$TailnetIpv6Cidr,$outbound,no-resolve",
    )

    private companion object {
        const val DnsOutRule = "AND,((IN-TYPE,SOCKS),(NETWORK,UDP),(DST-PORT,53)),dns-out"
    }
}
