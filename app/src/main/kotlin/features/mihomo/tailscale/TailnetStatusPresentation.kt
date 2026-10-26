// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.mihomo.tailscale

import app.modes.usesPrivateAddressRouting
import engine.mihomo.tailscale.TailnetOutboundKey
import engine.mihomo.tailscale.TailnetInboundForward
import engine.mihomo.tailscale.TailscaleInboundForwardState
import engine.mihomo.tailscale.TailscaleInboundForwardStatus
import engine.mihomo.tailscale.TailscaleBackendState
import engine.mihomo.tailscale.TailscaleStateDirIssue
import engine.mihomo.tailscale.TailscaleStatusQuery
import engine.mihomo.tailscale.TailscaleStatusResult
import engine.mihomo.tailscale.TailscaleStatusUnavailableReason
import engine.mihomo.tailscale.allowedLoginHosts
import engine.mihomo.tailscale.diagnoseTailscaleStateDirs
import engine.mihomo.tailscale.inboundForwardsFor
import engine.mihomo.tailscale.resultFor
import engine.network.tailnetPrivateAddressOverrides

internal data class TailnetOutboundPresentation(
    val key: TailnetOutboundKey,
    val hostname: String?,
    val status: TailscaleStatusResult,
    val stateDirIssues: List<TailscaleStateDirIssue>,
    val allowedLoginHosts: Set<String> = emptySet(),
    val source: TailnetOutboundSource = TailnetOutboundSource.RunningCore,
    val inboundForwards: List<TailnetInboundForwardPresentation> = emptyList(),
    val inboundNotice: TailnetInboundNotice? = null,
) {
    val name: String get() = key.name
    val provider: String? get() = key.provider
    val action: TailnetOutboundAction get() = tailnetOutboundAction(status)
}

internal enum class TailnetInboundPresentationState { Listening, WaitingForNode, WaitingForLogin, Error, NotRunning, Invalid }

internal enum class TailnetInboundNotice { UnsupportedCore }

internal data class TailnetInboundTogglePresentation(
    val enabled: Boolean,
    val available: Boolean,
)

/**
 * The switch is checked only when it has an effect. It is available when a top-level outbound
 * declares forwards and the core supports them; an enabled switch stays operable so it can
 * always be turned off.
 */
internal fun tailnetInboundTogglePresentation(
    enabled: Boolean,
    hasForwards: Boolean,
    coreUnsupported: Boolean = false,
): TailnetInboundTogglePresentation = TailnetInboundTogglePresentation(
    enabled = enabled && hasForwards,
    available = hasForwards && (enabled || !coreUnsupported),
)

/** The toggle controls only top-level effective outbounds; provider members are not reconciled. */
internal fun tailnetInboundForwardsAvailable(card: TailnetCardOutbounds): Boolean =
    card.outbounds.any { it.outbound.provider == null && it.outbound.inboundForwards.isNotEmpty() }

internal data class TailnetInboundForwardPresentation(
    val name: String,
    val network: String,
    val listen: Int,
    val target: String,
    val state: TailnetInboundPresentationState,
    val error: String? = null,
    val activeConns: Int = 0,
    val totalConns: Long = 0,
    val address: String? = null,
    /** Last target dial failure of a forward that is still listening. */
    val warning: String? = null,
)

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
    val showAclHint: Boolean = false,
    /** A Running node omitted peer addresses, so private-address sync has nothing to merge. */
    val peerListUnavailable: Boolean = false,
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
    inboundForwardsEnabled: Boolean = false,
): TailnetStatusPresentation {
    if (card.outbounds.isEmpty()) return TailnetStatusPresentation(emptyList(), rootRouting = null, peerListUnavailable = false)
    // Outbounds whose options are unknown get no state-dir findings rather than guessed ones.
    // Running outbounds and profile entries the core does not run are checked separately, so a
    // stale profile entry never reports a running outbound's directory as shared.
    val findings = card.outbounds.filter { it.configKnown }.groupBy { it.source }.values.flatMap { group ->
        group.zip(diagnoseTailscaleStateDirs(group.map { it.outbound }, mihomoHomeDirectory, ownPackageName))
    }.associate { (entry, finding) -> entry.outbound.key to finding }
    val rootRouting = if (runMode.usesPrivateAddressRouting()) {
        val overrides = tailnetPrivateAddressOverrides(privateAddressCidrs)
        TailnetRootRoutingHint(overrides.hostOverrides.size, overrides.broadOverrides)
    } else {
        null
    }
    val running = (statuses as? TailscaleStatusQuery.Available)
        ?.outbounds
        .orEmpty()
        .mapNotNull { outbound -> outbound.state as? TailscaleBackendState.Running }
    val anyForward = card.outbounds.any { it.outbound.inboundForwards.isNotEmpty() }
    return TailnetStatusPresentation(
        outbounds = card.outbounds.map { entry ->
            val outbound = entry.outbound
            val result = statuses.resultFor(outbound.key)
            val reported = statuses.inboundForwardsFor(outbound.key)
            TailnetOutboundPresentation(
                key = outbound.key,
                hostname = outbound.hostname,
                status = result,
                stateDirIssues = findings[outbound.key]?.issues.orEmpty().sortedBy { it.ordinal },
                allowedLoginHosts = outbound.allowedLoginHosts(),
                source = entry.source,
                inboundForwards = inboundForwardRows(outbound.inboundForwards, reported, result),
                inboundNotice = if (
                    inboundForwardsEnabled && outbound.provider == null && outbound.inboundForwards.isNotEmpty() &&
                    result is TailscaleStatusResult.Available && reported == null
                ) {
                    // A core with the feature reports every registered forwarding outbound,
                    // whatever its backend state; an omitted field means an older core.
                    TailnetInboundNotice.UnsupportedCore
                } else {
                    null
                },
            )
        },
        rootRouting = rootRouting,
        profileFallback = card.profileFallback,
        showAclHint = anyForward,
        peerListUnavailable = running.isNotEmpty() && running.all { state -> state.peerIps == null },
    )
}

