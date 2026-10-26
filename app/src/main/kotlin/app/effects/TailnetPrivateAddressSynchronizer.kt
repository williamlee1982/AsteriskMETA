// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import app.AppState
import app.modes.usesPrivateAddressRouting
import data.AndroidAppStateStore
import engine.mihomo.runtime.MihomoRuntimeRepository
import engine.mihomo.tailscale.TailscaleStatusQuery
import engine.mihomo.tailscale.TailscaleStatusUnavailableReason
import engine.proxy.ProxyServiceResult
import engine.proxy.ProxyServiceUseCase
import engine.root.runtime.RootLifecycleTrigger
import features.logs.AndroidAppLogger
import features.mihomo.tailscale.tailnetPollInterval
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps **Private addresses** aligned with other tailnet devices while the process is alive.
 *
 * The poll uses the same Tailscale status API as the home card. VPN mode only stores the list:
 * it never asks for root and never restarts the VPN. A routing-mode restart goes through the
 * ordinary proxy restart, which those ROOT modes need because asteriskd reads the list only at start.
 */
internal suspend fun runTailnetPrivateAddressSync(
    stateStore: AndroidAppStateStore,
    runtimeRepository: MihomoRuntimeRepository,
    proxyService: ProxyServiceUseCase,
    clock: () -> Long = System::currentTimeMillis,
) {
    var syncRuntime = TailnetPrivateAddressRuntime()
    while (currentCoroutineContext().isActive) {
        val state = stateStore.state.value
        val statuses = if (state.proxyRunning) {
            runtimeRepository.tailscaleStatuses(state)
        } else {
            TailscaleStatusQuery.Unavailable(TailscaleStatusUnavailableReason.ProxyNotRunning)
        }
        val now = clock()
        val plan = planTailnetPrivateAddressSync(state, statuses, syncRuntime, now)
        if (
            plan.privateAddressCidrs != state.privateAddressCidrs ||
            plan.managedCidrs != state.tailnetManagedPrivateAddressCidrs
        ) {
            stateStore.update { current ->
                val latest = planTailnetPrivateAddressSync(current, statuses, syncRuntime, now)
                current.copy(
                    privateAddressCidrs = latest.privateAddressCidrs,
                    tailnetManagedPrivateAddressCidrs = latest.managedCidrs,
                )
            }
        }
        syncRuntime = plan.runtime
        if (plan.action != TailnetPrivateAddressAction.Keep) {
            AndroidAppLogger.info(LogTag, tailnetPrivateAddressSyncLog(plan))
        }
        if (plan.action == TailnetPrivateAddressAction.SaveAndRestart) {
            syncRuntime = applyPrivateAddressRestart(stateStore.state.value, proxyService, syncRuntime, clock)
        }
        val baseline = stateStore.state.value
        withTimeoutOrNull(tailnetPollInterval(statuses).inWholeMilliseconds) {
            stateStore.state.drop(1).first { next -> next.tailnetPrivateAddressWakeChanged(baseline) }
        }
    }
}

private suspend fun applyPrivateAddressRestart(
    restartState: AppState,
    proxyService: ProxyServiceUseCase,
    runtime: TailnetPrivateAddressRuntime,
    clock: () -> Long,
): TailnetPrivateAddressRuntime {
    // VPN and ROOT TUN do not install this list. Never restart them from here.
    if (!restartState.runMode.usesPrivateAddressRouting()) return runtime
    return try {
        when (val result = proxyService.restart(restartState, RootLifecycleTrigger.InternalResume)) {
            is ProxyServiceResult.Success -> runtime.copy(
                appliedCidrs = restartState.privateAddressCidrs,
                retryNotBeforeMillis = 0L,
            )
            is ProxyServiceResult.Failed -> {
                AndroidAppLogger.warn(
                    LogTag,
                    "tailnet_private_sync restart=failed type=${result.error.javaClass.simpleName}",
                )
                runtime.copy(retryNotBeforeMillis = clock() + TailnetPrivateAddressRestartRetryMillis)
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        AndroidAppLogger.warn(
            LogTag,
            "tailnet_private_sync restart=failed type=${error.javaClass.simpleName}",
        )
        runtime.copy(retryNotBeforeMillis = clock() + TailnetPrivateAddressRestartRetryMillis)
    }
}

private fun AppState.tailnetPrivateAddressWakeChanged(baseline: AppState): Boolean =
    tailnetPrivateAddressSyncEnabled != baseline.tailnetPrivateAddressSyncEnabled ||
        proxyRunning != baseline.proxyRunning ||
        runMode != baseline.runMode ||
        enableIpv6 != baseline.enableIpv6 ||
        privateAddressCidrs != baseline.privateAddressCidrs ||
        tailnetManagedPrivateAddressCidrs != baseline.tailnetManagedPrivateAddressCidrs

private const val LogTag = "TailnetPrivateSync"
