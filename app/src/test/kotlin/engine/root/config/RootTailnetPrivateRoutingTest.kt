// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.config

import app.ServiceControlSettings
import engine.network.TailnetIpv4Cidr
import engine.network.cidrContainsAddress
import engine.root.daemon.config.AsteriskdConfig
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdModeOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ROOT transparent routing for Tailnet peers. A YAML `IP-CIDR,100.64.0.0/10,...` rule alone is
 * not enough in ROOT modes, because asteriskd returns CGNAT destinations before they reach the
 * core. Only explicit **Private addresses** entries, compiled to proxy-private CIDRs that asteriskd
 * evaluates before the bypass list, route a peer through the core.
 */
class RootTailnetPrivateRoutingTest {
    @Test
    fun defaultPolicyBypassesTailnetRangeAndProxiesNothingPrivate() {
        val policy = rootPrivateAddressPolicy(emptyList())

        assertTrue(TailnetIpv4Cidr in RootDefaultBypassPrivateCidrs)
        assertTrue(TailnetIpv4Cidr in policy.bypassIpv4Cidrs)
        assertTrue(policy.proxyIpv4Cidrs.isEmpty())
        assertTrue(policy.proxyIpv6Cidrs.isEmpty())
    }

    @Test
    fun hostOverrideIsProxiedWithoutRemovingTheCgnatBypass() {
        val policy = rootPrivateAddressPolicy(listOf(" $PeerCidr ", PeerCidr, PeerIpv6Cidr))

        assertEquals(listOf(PeerCidr), policy.proxyIpv4Cidrs)
        assertEquals(listOf(PeerIpv6Cidr), policy.proxyIpv6Cidrs)
        assertTrue(TailnetIpv4Cidr in policy.bypassIpv4Cidrs)
    }

    @Test
    fun tproxyConfigCarriesHostOverrideForIpv4AndOptionallyIpv6() {
        val ipv4Only = buildConfig(AsteriskdMode.Tproxy, enableIpv6 = false)
        assertEquals(listOf(PeerCidr), ipv4Only.network.proxyPrivateCidrs)
        assertTrue(TailnetIpv4Cidr in ipv4Only.network.bypassPrivateCidrs)
        assertFalse(ipv4Only.network.bypassPrivateCidrs.any { ":" in it })

        val dualStack = buildConfig(AsteriskdMode.Tproxy, enableIpv6 = true)
        assertEquals(listOf(PeerCidr, PeerIpv6Cidr), dualStack.network.proxyPrivateCidrs)
    }

    @Test
    fun socksBasedRootModesAlsoCarryHostOverride() {
        for (mode in listOf(AsteriskdMode.Tun2Socks, AsteriskdMode.Bpf2Socks)) {
            assertEquals(mode.name, listOf(PeerCidr), buildConfig(mode, enableIpv6 = false).network.proxyPrivateCidrs)
        }
    }

    @Test
    fun rootTunModeDoesNotUsePrivateAddressRules() {
        // ROOT TUN routes through Mihomo's own TUN; Tailnet routing there is a P1 verification item.
        val tun = buildConfig(AsteriskdMode.Tun, enableIpv6 = true)
        assertTrue(tun.network.proxyPrivateCidrs.isEmpty())
        assertTrue(tun.network.bypassPrivateCidrs.isEmpty())
    }

    @Test
    fun asteriskdPrecedenceRoutesOnlyTheChosenPeer() {
        val network = buildConfig(AsteriskdMode.Tproxy, enableIpv6 = true).network

        assertEquals(Route.Proxy, network.routeFor(PeerAddress))
        assertEquals(Route.Bypass, network.routeFor(OtherTailnetAddress))
        assertEquals(Route.Bypass, network.routeFor(CarrierCgnatAddress))
        assertEquals(Route.Proxy, network.routeFor(PeerIpv6Address))
        assertEquals(Route.AppPolicy, network.routeFor(PublicAddress))
    }

    @Test
    fun coreWorkingDirectoryIsTheMihomoHomeUsedForRelativeStateDirs() {
        val config = buildConfig(AsteriskdMode.Tproxy, enableIpv6 = false)
        assertEquals(HomeDirectory, config.core.workingDirectory)
    }

    private enum class Route { Proxy, Bypass, AppPolicy }

    /** Mirrors asteriskd's ordering: proxy-private marks first, then bypass-private returns. */
    private fun engine.root.daemon.config.AsteriskdNetworkConfig.routeFor(address: String): Route = when {
        proxyPrivateCidrs.any { cidrContainsAddress(it, address) } -> Route.Proxy
        bypassPrivateCidrs.any { cidrContainsAddress(it, address) } -> Route.Bypass
        else -> Route.AppPolicy
    }

    private fun buildConfig(mode: AsteriskdMode, enableIpv6: Boolean): AsteriskdConfig {
        val policy = rootPrivateAddressPolicy(listOf(PeerCidr, PeerIpv6Cidr))
        val iptables = RootIptablesConfig(
            proxyPrivateIpv4Cidrs = policy.proxyIpv4Cidrs,
            proxyPrivateIpv6Cidrs = policy.proxyIpv6Cidrs,
            bypassPrivateIpv4Cidrs = policy.bypassIpv4Cidrs,
            bypassPrivateIpv6Cidrs = policy.bypassIpv6Cidrs,
        )
        return rootStartConfig(enableIpv6).buildAsteriskdConfig(
            mode = mode,
            iptablesConfig = iptables,
            virtualInterfaces = emptyList(),
            modeOptions = AsteriskdModeOptions(
                transparentPort = if (mode == AsteriskdMode.Tproxy) 65535 else null,
                tunnelName = null,
            ),
        )
    }

    private fun rootStartConfig(enableIpv6: Boolean) = RootStartConfig(
        mihomoProfileBytes = "mixed-port: 23456\n".toByteArray(),
        ageSecretKey = null,
        runtimePaths = RootConfigRuntimePaths(
            coreExecutablePath = "/data/app/example/lib/arm64/libmihomo.so",
            coreConfigPath = "$HomeDirectory/config.yaml",
            matcherExecutablePath = "/data/app/example/lib/arm64/libbpf-matcher.so",
            bpf2SocksExecutablePath = "/data/app/example/lib/arm64/libbpf2socks.so",
            hevSocks5TunnelExecutablePath = "/data/app/example/lib/arm64/libhev-socks5-tunnel-cli.so",
            workingDirectory = HomeDirectory,
            statePath = "$HomeDirectory/asteriskd.state",
            logPath = "$HomeDirectory/logs/asteriskd.log",
        ),
        directCidrIpv4Path = "$HomeDirectory/direct-v4.txt",
        directCidrIpv6Path = "$HomeDirectory/direct-v6.txt",
        enableIpv6 = enableIpv6,
        enableRootIpv6Disabler = false,
        enableLocalDns = false,
        enableFakeIp = false,
        fakeIpIpv4Pool = "198.18.0.0/16",
        enableBoot = false,
        serviceControl = ServiceControlSettings(),
    )

    private companion object {
        // Fixture values only; any Tailnet peer works the same way.
        const val PeerAddress = "100.101.102.103"
        const val PeerCidr = "$PeerAddress/32"
        const val PeerIpv6Address = "fd7a:115c:a1e0::abcd"
        const val PeerIpv6Cidr = "$PeerIpv6Address/128"
        const val OtherTailnetAddress = "100.101.102.104"
        const val CarrierCgnatAddress = "100.72.1.1"
        const val PublicAddress = "1.1.1.1"
        const val HomeDirectory = "/data/user/0/example.app/files/clash"
    }
}
