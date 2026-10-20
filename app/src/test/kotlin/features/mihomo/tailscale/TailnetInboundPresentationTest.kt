package features.mihomo.tailscale

import app.modes.RunModeVpnService
import engine.mihomo.tailscale.TailnetInboundForward
import engine.mihomo.tailscale.TailscaleBackendState
import engine.mihomo.tailscale.TailscaleStatusQuery
import engine.mihomo.tailscale.TailscaleInboundForwardState
import engine.mihomo.tailscale.TailscaleInboundForwardStatus
import engine.mihomo.tailscale.TailscaleStatusResult
import engine.mihomo.tailscale.tailnetOutbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetInboundPresentationTest {
    @Test
    fun needsLoginForwardShowsWaiting() {
        val outbound = tailnetOutbound("TS").copy(
            inboundForwards = listOf(TailnetInboundForward("ssh", "tcp", 20022, "127.0.0.1:22")),
        )
        val query = TailscaleStatusQuery.Available(
            listOf(
                engine.mihomo.tailscale.TailscaleCoreOutbound(
                    outbound.key,
                    TailscaleBackendState.NeedsLogin(loginUrlAvailable = true),
                    outbound,
                ),
            ),
        )
        val presentation = buildTailnetStatusPresentation(
            resolveTailnetCardOutbounds(query, listOf(outbound)),
            RunModeVpnService,
            emptyList(),
            "/data/user/0/example.app/files/clash",
            "example.app",
            query,
            inboundForwardsEnabled = true,
        )
        assertEquals(TailnetInboundPresentationState.Waiting, presentation.outbounds.single().inboundForwards.single().state)
    }

    @Test
    fun forwardRowShowsReachableAddressAndCounters() {
        val outbound = tailnetOutbound("TS").copy(
            inboundForwards = listOf(TailnetInboundForward("ssh", "tcp", 20022, "127.0.0.1:22")),
        )
        val query = TailscaleStatusQuery.Available(
            listOf(
                engine.mihomo.tailscale.TailscaleCoreOutbound(
                    outbound.key,
                    TailscaleBackendState.Running(
                        listOf("100.101.102.103"), "node", "phone.example.test.",
                        inboundForwards = listOf(TailscaleInboundForwardStatus("ssh", "tcp", 20022, "127.0.0.1:22", TailscaleInboundForwardState.Listening, activeConns = 1, totalConns = 2)),
                    ),
                    outbound,
                ),
            ),
        )
        val presentation = buildTailnetStatusPresentation(
            resolveTailnetCardOutbounds(query, listOf(outbound)),
            RunModeVpnService,
            emptyList(),
            "/data/user/0/example.app/files/clash",
            "example.app",
            query,
            inboundForwardsEnabled = true,
        )
        val row = presentation.outbounds.single().inboundForwards.single()
        assertEquals("phone.example.test:20022", row.address)
        assertEquals(1, row.activeConns)
        assertTrue(presentation.showAclHint)
    }

    @Test
    fun unsupportedCoreNoticeIsShownWhenToggleIsOn() {
        val outbound = tailnetOutbound("TS").copy(inboundForwards = listOf(TailnetInboundForward("ssh", "tcp", 20022, "127.0.0.1:22")))
        val query = TailscaleStatusQuery.Available(
            listOf(engine.mihomo.tailscale.TailscaleCoreOutbound(outbound.key, TailscaleBackendState.Running(listOf("100.101.102.103"), "node"), outbound)),
        )
        val presentation = buildTailnetStatusPresentation(
            resolveTailnetCardOutbounds(query, listOf(outbound)), RunModeVpnService, emptyList(), "/data/user/0/example.app/files/clash", "example.app", query, true,
        )
        assertEquals(TailnetInboundNotice.UnsupportedCore, presentation.outbounds.single().inboundNotice)
    }

    @Test
    fun profileOnlyForwardIsNotRunning() {
        val outbound = tailnetOutbound("TS").copy(inboundForwards = listOf(TailnetInboundForward("ssh", "tcp", 20022, "127.0.0.1:22")))
        val query = TailscaleStatusQuery.Available(emptyList())
        val presentation = buildTailnetStatusPresentation(
            resolveTailnetCardOutbounds(query, listOf(outbound)), RunModeVpnService, emptyList(), "/data/user/0/example.app/files/clash", "example.app", query, true,
        )
        assertEquals(TailnetInboundPresentationState.NotRunning, presentation.outbounds.single().inboundForwards.single().state)
    }
}
