// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.mihomo.tailscale

import app.modes.RunModeBpf2Socks
import app.modes.RunModeTproxy
import app.modes.RunModeTun2Socks
import engine.mihomo.tailscale.MihomoTailscaleOutbound
import engine.mihomo.tailscale.TailscaleStateDirIssue
import engine.mihomo.tailscale.TailscaleStatusResult
import engine.mihomo.tailscale.TailscaleStatusSource
import engine.mihomo.tailscale.diagnoseTailscaleStateDirs
import engine.network.tailnetPrivateAddressOverrides

internal data class TailnetOutboundPresentation(
    val name: String,
    val hostname: String?,
    val status: TailscaleStatusResult,
    val stateDirIssues: List<TailscaleStateDirIssue>,
)

/**
 * ROOT transparent-routing hint. Present only for ROOT modes whose asteriskd rules consume the
 * **Private addresses** setting (TPROXY, TUN2SOCKS, BPF2SOCKS).
 */
internal data class TailnetRootRoutingHint(
    val hostOverrideCount: Int,
    val broadOverrides: List<String>,
)

internal data class TailnetStatusPresentation(
    val outbounds: List<TailnetOutboundPresentation>,
    val rootRouting: TailnetRootRoutingHint?,
) {
    val visible: Boolean get() = outbounds.isNotEmpty()
}

internal fun buildTailnetStatusPresentation(
    outbounds: List<MihomoTailscaleOutbound>,
    runMode: Int,
    proxyRunning: Boolean,
    privateAddressCidrs: List<String>,
    mihomoHomeDirectory: String,
    ownPackageName: String,
    statusSource: TailscaleStatusSource,
): TailnetStatusPresentation {
    if (outbounds.isEmpty()) return TailnetStatusPresentation(emptyList(), rootRouting = null)
    val findings = diagnoseTailscaleStateDirs(outbounds, mihomoHomeDirectory, ownPackageName)
        .associateBy { it.outboundName }
    val rootRouting = if (runMode in PrivateAddressRootModes) {
        val overrides = tailnetPrivateAddressOverrides(privateAddressCidrs)
        TailnetRootRoutingHint(overrides.hostOverrides.size, overrides.broadOverrides)
    } else {
        null
    }
    return TailnetStatusPresentation(
        outbounds = outbounds.map { outbound ->
            TailnetOutboundPresentation(
                name = outbound.name,
                hostname = outbound.hostname,
                status = statusSource.query(outbound.name, proxyRunning),
                stateDirIssues = findings[outbound.name]?.issues.orEmpty().sortedBy { it.ordinal },
            )
        },
        rootRouting = rootRouting,
    )
}

private val PrivateAddressRootModes = setOf(RunModeTproxy, RunModeTun2Socks, RunModeBpf2Socks)
