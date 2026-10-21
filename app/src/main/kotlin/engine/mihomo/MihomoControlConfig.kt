// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo

import app.AppState
import engine.network.findAvailableTcpPort
import engine.network.isTcpPortAvailable
import engine.network.toPortOrNull
import engine.root.RootModeEngine
import engine.vpn.VpnDefaults

internal const val MihomoControlHost = "127.0.0.1"
internal const val DefaultMihomoControlPort = 9090
internal const val DefaultMihomoDelayTestUrl = app.DefaultConnectionTestUrl
internal const val DefaultMihomoDelayTimeoutMillis = 5000

internal data class MihomoControlConfig(
    val host: String = MihomoControlHost,
    val port: Int = DefaultMihomoControlPort,
    val secret: String = "",
    val scheme: String = "http",
) {
    val address: String
        get() = if (":" in host) "[$host]:$port" else "$host:$port"

    val baseUrl: String
        get() = "$scheme://$address"

    override fun toString(): String {
        return "MihomoControlConfig(host=$host, port=$port, scheme=$scheme, secret=<redacted>)"
    }
}

internal fun AppState.mihomoControlConfig(): MihomoControlConfig {
    return MihomoControlConfig(
        port = mihomoControlPort.toPortOrNull() ?: DefaultMihomoControlPort,
        secret = mihomoControlSecret.trim(),
    )
}

/**
 * [ownedPort] is the controller port of the ROOT core this start replaces. That port is busy only
 * because our own core still holds it (it is released by the stop before launch), so it must be
 * kept instead of drifting to a random port on every restart.
 */
internal fun AppState.withResolvedMihomoControlPort(
    ownedPort: Int? = null,
    isPortAvailable: (Int) -> Boolean = { port -> isTcpPortAvailable(MihomoControlHost, port) },
): AppState {
    val configuredPort = mihomoControlPort.toPortOrNull()
    val excludedPorts = mihomoControlExcludedPorts()
    val resolvedPort = when {
        configuredPort != null &&
            configuredPort !in excludedPorts &&
            (configuredPort == ownedPort || isPortAvailable(configuredPort)) -> configuredPort

        else -> availableMihomoControlPort(excludedPorts) ?: configuredPort ?: DefaultMihomoControlPort
    }
    val resolvedPortText = resolvedPort.toString()
    return if (mihomoControlPort == resolvedPortText) this else copy(mihomoControlPort = resolvedPortText)
}

private fun AppState.mihomoControlExcludedPorts(): Set<Int> {
    return buildSet {
        add(localProxyPort.toPortOrNull() ?: VpnDefaults.LOCAL_PROXY_PORT)
        add(transparentProxyPort.toPortOrNull() ?: RootModeEngine.DefaultTproxyPort)
        add(socks5ProxyPort.toPortOrNull() ?: RootModeEngine.DefaultTun2SocksProxyPort)
        add(bpf2SocksBridgePort.toPortOrNull() ?: RootModeEngine.DefaultBpf2SocksBridgePort)
    }
}

private fun availableMihomoControlPort(excludedPorts: Set<Int>): Int? {
    return findAvailableTcpPort(
        listenAddress = MihomoControlHost,
        excludedPorts = excludedPorts,
        attempts = RandomPortAttempts,
    )
}

private const val RandomPortAttempts = 32
