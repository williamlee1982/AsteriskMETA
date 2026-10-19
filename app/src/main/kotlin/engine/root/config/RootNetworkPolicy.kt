// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.config

import android.content.Context
import app.AppState
import app.withCompatibleProxyAppListMode
import app.modes.ProxyAppListModeGlobal
import utils.toTrimmedNonEmptyDistinctList

internal val RootDefaultBypassPrivateCidrs = listOf(
    "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
    "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16",
    "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
    "255.255.255.255/32", "::/128", "::1/128", "::ffff:0:0/96", "100::/64",
    "64:ff9b::/96", "2001::/32", "2001:10::/28", "2001:20::/28", "2001:db8::/32",
    "2002::/16", "fc00::/7", "fe80::/10", "ff00::/8",
)

internal data class RootIptablesConfig(
    val enableEbpfRules: Boolean = false,
    val enableEbpfDirectCidrBypass: Boolean = false,
    val externalInterfacePrefixes: List<String> = emptyList(),
    val ignoredInterfaces: List<String> = emptyList(),
    val proxyPrivateIpv4Cidrs: List<String> = emptyList(),
    val proxyPrivateIpv6Cidrs: List<String> = emptyList(),
    val bypassPrivateIpv4Cidrs: List<String> = emptyList(),
    val bypassPrivateIpv6Cidrs: List<String> = emptyList(),
    val forcedBypassUids: List<Int> = emptyList(),
    val proxyAppListMode: Int = ProxyAppListModeGlobal,
    val proxyApplicationUids: List<Int> = emptyList(),
)

internal fun RootIptablesConfig.withAppSettings(
    context: Context,
    appState: AppState,
): RootIptablesConfig {
    val privateAddressPolicy = rootPrivateAddressPolicy(appState.privateAddressCidrs)
    val selectedAppKeys = appState.proxyAppListSelectedApps.toTrimmedNonEmptyDistinctList()
    val appListMode = appState.withCompatibleProxyAppListMode().proxyAppListMode.toRootProxyAppListMode()

    return copy(
        externalInterfacePrefixes = appState.externalInterfaces.toTrimmedNonEmptyDistinctList(),
        ignoredInterfaces = appState.ignoredInterfaces.toTrimmedNonEmptyDistinctList(),
        proxyPrivateIpv4Cidrs = privateAddressPolicy.proxyIpv4Cidrs,
        proxyPrivateIpv6Cidrs = privateAddressPolicy.proxyIpv6Cidrs,
        bypassPrivateIpv4Cidrs = privateAddressPolicy.bypassIpv4Cidrs,
        bypassPrivateIpv6Cidrs = privateAddressPolicy.bypassIpv6Cidrs,
        enableEbpfRules = appState.enableRootEbpfRules,
        enableEbpfDirectCidrBypass = appState.enableRootEbpfDirectCidrBypass,
        proxyAppListMode = appListMode,
        proxyApplicationUids = if (appListMode == ProxyAppListModeGlobal) {
            emptyList()
        } else {
            context.resolveRootProxyApplicationUids(selectedAppKeys)
        },
    )
}

/**
 * ROOT private-address policy. Reserved and private ranges, including the CGNAT range
 * `100.64.0.0/10` used by Tailnet addresses, are bypassed by default. The user's
 * **Private addresses** setting is compiled into proxy-private CIDRs, which asteriskd evaluates
 * before the bypass list, so a single Tailnet host such as `100.x.y.z/32` can be routed through
 * the core without proxying all of CGNAT.
 */
internal data class RootPrivateAddressPolicy(
    val proxyIpv4Cidrs: List<String>,
    val proxyIpv6Cidrs: List<String>,
    val bypassIpv4Cidrs: List<String>,
    val bypassIpv6Cidrs: List<String>,
)

internal fun rootPrivateAddressPolicy(privateAddressCidrs: List<String>): RootPrivateAddressPolicy {
    val proxyPrivateCidrs = privateAddressCidrs.toTrimmedNonEmptyDistinctList()
    val bypassPrivateCidrs = RootDefaultBypassPrivateCidrs.toTrimmedNonEmptyDistinctList()
    return RootPrivateAddressPolicy(
        proxyIpv4Cidrs = proxyPrivateCidrs.ipv4Cidrs(),
        proxyIpv6Cidrs = proxyPrivateCidrs.ipv6Cidrs(),
        bypassIpv4Cidrs = bypassPrivateCidrs.ipv4Cidrs(),
        bypassIpv6Cidrs = bypassPrivateCidrs.ipv6Cidrs(),
    )
}

private fun List<String>.ipv4Cidrs(): List<String> {
    return filterNot { cidr -> ":" in cidr }
}

private fun List<String>.ipv6Cidrs(): List<String> {
    return filter { cidr -> ":" in cidr }
}
