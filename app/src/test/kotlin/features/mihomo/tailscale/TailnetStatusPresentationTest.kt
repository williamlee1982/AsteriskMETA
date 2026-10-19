// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.mihomo.tailscale

import app.modes.RunModeTproxy
import app.modes.RunModeTun
import app.modes.RunModeVpnService
import engine.mihomo.tailscale.MihomoTailscaleOutbound
import engine.mihomo.tailscale.TailscaleBackendState
import engine.mihomo.tailscale.TailscaleStateDirIssue
import engine.mihomo.tailscale.TailscaleStatusResult
import engine.mihomo.tailscale.TailscaleStatusSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetStatusPresentationTest {
    /** Test double for a future structured core API. */
    private class FakeTailscaleStatusSource(
        private val states: Map<String, TailscaleBackendState>,
    ) : TailscaleStatusSource {
        val queries = mutableListOf<Pair<String, Boolean>>()

        override fun query(outboundName: String, proxyRunning: Boolean): TailscaleStatusResult {
            queries += outboundName to proxyRunning
            return TailscaleStatusResult.Available(states.getValue(outboundName))
        }
    }

    @Test
    fun hiddenWithoutTailscaleOutbounds() {
        val presentation = build(emptyList(), RunModeTproxy, FakeTailscaleStatusSource(emptyMap()))
        assertFalse(presentation.visible)
        assertNull(presentation.rootRouting)
    }

    @Test
    fun showsTypedStateFromTheSourceAndStateDirIssues() {
        val source = FakeTailscaleStatusSource(
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
        assertEquals(listOf("TS-A" to true, "TS-B" to true), source.queries)
        assertEquals(TailscaleStatusResult.Available(TailscaleBackendState.NeedsLogin(true)), presentation.outbounds[0].status)
        assertEquals(listOf(TailscaleStateDirIssue.ImplicitDefault), presentation.outbounds[0].stateDirIssues)
        assertTrue(presentation.outbounds[1].stateDirIssues.isEmpty())
        assertNull("VPN mode does not use ROOT private-address rules", presentation.rootRouting)
    }

    @Test
    fun rootTransparentModesExplainPrivateAddressOverrides() {
        val source = FakeTailscaleStatusSource(mapOf("TS" to TailscaleBackendState.Starting))
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

    private fun build(
        outbounds: List<MihomoTailscaleOutbound>,
        runMode: Int,
        source: TailscaleStatusSource,
        privateAddressCidrs: List<String> = emptyList(),
    ) = buildTailnetStatusPresentation(
        outbounds = outbounds,
        runMode = runMode,
        proxyRunning = true,
        privateAddressCidrs = privateAddressCidrs,
        mihomoHomeDirectory = "/data/user/0/example.app/files/clash",
        ownPackageName = "example.app",
        statusSource = source,
    )

    private fun outbound(name: String, stateDir: String?) =
        MihomoTailscaleOutbound(name, hostname = null, stateDir = stateDir, hasAuthKey = false, ephemeral = false, usesCustomControlUrl = false)
}