/**
 * Declared forwards first, then forwards only the core reports (for example proxy-provider
 * members, whose declarations the profile file cannot show and which the core reports as errors).
 */
private fun inboundForwardRows(
    declared: List<TailnetInboundForward>,
    reported: List<TailscaleInboundForwardStatus>?,
    result: TailscaleStatusResult,
): List<TailnetInboundForwardPresentation> {
    val rows = declared.map { it.toPresentation(result, reported) }
    val declaredPorts = declared.map { it.listen }.toSet()
    val coreOnly = reported.orEmpty().filter { it.listen !in declaredPorts }.map { status ->
        TailnetInboundForward(status.name, status.network, status.listen, status.target).toPresentation(result, reported)
    }
    return rows + coreOnly
}

private fun TailnetInboundForward.toPresentation(
    outboundState: TailscaleStatusResult,
    reported: List<TailscaleInboundForwardStatus>?,
): TailnetInboundForwardPresentation {
    if (error != null) return TailnetInboundForwardPresentation(name, network, listen, target, TailnetInboundPresentationState.Invalid, error)
    val backend = (outboundState as? TailscaleStatusResult.Available)?.state
    val running = backend as? TailscaleBackendState.Running
    val status = reported?.firstOrNull { it.listen == listen }
    val state = when {
        backend == null -> TailnetInboundPresentationState.NotRunning
        status == null -> when (backend) {
            is TailscaleBackendState.NeedsLogin -> TailnetInboundPresentationState.WaitingForLogin
            TailscaleBackendState.Starting -> TailnetInboundPresentationState.WaitingForNode
            else -> TailnetInboundPresentationState.NotRunning
        }
        status.state == TailscaleInboundForwardState.Listening -> TailnetInboundPresentationState.Listening
        // The core's Waiting means "node not Running yet"; only NeedsLogin needs the user.
        status.state == TailscaleInboundForwardState.Waiting -> if (backend is TailscaleBackendState.NeedsLogin) {
            TailnetInboundPresentationState.WaitingForLogin
        } else {
            TailnetInboundPresentationState.WaitingForNode
        }
        else -> TailnetInboundPresentationState.Error
    }
    val statusError = status?.error?.takeIf(String::isNotBlank)
    return TailnetInboundForwardPresentation(
        name = name,
        network = network,
        listen = listen,
        target = target,
        state = state,
        error = statusError.takeIf { state == TailnetInboundPresentationState.Error },
        // A Listening forward keeps its last target dial error as a warning.
        warning = statusError.takeIf { state == TailnetInboundPresentationState.Listening },
        activeConns = status?.activeConns ?: 0,
        totalConns = status?.totalConns ?: 0,
        address = running?.reachableAddress(listen),
    )
}

private fun TailscaleBackendState.Running.reachableAddress(listen: Int): String? {
    val host = dnsName?.trim()?.removeSuffix(".")?.takeIf(String::isNotEmpty)
        ?: tailnetAddresses.firstOrNull { it.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")) }
    return host?.let { "$it:$listen" }
}
