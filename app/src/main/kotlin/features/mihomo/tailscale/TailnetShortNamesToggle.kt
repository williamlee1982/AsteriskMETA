// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.mihomo.tailscale

import app.AppState
import engine.proxy.ProxyServiceResult

/**
 * Saves the short-hostname switch and applies it. The setting changes the generated runtime
 * profile (`nameserver-policy` and rules), and a profile reload only re-reads the profile the
 * core was started with, so a running proxy is restarted with the new state.
 */
internal suspend fun applyTailnetShortNamesToggle(
    current: AppState,
    enabled: Boolean,
    update: ((AppState) -> AppState) -> Unit,
    restart: suspend (AppState) -> ProxyServiceResult,
    onFailure: suspend (Throwable) -> Unit,
) {
    val next = current.copy(tailnetShortNamesEnabled = enabled)
    update { it.copy(tailnetShortNamesEnabled = enabled) }
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
