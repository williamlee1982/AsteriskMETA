// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import app.AppState
import app.modes.usesPrivateAddressRouting
import engine.mihomo.tailscale.TailscaleStatusQuery
import engine.mihomo.tailscale.tailnetPeerSnapshot
import engine.network.canonicalIpCidrOrNull
import engine.network.syncTailnetPrivateAddresses

internal enum class TailnetPrivateAddressAction { Keep, Save, SaveAndRestart }

/**
 * Bookkeeping for whether the running private-address router already has [appliedCidrs].
 * A process start treats the current list as already applied, so opening the app does not
 * restart a healthy proxy. A later peer change in TPROXY, TUN2SOCKS or BPF2SOCKS does.
 */
internal data class TailnetPrivateAddressRuntime(
    val proxyWasRunning: Boolean = false,
    val appliedRunMode: Int? = null,
    val appliedCidrs: List<String>? = null,
    val retryNotBeforeMillis: Long = 0L,
)

internal data class TailnetPrivateAddressPlan(
    val privateAddressCidrs: List<String>,
    val managedCidrs: List<String>,
    val action: TailnetPrivateAddressAction,
    val observedPeers: Int?,
    val added: Int,
    val removed: Int,
    val runtime: TailnetPrivateAddressRuntime,
)

internal const val TailnetPrivateAddressRestartRetryMillis = 60_000L

/**
 * Decides the next private-address list and whether the running proxy must be restarted.
 *
 * Restart is limited to modes that compile the list into asteriskd rules. The Clash API
 * cannot change those rules. VPN and ROOT TUN only persist the list.
 */
internal fun planTailnetPrivateAddressSync(
    state: AppState,
    statuses: TailscaleStatusQuery,
    runtime: TailnetPrivateAddressRuntime,
    nowMillis: Long,
): TailnetPrivateAddressPlan {
    val anchored = when {
        state.proxyRunning && (!runtime.proxyWasRunning || runtime.appliedRunMode != state.runMode) ->
            runtime.copy(
                proxyWasRunning = true,
                appliedRunMode = state.runMode,
                appliedCidrs = state.privateAddressCidrs,
                retryNotBeforeMillis = 0L,
            )
        !state.proxyRunning -> runtime.copy(
            proxyWasRunning = false,
            appliedRunMode = null,
            appliedCidrs = null,
            retryNotBeforeMillis = 0L,
        )
        else -> runtime
    }
    val snapshot = tailnetPeerSnapshot(statuses)
    val merged = syncTailnetPrivateAddresses(
        enabled = state.tailnetPrivateAddressSyncEnabled,
        privateAddressCidrs = state.privateAddressCidrs,
        managedCidrs = state.tailnetManagedPrivateAddressCidrs,
        peerAddresses = snapshot.peerAddresses,
        selfAddresses = snapshot.selfAddresses,
    )
    val changed = merged.privateAddressCidrs != state.privateAddressCidrs ||
        merged.managedCidrs != state.tailnetManagedPrivateAddressCidrs
    val routingActive = state.proxyRunning && state.runMode.usesPrivateAddressRouting()
    val restartNeeded = routingActive &&
        merged.privateAddressCidrs.routingProjection(state.enableIpv6) !=
        anchored.appliedCidrs?.routingProjection(state.enableIpv6)
    val action = when {
        restartNeeded && nowMillis >= anchored.retryNotBeforeMillis -> TailnetPrivateAddressAction.SaveAndRestart
        changed -> TailnetPrivateAddressAction.Save
        else -> TailnetPrivateAddressAction.Keep
    }
    val nextRuntime = when {
        action == TailnetPrivateAddressAction.SaveAndRestart -> anchored
        !state.proxyRunning -> anchored.copy(appliedCidrs = null)
        !routingActive -> anchored.copy(appliedCidrs = merged.privateAddressCidrs, retryNotBeforeMillis = 0L)
        else -> anchored
    }
    val oldKeys = state.privateAddressCidrs.mapNotNullTo(HashSet(), ::canonicalIpCidrOrNull)
    val newKeys = merged.privateAddressCidrs.mapNotNullTo(HashSet(), ::canonicalIpCidrOrNull)
    return TailnetPrivateAddressPlan(
        privateAddressCidrs = merged.privateAddressCidrs,
        managedCidrs = merged.managedCidrs,
        action = action,
        observedPeers = merged.acceptedPeers,
        added = (newKeys - oldKeys).size,
        removed = (oldKeys - newKeys).size,
        runtime = nextRuntime,
    )
}

/** Counts only. The address list itself must not be logged. */
internal fun tailnetPrivateAddressSyncLog(plan: TailnetPrivateAddressPlan): String {
    val peers = plan.observedPeers?.toString() ?: "unknown"
    return "tailnet_private_sync action=${plan.action.name} peers=$peers added=${plan.added} removed=${plan.removed}"
}

private fun List<String>.routingProjection(enableIpv6: Boolean): List<String> =
    if (enableIpv6) this else filterNot { cidr -> ':' in cidr }
