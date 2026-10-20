// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.daemon.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** H2/M4: opt-in network flags are only encoded when enabled, so the default config stays upstream-identical. */
class AsteriskdNetworkOptionsEncodingTest {
    private fun network(capture: Boolean = false, block: Boolean = false) = AsteriskdNetworkConfig(
        enableIpv6 = false,
        disableSystemIpv6 = false,
        enableLocalDns = true,
        enableFakeDns = false,
        fakeDnsIpv4Pool = null,
        ignoredInterfaces = emptyList(),
        virtualInterfaces = emptyList(),
        hotspotInterfacePrefixes = emptyList(),
        proxyPrivateCidrs = emptyList(),
        bypassPrivateCidrs = emptyList(),
        appPolicy = AsteriskdAppPolicy(AsteriskdAppPolicyMode.Global, emptyList(), emptyList(), null, null),
        captureBoundNetworks = capture,
        blockEncryptedDns = block,
    )

    @Test
    fun defaultsAreOmitted() {
        val json = network().toJsonObject()
        assertFalse(json.containsKey("captureBoundNetworks"))
        assertFalse(json.containsKey("blockEncryptedDns"))
    }

    @Test
    fun enabledFlagsAreEncoded() {
        val json = network(capture = true, block = true).toJsonObject()
        assertEquals("true", json["captureBoundNetworks"].toString())
        assertEquals("true", json["blockEncryptedDns"].toString())
    }
}
