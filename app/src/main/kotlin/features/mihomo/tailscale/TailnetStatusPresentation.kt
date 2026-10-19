// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.mihomo.tailscale

import app.modes.RunModeBpf2Socks
import app.modes.RunModeTproxy
import app.modes.RunModeTun2Socks
import engine.mihomo.tailscale.TailnetOutboundKey
import engine.mihomo.tailscale.TailscaleBackendState
import engine.mihomo.tailscale.TailscaleStateDirIssue
import engine.mihomo.tailscale.TailscaleStatusQuery
import engine.mihomo.tailscale.TailscaleStatusResult
import engine.mihomo.tailscale.TailscaleStatusUnavailableReason
import engine.mihomo.tailscale.allowedLoginHosts
import engine.mihomo.tailscale.diagnoseTailscaleStateDirs
import engine.mihomo.tailscale.resultFor
import engine.network.tailnetPrivateAddressOverrides

internal data class TailnetOutboundPresentation(
    val key: TailnetOutboundKey,
    val hostname: String?,
    val status: TailscaleStatusResult,
    val stateDirIssues: List<TailscaleStateDirIssue>,
    val allowedLoginHosts: Set<String> = emptySet(),
    val source: TailnetOutboundSource = TailnetOutboundSource.RunningCore,
) {
    val name: String get() = key.name
    val provider: String? get() = key.provider
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
    /** Set when the list comes from the profile file because the core cannot report it. */
    val profileFallback: TailscaleStatusUnavailableReason? = null,
) {
    val visible: Boolean get() = outbounds.isNotEmpty()
}

internal fun buildTailnetStatusPresentation(
    card: TailnetCardOutbounds,
    runMode: Int,
    privateAddressCidrs: List<String>,
    mihomoHomeDirectory: String,
    ownPackageName: String,
    statuses: TailscaleStatusQuery,
): TailnetStatusPresentation {
    if (card.outbounds.isEmpty()) return TailnetStatusPresentation(emptyList(), rootRouting = null)
    // Outbounds whose options are unknown get no state-dir findings rather than guessed ones.
    // Running outbounds and profile entries the core does not run are checked separately, so a
    // stale profile entry never reports a running outbound's directory as shared.
    val findings = card.outbounds.filter { it.configKnown }.groupBy { it.source }.values.flatMap { group ->
        group.zip(diagnoseTailscaleStateDirs(group.map { it.outbound }, mihomoHomeDirectory, ownPackageName))
    }.associate { (entry, finding) -> entry.outbound.key to finding }
    val rootRouting = if (runMode in PrivateAddressRootModes) {
        val overrides = tailnetPrivateAddressOverrides(privateAddressCidrs)
        TailnetRootRoutingHint(overrides.hostOverrides.size, overrides.broadOverrides)
    } else {
        null
    }
    return TailnetStatusPresentation(
        outbounds = card.outbounds.map { entry ->
            val outbound = entry.outbound
            TailnetOutboundPresentation(
                key = outbound.key,
                hostname = outbound.hostname,
                status = statuses.resultFor(outbound.key),
                stateDirIssues = findings[outbound.key]?.issues.orEmpty().sortedBy { it.ordinal },
                allowedLoginHosts = outbound.allowedLoginHosts(),
                source = entry.source,
            )
        },
        rootRouting = rootRouting,
        profileFallback = card.profileFallback,
    )
}

private val PrivateAddressRootModes = setOf(RunModeTproxy, RunModeTun2Socks, RunModeBpf2Socks)
