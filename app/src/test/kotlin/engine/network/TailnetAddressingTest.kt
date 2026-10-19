// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetAddressingTest {
    @Test
    fun hostOverridesInsideTailnetRangesAreRecognized() {
        val overrides = tailnetPrivateAddressOverrides(
            listOf("100.101.102.103/32", " fd7a:115c:a1e0::1/128 ", "100.101.102.103/32"),
        )
        assertEquals(listOf("100.101.102.103/32", "fd7a:115c:a1e0::1/128"), overrides.hostOverrides)
        assertTrue(overrides.broadOverrides.isEmpty())
    }

    @Test
    fun broadOverridesOverlappingCgnatAreFlagged() {
        val overrides = tailnetPrivateAddressOverrides(
            listOf(TailnetIpv4Cidr, "100.0.0.0/8", "100.100.0.0/16", "fd7a:115c:a1e0::/48"),
        )
        assertTrue(overrides.hostOverrides.isEmpty())
        assertEquals(listOf(TailnetIpv4Cidr, "100.0.0.0/8", "100.100.0.0/16", "fd7a:115c:a1e0::/48"), overrides.broadOverrides)
    }

    @Test
    fun unrelatedAndInvalidEntriesAreIgnored() {
        val overrides = tailnetPrivateAddressOverrides(
            listOf("10.0.0.0/8", "192.168.1.10/32", "100.63.255.255/32", "100.128.0.1/32", "not-a-cidr", "fd00::1/128"),
        )
        assertTrue(overrides.hostOverrides.isEmpty())
        assertTrue(overrides.broadOverrides.isEmpty())
    }

    @Test
    fun addressAndContainmentHelpers() {
        assertTrue(isTailnetAddress("100.64.0.1"))
        assertTrue(isTailnetAddress("100.127.255.254"))
        assertFalse(isTailnetAddress("100.128.0.1"))
        assertTrue(isTailnetAddress("fd7a:115c:a1e0::5"))
        assertFalse(isTailnetAddress("example.ts.net"))
        assertTrue(cidrContainsAddress("100.64.0.0/10", "100.101.102.103"))
        assertFalse(cidrContainsAddress("100.101.102.103/32", "100.101.102.104"))
        assertFalse(cidrContainsAddress("100.64.0.0/10", "fd7a:115c:a1e0::5"))
    }
}
