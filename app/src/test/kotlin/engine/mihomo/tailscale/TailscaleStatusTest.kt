// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleStatusTest {
    @Test
    fun ipnStatesMapToTypedStates() {
        assertEquals(TailscaleBackendState.Starting, tailscaleBackendStateFromIpn("NoState"))
        assertEquals(TailscaleBackendState.Starting, tailscaleBackendStateFromIpn("Starting"))
        assertEquals(TailscaleBackendState.Stopped, tailscaleBackendStateFromIpn("Stopped"))
        assertEquals(TailscaleBackendState.NeedsLogin(true), tailscaleBackendStateFromIpn("NeedsLogin", loginUrlAvailable = true))
        assertEquals(
            TailscaleBackendState.NeedsLogin(loginUrlAvailable = false, machineAuthorization = true),
            tailscaleBackendStateFromIpn("NeedsMachineAuth", loginUrlAvailable = true),
        )
        assertEquals(
            TailscaleBackendState.Running(listOf("100.101.102.103"), "example-node"),
            tailscaleBackendStateFromIpn("Running", tailnetAddresses = listOf("100.101.102.103"), hostname = "example-node"),
        )
        assertTrue(tailscaleBackendStateFromIpn("InUseOtherUser") is TailscaleBackendState.Error)
    }

    @Test
    fun bundledCoreReportsTheMissingApiInsteadOfGuessing() {
        // Documents the core gap (MetaCubeX/mihomo#3203): AndroidLibClashLite exports no
        // Tailscale state, and log scraping is not an acceptable substitute.
        assertEquals(
            TailscaleStatusResult.Unavailable(TailscaleStatusUnavailableReason.CoreApiMissing),
            BundledCoreTailscaleStatusSource.query("TS", proxyRunning = true),
        )
        assertEquals(
            TailscaleStatusResult.Unavailable(TailscaleStatusUnavailableReason.ProxyNotRunning),
            BundledCoreTailscaleStatusSource.query("TS", proxyRunning = false),
        )
    }
}
