// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.mihomo.tailscale

import app.AppState
import engine.proxy.ProxyServiceResult

/**
 * Saves the inbound-forwarding switch and applies it. The setting changes the generated runtime
 * profile (forwards are stripped while it is off), and a profile reload only re-reads the
 * profile the core was started with, so a running proxy is restarted with the new state, the
 * same way the app applies other profile changes.
 */
internal suspend fun applyTailnetInboundToggle(
    current: AppState,
    enabled: Boolean,
    update: ((AppState) -> AppState) -> Unit,
    restart: suspend (AppState) -> ProxyServiceResult,
    onFailure: suspend (Throwable) -> Unit,
) {
    val next = current.copy(tailnetInboundForwardsEnabled = enabled)
    update { it.copy(tailnetInboundForwardsEnabled = enabled) }
    if (!next.proxyRunning) return
    when (val result = restart(next)) {
        is ProxyServiceResult.Success -> update { state ->
            state.copy(
                proxyRunning = result.proxyRunning,
                localProxyPort = result.appState?.localProxyPort ?: state.localProxyPort,
                mihomoControlPort = result.appState?.mihomoControlPort ?: state.mihomoControlPort,
            )
        }
        is ProxyServiceResult.Failed -> onFailure(result.error)
    }
}
