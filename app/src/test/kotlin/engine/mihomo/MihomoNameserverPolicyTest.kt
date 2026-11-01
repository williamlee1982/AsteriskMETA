// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo

import app.AppState
import app.modes.RunModeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.snakeyaml.engine.v2.api.Load

class MihomoNameserverPolicyTest {
    @Test
    fun starIsAPolicyKeyAndInvalidKeysAreDropped() {
        assertTrue(isMihomoNameserverPolicyKey("*"))
        assertTrue(isMihomoNameserverPolicyKey("*.lan"))
        assertTrue(isMihomoNameserverPolicyKey("+.ts.net"))
        assertTrue(isMihomoNameserverPolicyKey("wbvm"))
        assertTrue(isMihomoNameserverPolicyKey("geosite:cn,private"))
        assertTrue(isMihomoDomainPattern("*"))
        assertFalse(isMihomoNameserverPolicyKey("+"))
        assertFalse(isMihomoNameserverPolicyKey("*a"))
        assertFalse(isMihomoNameserverPolicyKey("a..b"))
        assertFalse(isMihomoNameserverPolicyKey("a."))

        val policy = listOf(
            "*=>tailscale://TS",
            "wbvm=>tailscale://TS",
            "+=>system",
            "*a=>1.1.1.1",
            "geosite:cn=>system",
            "+.internal.crop.com=>10.0.0.1",
        ).toMihomoNameserverPolicy()

        assertEquals("tailscale://TS", policy["*"])
        assertEquals("tailscale://TS", policy["wbvm"])
        assertEquals("system", policy["geosite:cn"])
        assertEquals("10.0.0.1", policy["+.internal.crop.com"])
        assertFalse(policy.containsKey("+"))
        assertFalse(policy.containsKey("*a"))
        assertFalse(policy.values.any { it.toString().contains("=>") })
    }

    @Test
    fun tailscaleSchemeIsANameserver() {
        assertTrue(isMihomoDnsServer("tailscale://TS"))
        assertTrue(isMihomoDnsServer("ts://TS-2"))
        assertFalse(isMihomoDnsServer("tailscale://"))
        assertFalse(isMihomoDnsServer("nope://1.1.1.1"))
    }

    @Test
    fun starPolicyIsQuotedAndDoesNotBecomeANameserver() {
        val yaml = linkedMapOf<String, Any?>(
            "proxies" to listOf(mapOf("name" to "TS", "type" to "tailscale")),
            "dns" to mapOf("enable" to true),
            "rules" to listOf("MATCH,DIRECT"),
        ).toAsteriskRuntimeProfileYaml(
            AppState(dnsNameserverPolicy = listOf("*=>tailscale://TS", "+=>system", "wbvm=>1.1.1.1")),
            RunModeVpnService,
            tunApplicationUids = emptyList(),
            useRootProviderPaths = false,
        )
        assertTrue(yaml, yaml.contains("'*'"))
        assertFalse(yaml, yaml.contains("*=>"))
        assertFalse(yaml, yaml.contains("+=>"))
        val runtime = Load(MihomoYamlLoadSettings).loadFromString(yaml) as Map<*, *>
        val dns = runtime["dns"] as Map<*, *>
        val policy = dns["nameserver-policy"] as Map<*, *>
        assertEquals("tailscale://TS", policy["*"])
        assertEquals("1.1.1.1", policy["wbvm"])
        assertNull(policy["+"])
        val nameservers = dns["nameserver"] as List<*>
        assertTrue(nameservers.none { it.toString().contains("=>") || it.toString().startsWith("*") })
    }
}
