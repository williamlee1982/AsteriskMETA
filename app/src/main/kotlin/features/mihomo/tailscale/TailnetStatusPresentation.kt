// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.mihomo.tailscale

import app.modes.RunModeBpf2Socks
import app.modes.RunModeTproxy
import app.modes.RunModeTun2Socks
import engine.mihomo.tailscale.MihomoTailscaleOutbound
import engine.mihomo.tailscale.TailscaleBackendState
import engine.mihomo.tailscale.TailscaleStateDirIssue
import engine.mihomo.tailscale.TailscaleStatusQuery
import engine.mihomo.tailscale.TailscaleStatusResult
import engine.mihomo.tailscale.allowedLoginHosts
import engine.mihomo.tailscale.diagnoseTailscaleStateDirs
import engine.mihomo.tailscale.resultFor
import engine.network.tailnetPrivateAddressOverrides

internal data class TailnetOutboundPresentation(
    val name: String,
    val hostname: String?,
    val status: TailscaleStatusResult,
    val stateDirIssues: List<TailscaleStateDirIssue>,
    val allowedLoginHosts: Set<String> = emptySet(),
) {
    val action: TailnetOutboundAction get() = tailnetOutboundAction(status)
}

/** The single user action offered for an outbound in its current state. */
internal enum class TailnetOutboundAction { None, LogIn, LogOut }

internal fun tailnetOutboundAction(status: TailscaleStatusResult): TailnetOutboundAction {
    if (status !is TailscaleStatusResult.Available) return TailnetOutboundAction.None
    return when (val state = status.state) {
        TailscaleBackendState.Idle,
        TailscaleBackendState.Stopped,
        is TailscaleBackendState.Error,
        -> TailnetOutboundAction.LogIn
        is TailscaleBackendState.NeedsLogin ->
            if (state.machineAuthorization) TailnetOutboundAction.LogOut else TailnetOutboundAction.LogIn
        is TailscaleBackendState.Running -> TailnetOutboundAction.LogOut
        TailscaleBackendState.Starting -> TailnetOutboundAction.None
    }
}

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
    privateAddressCidrs: List<String>,
    mihomoHomeDirectory: String,
    ownPackageName: String,
    statuses: TailscaleStatusQuery,
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
                status = statuses.resultFor(outbound.name),
                stateDirIssues = findings[outbound.name]?.issues.orEmpty().sortedBy { it.ordinal },
                allowedLoginHosts = outbound.allowedLoginHosts(),
            )
        },
        rootRouting = rootRouting,
    )
}

private val PrivateAddressRootModes = setOf(RunModeTproxy, RunModeTun2Socks, RunModeBpf2Socks)
