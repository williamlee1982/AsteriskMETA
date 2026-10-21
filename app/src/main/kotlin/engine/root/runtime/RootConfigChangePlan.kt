// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import engine.mihomo.MihomoYamlLoadSettings
import org.snakeyaml.engine.v2.api.Load

/** How a requested ROOT restart is applied to an already running AsteriskMETA core. */
internal enum class RootConfigChangePlan {
    /** Published core and supervisor configuration are byte-identical: nothing to do. */
    NoOp,

    /** Only Clash-API-reloadable parts changed: `PUT /configs?force=true`, keep rules and connections. */
    HotReload,

    /** Something the Clash API cannot apply safely changed: supervised stop + start. */
    Restart,
}

/**
 * Pure decision for item "restart only when needed". [currentProfile]/[currentDaemon] are the files
 * the running core and asteriskd were launched with; `null` means unknown, which forces a restart.
 *
 * Any change of the asteriskd configuration (ROOT mode, TPROXY/TUN/redirect ports, routing marks,
 * Tailnet CIDRs, app lists, DNS hijack, service control, ...) and any change of a core key that
 * the supervisor's rules depend on requires a restart. Everything else (proxies, groups, rules,
 * providers, DNS upstreams, sniffer, ...) is hot-reloaded through the Clash API.
 */
internal fun planRootConfigChange(
    currentProfile: ByteArray?,
    currentDaemon: ByteArray?,
    nextProfile: ByteArray,
    nextDaemon: ByteArray,
): RootConfigChangePlan {
    if (currentProfile == null || currentDaemon == null) return RootConfigChangePlan.Restart
    if (!currentDaemon.contentEquals(nextDaemon)) return RootConfigChangePlan.Restart
    if (currentProfile.contentEquals(nextProfile)) return RootConfigChangePlan.NoOp
    val current = restartSensitiveProfileView(currentProfile) ?: return RootConfigChangePlan.Restart
    val next = restartSensitiveProfileView(nextProfile) ?: return RootConfigChangePlan.Restart
    return if (current == next) RootConfigChangePlan.HotReload else RootConfigChangePlan.Restart
}

/** Top-level core keys that listeners, the controller or the supervisor's rules depend on. */
internal val RootRestartSensitiveProfileKeys = listOf(
    "port", "socks-port", "mixed-port", "redir-port", "tproxy-port",
    "allow-lan", "bind-address", "lan-allowed-ips", "lan-disallowed-ips",
    "authentication", "skip-auth-prefixes",
    "external-controller", "external-controller-tls", "external-controller-unix",
    "external-controller-pipe", "external-controller-cors", "secret", "external-ui",
    "external-ui-name", "external-ui-url",
    "tun", "iptables", "ebpf", "auto-redirect", "interface-name", "routing-mark",
    "ipv6", "listeners", "tunnels", "tailscale", "inbound-tfo", "inbound-mptcp",
)

/** `dns` sub-keys that the supervisor's DNS hijack / FakeIP rules depend on. */
internal val RootRestartSensitiveDnsKeys = listOf(
    "enable", "listen", "ipv6", "enhanced-mode", "fake-ip-range", "fake-ip-range6",
)

/**
 * Returns the restart-sensitive projection of a core profile, or `null` when it is not a plain
 * YAML mapping (e.g. an encrypted profile), in which case the caller must restart.
 */
internal fun restartSensitiveProfileView(profile: ByteArray): Map<String, Any?>? {
    val root = runCatching {
        Load(MihomoYamlLoadSettings).loadFromString(profile.toString(Charsets.UTF_8)) as? Map<*, *>
    }.getOrNull() ?: return null
    val view = linkedMapOf<String, Any?>()
    RootRestartSensitiveProfileKeys.forEach { key -> view[key] = root[key] }
    val dns = root["dns"] as? Map<*, *>
    RootRestartSensitiveDnsKeys.forEach { key -> view["dns.$key"] = dns?.get(key) }
    // Tailscale outbounds carry Tailnet routing that the supervisor installs at start.
    view["proxies.tailscale"] = (root["proxies"] as? List<*>)
        ?.filterIsInstance<Map<*, *>>()
        ?.filter { proxy -> proxy["type"]?.toString()?.lowercase() == "tailscale" }
    return view
}
