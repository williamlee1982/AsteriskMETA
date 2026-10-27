// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import app.AppState
import app.modes.RunModeBpf2Socks
import app.modes.RunModeTproxy
import app.modes.RunModeTun
import app.modes.RunModeTun2Socks
import app.modes.RunModeVpnService
import engine.mihomo.tailscale.TailnetOutboundKey
import engine.mihomo.tailscale.TailscaleBackendState
import engine.mihomo.tailscale.TailscaleCoreOutbound
import engine.mihomo.tailscale.TailscaleStatusQuery
import engine.mihomo.tailscale.TailscaleStatusUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetPrivateAddressSyncPlanTest {
    @Test
    fun firstRunningObservationInARoutingModeRestarts() {
        val plan = plan(tproxy(running = true), peers("100.101.102.103", "fd7a:115c:a1e0::5"))

        assertEquals(TailnetPrivateAddressAction.SaveAndRestart, plan.action)
        assertEquals(listOf("100.101.102.103/32", "fd7a:115c:a1e0::5/128"), plan.privateAddressCidrs)
        assertEquals(listOf("100.101.102.103/32", "fd7a:115c:a1e0::5/128"), plan.managedCidrs)
        assertEquals(2, plan.added)
        assertEquals(0, plan.removed)
        assertFalse(tailnetPrivateAddressSyncLog(plan).contains("100.101"))
        assertFalse(tailnetPrivateAddressSyncLog(plan).contains("fd7a"))
        assertTrue(tailnetPrivateAddressSyncLog(plan).contains("peers=2"))
    }

    @Test
    fun secondPollWithTheSamePeersDoesNotRestart() {
        val first = plan(tproxy(running = true), peers("100.101.102.103"))
        val saved = tproxy(running = true).copy(
            privateAddressCidrs = first.privateAddressCidrs,
            tailnetManagedPrivateAddressCidrs = first.managedCidrs,
        )
        val applied = first.runtime.copy(appliedCidrs = first.privateAddressCidrs)

        val second = planTailnetPrivateAddressSync(saved, peers("100.101.102.103"), applied, nowMillis = 0L)

        assertEquals(TailnetPrivateAddressAction.Keep, second.action)
    }

    @Test
    fun vpnAndRootTunPersistWithoutRestart() {
        for (mode in listOf(RunModeVpnService, RunModeTun)) {
            val plan = plan(AppState(runMode = mode, proxyRunning = true), peers("100.101.102.103"))
            assertEquals(mode.toString(), TailnetPrivateAddressAction.Save, plan.action)
            assertEquals(listOf("100.101.102.103/32"), plan.privateAddressCidrs)
        }
    }

    @Test
    fun tun2SocksAndBpf2SocksRestartLikeTproxy() {
        for (mode in listOf(RunModeTun2Socks, RunModeBpf2Socks)) {
            val plan = plan(AppState(runMode = mode, proxyRunning = true), peers("100.101.102.103"))
            assertEquals(mode.toString(), TailnetPrivateAddressAction.SaveAndRestart, plan.action)
        }
    }

    @Test
    fun ipv6OnlyChangeDoesNotRestartWhileIpv6IsDisabled() {
        val plan = plan(
            tproxy(running = true).copy(enableIpv6 = false),
            peers("fd7a:115c:a1e0::5"),
        )

        assertEquals(TailnetPrivateAddressAction.Save, plan.action)
        assertEquals(listOf("fd7a:115c:a1e0::5/128"), plan.privateAddressCidrs)
    }

    @Test
    fun disappearedPeerRestartsARoutingModeAndKeepsManualEntries() {
        val state = tproxy(running = true).copy(
            privateAddressCidrs = listOf("10.0.0.0/8", "100.101.102.103/32", "100.101.102.104/32"),
            tailnetManagedPrivateAddressCidrs = listOf("100.101.102.104/32"),
        )
        val runtime = TailnetPrivateAddressRuntime(
            proxyWasRunning = true,
            appliedRunMode = RunModeTproxy,
            appliedCidrs = state.privateAddressCidrs,
        )

        val plan = planTailnetPrivateAddressSync(state, peers("100.101.102.103"), runtime, nowMillis = 0L)

        assertEquals(listOf("10.0.0.0/8", "100.101.102.103/32"), plan.privateAddressCidrs)
        assertEquals(emptyList<String>(), plan.managedCidrs)
        assertEquals(1, plan.removed)
        assertEquals(TailnetPrivateAddressAction.SaveAndRestart, plan.action)
    }

    @Test
    fun failedRestartIsNotRetriedUntilTheCooldownElapses() {
        val state = tproxy(running = true).copy(
            privateAddressCidrs = listOf("100.101.102.103/32"),
            tailnetManagedPrivateAddressCidrs = listOf("100.101.102.103/32"),
        )
        val runtime = TailnetPrivateAddressRuntime(
            proxyWasRunning = true,
            appliedRunMode = RunModeTproxy,
            appliedCidrs = emptyList(),
            retryNotBeforeMillis = 60_000L,
        )

        val waiting = planTailnetPrivateAddressSync(state, peers("100.101.102.103"), runtime, nowMillis = 1_000L)
        val ready = planTailnetPrivateAddressSync(state, peers("100.101.102.103"), runtime, nowMillis = 60_000L)

        assertEquals(TailnetPrivateAddressAction.Keep, waiting.action)
        assertEquals(TailnetPrivateAddressAction.SaveAndRestart, ready.action)
    }

    @Test
    fun unknownStatusDoesNotChangeARunningProxy() {
        val state = tproxy(running = true).copy(
            privateAddressCidrs = listOf("10.0.0.0/8"),
        )
        val runtime = TailnetPrivateAddressRuntime(
            proxyWasRunning = true,
            appliedRunMode = RunModeTproxy,
            appliedCidrs = state.privateAddressCidrs,
        )
        val plan = planTailnetPrivateAddressSync(
            state,
            TailscaleStatusQuery.Unavailable(TailscaleStatusUnavailableReason.QueryFailed),
            runtime,
            nowMillis = 0L,
        )

        assertEquals(TailnetPrivateAddressAction.Keep, plan.action)
        assertEquals(state.privateAddressCidrs, plan.privateAddressCidrs)
    }

    @Test
    fun switchingTheFeatureOffWithdrawsManagedHosts() {
        val state = tproxy(running = true).copy(
            tailnetPrivateAddressSyncEnabled = false,
            privateAddressCidrs = listOf("10.0.0.0/8", "100.101.102.104/32"),
            tailnetManagedPrivateAddressCidrs = listOf("100.101.102.104/32"),
        )
        val runtime = TailnetPrivateAddressRuntime(
            proxyWasRunning = true,
            appliedRunMode = RunModeTproxy,
            appliedCidrs = state.privateAddressCidrs,
        )

        val plan = planTailnetPrivateAddressSync(state, peers("100.101.102.104"), runtime, nowMillis = 0L)

        assertEquals(listOf("10.0.0.0/8"), plan.privateAddressCidrs)
        assertEquals(emptyList<String>(), plan.managedCidrs)
        assertEquals(TailnetPrivateAddressAction.SaveAndRestart, plan.action)
    }

    @Test
    fun openingTheAppDoesNotRestartWhenPeersMatchTheSavedList() {
        val state = tproxy(running = true).copy(
            privateAddressCidrs = listOf("100.101.102.103/32"),
            tailnetManagedPrivateAddressCidrs = listOf("100.101.102.103/32"),
        )

        val plan = plan(state, peers("100.101.102.103"))

        assertEquals(TailnetPrivateAddressAction.Keep, plan.action)
        assertTrue(plan.runtime.proxyWasRunning)
        assertEquals(state.privateAddressCidrs, plan.runtime.appliedCidrs)
    }

    private fun plan(state: AppState, statuses: TailscaleStatusQuery) =
        planTailnetPrivateAddressSync(state, statuses, TailnetPrivateAddressRuntime(), nowMillis = 0L)

    private fun tproxy(running: Boolean) = AppState(runMode = RunModeTproxy, proxyRunning = running)

    private fun peers(vararg addresses: String): TailscaleStatusQuery = TailscaleStatusQuery.Available(
        listOf(
            TailscaleCoreOutbound(
                TailnetOutboundKey("ts"),
                TailscaleBackendState.Running(
                    tailnetAddresses = listOf("100.101.102.10"),
                    hostname = null,
                    peerIps = addresses.toList() + "100.101.102.10",
                ),
                config = null,
            ),
        ),
    )
}
