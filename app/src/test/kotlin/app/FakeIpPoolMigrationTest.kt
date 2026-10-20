// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package app

import engine.network.DefaultFakeIpIpv4Pool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeIpPoolMigrationTest {
    @Test
    fun ipv6PoolIsResetAndTailnetSettingsSurvive() {
        val migrated = AppState(
            dnsFakeIpRange = "fc00::/18",
            tailnetInboundForwardsEnabled = true,
        ).withCompatibleProxyAppListMode()

        assertEquals(DefaultFakeIpIpv4Pool, migrated.dnsFakeIpRange)
        assertTrue(migrated.tailnetInboundForwardsEnabled)
    }

    @Test
    fun savedIpv4PoolIsKeptUnchanged() {
        val state = AppState(dnsFakeIpRange = "198.18.0.1/16")
        assertEquals("198.18.0.1/16", state.withCompatibleProxyAppListMode().dnsFakeIpRange)
    }

    @Test
    fun compatibleStateIsReturnedAsIs() {
        val state = AppState()
        assertSame(state, state.withCompatibleProxyAppListMode())
    }
}
