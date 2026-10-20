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
        assertEquals(TailnetInboundPresentationState.WaitingForLogin, presentation.outbounds.single().inboundForwards.single().state)
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

    private fun present(
        outbound: engine.mihomo.tailscale.MihomoTailscaleOutbound,
        core: List<engine.mihomo.tailscale.TailscaleCoreOutbound>,
        enabled: Boolean = true,
        profile: List<engine.mihomo.tailscale.MihomoTailscaleOutbound> = listOf(outbound),
    ): TailnetStatusPresentation {
        val query = TailscaleStatusQuery.Available(core)
        return buildTailnetStatusPresentation(
            resolveTailnetCardOutbounds(query, profile), RunModeVpnService, emptyList(),
            "/data/user/0/example.app/files/clash", "example.app", query, enabled,
        )
    }

    private val ssh = TailnetInboundForward("ssh", "tcp", 20022, "127.0.0.1:22")
    private fun status(state: TailscaleInboundForwardState, error: String? = null, total: Long = 0) =
        TailscaleInboundForwardStatus("ssh", "tcp", 20022, "127.0.0.1:22", state, error = error, totalConns = total)

    // A5: the core's Waiting means "node not up yet"; only NeedsLogin is "waiting for login".
    @Test
    fun coreWaitingWhileStartingIsWaitingForNode() {
        val outbound = tailnetOutbound("TS").copy(inboundForwards = listOf(ssh))
        val p = present(outbound, listOf(engine.mihomo.tailscale.TailscaleCoreOutbound(
            outbound.key, TailscaleBackendState.Starting, outbound,
            inboundForwards = listOf(status(TailscaleInboundForwardState.Waiting)))))
        assertEquals(TailnetInboundPresentationState.WaitingForNode, p.outbounds.single().inboundForwards.single().state)
    }

    // L2: a failed target dial is shown while the listener keeps running.
    @Test
    fun listeningWithDialErrorShowsWarning() {
        val outbound = tailnetOutbound("TS").copy(inboundForwards = listOf(ssh))
        val p = present(outbound, listOf(engine.mihomo.tailscale.TailscaleCoreOutbound(
            outbound.key, TailscaleBackendState.Running(listOf("100.101.102.103"), "node"), outbound,
            inboundForwards = listOf(status(TailscaleInboundForwardState.Listening, error = "connection refused", total = 5_000_000_000L)))))
        val row = p.outbounds.single().inboundForwards.single()
        assertEquals(TailnetInboundPresentationState.Listening, row.state)
        assertEquals("connection refused", row.warning)
        assertEquals(5_000_000_000L, row.totalConns)
    }

    // M1: provider members have no profile declaration; the core's error rows must still show.
    @Test
    fun providerMemberShowsCoreReportedUnsupportedRow() {
        val member = tailnetOutbound("TS").copy(provider = "sub")
        val p = present(member, listOf(engine.mihomo.tailscale.TailscaleCoreOutbound(
            member.key, TailscaleBackendState.Idle, member,
            inboundForwards = listOf(status(TailscaleInboundForwardState.Error, error = "unsupported in proxy providers")))),
            profile = emptyList())
        val row = p.outbounds.single().inboundForwards.single()
        assertEquals(TailnetInboundPresentationState.Error, row.state)
        assertEquals("unsupported in proxy providers", row.error)
    }

    // M1: the unsupported-core notice does not depend on the node being Running.
    @Test
    fun unsupportedCoreNoticeShownWhileNeedsLogin() {
        val outbound = tailnetOutbound("TS").copy(inboundForwards = listOf(ssh))
        val p = present(outbound, listOf(engine.mihomo.tailscale.TailscaleCoreOutbound(
            outbound.key, TailscaleBackendState.NeedsLogin(loginUrlAvailable = true), outbound)))
        assertEquals(TailnetInboundNotice.UnsupportedCore, p.outbounds.single().inboundNotice)
    }

    @Test
    fun noUnsupportedNoticeWhenToggleOff() {
        val outbound = tailnetOutbound("TS").copy(inboundForwards = listOf(ssh))
        val p = present(outbound, listOf(engine.mihomo.tailscale.TailscaleCoreOutbound(
            outbound.key, TailscaleBackendState.Idle, outbound)), enabled = false)
        assertEquals(null, p.outbounds.single().inboundNotice)
    }
}
