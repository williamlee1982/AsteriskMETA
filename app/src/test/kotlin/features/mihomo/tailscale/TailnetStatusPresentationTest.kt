// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.mihomo.tailscale

import app.modes.RunModeTproxy
import app.modes.RunModeTun
import app.modes.RunModeVpnService
import engine.mihomo.tailscale.MihomoTailscaleOutbound
import engine.mihomo.tailscale.TailscaleBackendState
import engine.mihomo.tailscale.TailscaleStateDirIssue
import engine.mihomo.tailscale.TailscaleStatusQuery
import engine.mihomo.tailscale.TailscaleStatusResult
import engine.mihomo.tailscale.TailscaleStatusUnavailableReason
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetStatusPresentationTest {
    @Test
    fun hiddenWithoutTailscaleOutbounds() {
        val presentation = build(emptyList(), RunModeTproxy, TailscaleStatusQuery.Available(emptyMap()))
        assertFalse(presentation.visible)
        assertNull(presentation.rootRouting)
    }

    @Test
    fun showsTypedStateFromTheSourceAndStateDirIssues() {
        val source = TailscaleStatusQuery.Available(
            mapOf(
                "TS-A" to TailscaleBackendState.NeedsLogin(loginUrlAvailable = true),
                "TS-B" to TailscaleBackendState.Running(listOf("100.101.102.103"), "node-b"),
            ),
        )
        val presentation = build(
            listOf(outbound("TS-A", null), outbound("TS-B", "./tailscale-b")),
            RunModeVpnService,
            source,
        )

        assertTrue(presentation.visible)
        assertEquals(TailscaleStatusResult.Available(TailscaleBackendState.NeedsLogin(true)), presentation.outbounds[0].status)
        assertEquals(listOf(TailscaleStateDirIssue.ImplicitDefault), presentation.outbounds[0].stateDirIssues)
        assertTrue(presentation.outbounds[1].stateDirIssues.isEmpty())
        assertNull("VPN mode does not use ROOT private-address rules", presentation.rootRouting)
    }

    @Test
    fun rootTransparentModesExplainPrivateAddressOverrides() {
        val source = TailscaleStatusQuery.Available(mapOf("TS" to TailscaleBackendState.Starting))
        val none = build(listOf(outbound("TS", "./ts")), RunModeTproxy, source, privateAddressCidrs = emptyList())
        assertEquals(TailnetRootRoutingHint(0, emptyList()), none.rootRouting)

        val some = build(
            listOf(outbound("TS", "./ts")),
            RunModeTproxy,
            source,
            privateAddressCidrs = listOf("100.101.102.103/32", "100.64.0.0/10", "10.0.0.0/8"),
        )
        assertEquals(TailnetRootRoutingHint(1, listOf("100.64.0.0/10")), some.rootRouting)

        assertNull(build(listOf(outbound("TS", "./ts")), RunModeTun, source).rootRouting)
    }

    @Test
    fun outboundsMissingFromTheRunningCoreAreReported() {
        val presentation = build(
            listOf(outbound("TS", "./ts")),
            RunModeVpnService,
            TailscaleStatusQuery.Available(emptyMap()),
        )
        assertEquals(
            TailscaleStatusResult.Unavailable(TailscaleStatusUnavailableReason.NotInRunningConfig),
            presentation.outbounds.single().status,
        )
        assertEquals(TailnetOutboundAction.None, presentation.outbounds.single().action)
    }

    @Test
    fun actionsFollowTheState() {
        fun action(state: TailscaleBackendState) = tailnetOutboundAction(TailscaleStatusResult.Available(state))
        assertEquals(TailnetOutboundAction.LogIn, action(TailscaleBackendState.Idle))
        assertEquals(TailnetOutboundAction.LogIn, action(TailscaleBackendState.Stopped))
        assertEquals(TailnetOutboundAction.LogIn, action(TailscaleBackendState.NeedsLogin(false)))
        assertEquals(TailnetOutboundAction.LogIn, action(TailscaleBackendState.Error("x")))
        assertEquals(TailnetOutboundAction.LogOut, action(TailscaleBackendState.NeedsLogin(false, machineAuthorization = true)))
        assertEquals(TailnetOutboundAction.LogOut, action(TailscaleBackendState.Running(emptyList(), null)))
        assertEquals(TailnetOutboundAction.None, action(TailscaleBackendState.Starting))
        assertEquals(
            TailnetOutboundAction.None,
            tailnetOutboundAction(TailscaleStatusResult.Unavailable(TailscaleStatusUnavailableReason.CoreApiMissing)),
        )
    }

    @Test
    fun pollingIsFastOnlyWhileTransitional() {
        assertEquals(2.seconds, tailnetPollInterval(TailscaleStatusQuery.Available(mapOf("a" to TailscaleBackendState.Starting))))
        assertEquals(2.seconds, tailnetPollInterval(TailscaleStatusQuery.Available(mapOf("a" to TailscaleBackendState.NeedsLogin(true)))))
        assertEquals(10.seconds, tailnetPollInterval(TailscaleStatusQuery.Available(mapOf("a" to TailscaleBackendState.Idle))))
        assertEquals(10.seconds, tailnetPollInterval(TailscaleStatusQuery.Unavailable(TailscaleStatusUnavailableReason.ProxyNotRunning)))
    }

    @Test
    fun placeholderBeforeTheFirstPollDoesNotClaimTheProxyIsStopped() {
        assertEquals(
            TailscaleStatusQuery.Unavailable(TailscaleStatusUnavailableReason.Checking),
            tailnetStatusPlaceholder(proxyRunning = true),
        )
        assertEquals(
            TailscaleStatusQuery.Unavailable(TailscaleStatusUnavailableReason.ProxyNotRunning),
            tailnetStatusPlaceholder(proxyRunning = false),
        )
    }

    @Test
    fun loginHostsComeFromTheOutbound() {
        val presentation = build(
            listOf(outbound("TS", "./ts").copy(usesCustomControlUrl = true, controlUrlHost = "control.example.test")),
            RunModeVpnService,
            TailscaleStatusQuery.Available(mapOf("TS" to TailscaleBackendState.Idle)),
        )
        assertEquals(setOf("control.example.test"), presentation.outbounds.single().allowedLoginHosts)
    }

    private fun build(
        outbounds: List<MihomoTailscaleOutbound>,
        runMode: Int,
        source: TailscaleStatusQuery,
        privateAddressCidrs: List<String> = emptyList(),
    ) = buildTailnetStatusPresentation(
        outbounds = outbounds,
        runMode = runMode,
        privateAddressCidrs = privateAddressCidrs,
        mihomoHomeDirectory = "/data/user/0/example.app/files/clash",
        ownPackageName = "example.app",
        statuses = source,
    )

    private fun outbound(name: String, stateDir: String?) =
        MihomoTailscaleOutbound(name, hostname = null, stateDir = stateDir, hasAuthKey = false, ephemeral = false, usesCustomControlUrl = false)
}
