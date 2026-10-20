// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeIpPoolsTest {
    @Test
    fun validPoolsAreIpv4WithPrefixOneToThirty() {
        assertTrue(isValidFakeIpIpv4Pool(DefaultFakeIpIpv4Pool))
        assertTrue(isValidFakeIpIpv4Pool("198.18.0.1/16"))
        assertTrue(isValidFakeIpIpv4Pool("10.0.0.0/29"))
        // Mihomo reserves network, gateway and .2/.3; a /30 has no usable FakeIP address.
        assertFalse(isValidFakeIpIpv4Pool("10.0.0.0/30"))
        assertFalse(isValidFakeIpIpv4Pool("10.0.0.0/31"))
        assertFalse(isValidFakeIpIpv4Pool("0.0.0.0/0"))
        assertFalse(isValidFakeIpIpv4Pool("fc00::/18"))
        assertFalse(isValidFakeIpIpv4Pool("198.18.0.0"))
        assertFalse(isValidFakeIpIpv4Pool("300.1.1.1/16"))
    }

    @Test
    fun poolsOverlappingTailnetCgnatAreRejected() {
        assertTrue(fakeIpPoolOverlapsTailnet("100.64.0.0/10"))
        assertTrue(fakeIpPoolOverlapsTailnet("100.100.0.0/16"))
        assertTrue(fakeIpPoolOverlapsTailnet("100.0.0.0/8"))
        assertFalse(fakeIpPoolOverlapsTailnet(DefaultFakeIpIpv4Pool))
        assertFalse(fakeIpPoolOverlapsTailnet("100.128.0.0/16"))
        assertFalse(isValidFakeIpIpv4Pool("100.100.0.0/16"))
        assertFalse(isValidFakeIpIpv4Pool("100.0.0.0/8"))
    }

    @Test
    fun onlyIpv6PoolsAreResetToDefault() {
        assertEquals(DefaultFakeIpIpv4Pool, "fc00::/18".withoutIpv6FakeIpPool())
        assertEquals("198.18.0.1/16", "198.18.0.1/16".withoutIpv6FakeIpPool())
        assertEquals("28.0.0.1/8", "28.0.0.1/8".withoutIpv6FakeIpPool())
    }

    @Test
    fun defaultPoolDoesNotOverlapTailnetCgnat() {
        assertFalse(cidrContainsAddress(DefaultFakeIpIpv4Pool, "100.64.0.1"))
        assertFalse(cidrContainsAddress(TailnetIpv4Cidr, "198.18.0.1"))
    }
}
