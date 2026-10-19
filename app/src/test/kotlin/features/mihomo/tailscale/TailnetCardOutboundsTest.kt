// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.mihomo.tailscale

import engine.mihomo.tailscale.TailnetOutboundKey
import engine.mihomo.tailscale.TailscaleBackendState
import engine.mihomo.tailscale.TailscaleCoreOutbound
import engine.mihomo.tailscale.TailscaleStatusQuery
import engine.mihomo.tailscale.TailscaleStatusUnavailableReason
import engine.mihomo.tailscale.coreStates
import engine.mihomo.tailscale.tailnetOutbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetCardOutboundsTest {
    private val idle = TailscaleBackendState.Idle

    private fun core(vararg outbounds: TailscaleCoreOutbound) = TailscaleStatusQuery.Available(outbounds.toList())

    private fun reported(name: String, provider: String? = null, stateDir: String = "/home/clash/ts-$name") =
        TailscaleCoreOutbound(
            TailnetOutboundKey(name, provider),
            idle,
            tailnetOutbound(name, stateDir = stateDir, provider = provider),
        )

    @Test
    fun runningCoreListsProviderAndScriptOutboundsTheProfileFileLacks() {
        val card = resolveTailnetCardOutbounds(
            core(reported("from-provider", provider = "subscription"), reported("from-script")),
            profileOutbounds = emptyList(),
        )
        assertNull(card.profileFallback)
        assertEquals(
            listOf(TailnetOutboundKey("from-provider", "subscription"), TailnetOutboundKey("from-script")),
            card.outbounds.map { it.outbound.key },
        )
        assertTrue(card.outbounds.all { it.source == TailnetOutboundSource.RunningCore && it.configKnown })
    }

    @Test
    fun coreConfigWinsOverTheProfileFile() {
        val card = resolveTailnetCardOutbounds(
            core(reported("ts", stateDir = "/home/clash/effective")),
            profileOutbounds = listOf(tailnetOutbound("ts", stateDir = "./from-file")),
        )
        assertEquals("/home/clash/effective", card.outbounds.single().outbound.stateDir)
        assertEquals(TailnetOutboundSource.RunningCore, card.outbounds.single().source)
    }

    @Test
    fun coresWithoutConfigBorrowItFromTheTopLevelProfileEntryOnly() {
        val card = resolveTailnetCardOutbounds(
            coreStates("ts" to idle).let { query ->
                TailscaleStatusQuery.Available(
                    query.outbounds + TailscaleCoreOutbound(TailnetOutboundKey("ts", "subscription"), idle, config = null),
                )
            },
            profileOutbounds = listOf(tailnetOutbound("ts", stateDir = "./ts")),
        )
        val (topLevel, member) = card.outbounds
        assertEquals("./ts", topLevel.outbound.stateDir)
        assertTrue(topLevel.configKnown)
        assertEquals(TailnetOutboundKey("ts", "subscription"), member.outbound.key)
        assertNull(member.outbound.stateDir)
        assertFalse("a provider member must not borrow a top-level profile entry", member.configKnown)
    }

    @Test
    fun profileOutboundsTheCoreDoesNotRunAreKeptAsNotRunning() {
        val card = resolveTailnetCardOutbounds(
            core(reported("ts", provider = "subscription")),
            profileOutbounds = listOf(tailnetOutbound("ts"), tailnetOutbound("edited")),
        )
        assertEquals(
            listOf(
                TailnetOutboundKey("ts", "subscription") to TailnetOutboundSource.RunningCore,
                TailnetOutboundKey("ts") to TailnetOutboundSource.ProfileFile,
                TailnetOutboundKey("edited") to TailnetOutboundSource.ProfileFile,
            ),
            card.outbounds.map { it.outbound.key to it.source },
        )
    }

    @Test
    fun withoutCoreStateTheProfileFileIsAMarkedFallback() {
        val profile = listOf(tailnetOutbound("ts"), tailnetOutbound("ts", stateDir = "./dup"), tailnetOutbound("other"))
        for (reason in TailscaleStatusUnavailableReason.entries) {
            val card = resolveTailnetCardOutbounds(TailscaleStatusQuery.Unavailable(reason), profile)
            assertEquals(reason, card.profileFallback)
            assertEquals("duplicate names are shown once", listOf("ts", "other"), card.outbounds.map { it.outbound.name })
            assertNull(card.outbounds.first().outbound.stateDir)
            assertTrue(card.outbounds.all { it.source == TailnetOutboundSource.ProfileFile })
        }
    }

    @Test
    fun providerRefreshesAddAndRemoveOutbounds() {
        val profile = emptyList<engine.mihomo.tailscale.MihomoTailscaleOutbound>()
        val before = resolveTailnetCardOutbounds(core(reported("a", "p")), profile)
        val after = resolveTailnetCardOutbounds(core(reported("b", "p")), profile)
        val gone = resolveTailnetCardOutbounds(core(), profile)
        assertEquals(listOf("a"), before.outbounds.map { it.outbound.name })
        assertEquals(listOf("b"), after.outbounds.map { it.outbound.name })
        assertTrue(gone.outbounds.isEmpty())
    }

    @Test
    fun aFailedPollKeepsTheLastCoreListButOtherReasonsFallBack() {
        val last = listOf(reported("ts", "p"))
        val failed = resolveTailnetCardOutbounds(
            TailscaleStatusQuery.Unavailable(TailscaleStatusUnavailableReason.QueryFailed),
            profileOutbounds = listOf(tailnetOutbound("top")),
            lastReported = last,
        )
        assertNull(failed.profileFallback)
        assertEquals(
            listOf(TailnetOutboundKey("ts", "p"), TailnetOutboundKey("top")),
            failed.outbounds.map { it.outbound.key },
        )
        val stopped = resolveTailnetCardOutbounds(
            TailscaleStatusQuery.Unavailable(TailscaleStatusUnavailableReason.ProxyNotRunning),
            profileOutbounds = listOf(tailnetOutbound("top")),
            lastReported = last,
        )
        assertEquals(TailscaleStatusUnavailableReason.ProxyNotRunning, stopped.profileFallback)
        assertEquals(listOf("top"), stopped.outbounds.map { it.outbound.name })
    }
}
