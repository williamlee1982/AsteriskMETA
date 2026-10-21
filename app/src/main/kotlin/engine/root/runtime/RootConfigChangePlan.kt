// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import engine.mihomo.MihomoYamlLoadSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
internal data class RootConfigChangeDecision(
    val plan: RootConfigChangePlan,
    /** Log token only: plan name plus the key that forced Restart. Never a config value. */
    val reason: String,
)

internal fun planRootConfigChange(
    currentProfile: ByteArray?,
    currentDaemon: ByteArray?,
    nextProfile: ByteArray,
    nextDaemon: ByteArray,
): RootConfigChangePlan = explainRootConfigChange(currentProfile, currentDaemon, nextProfile, nextDaemon).plan

internal fun explainRootConfigChange(
    currentProfile: ByteArray?,
    currentDaemon: ByteArray?,
    nextProfile: ByteArray,
    nextDaemon: ByteArray,
): RootConfigChangeDecision {
    if (currentProfile == null || currentDaemon == null) {
        return RootConfigChangeDecision(RootConfigChangePlan.Restart, "missing_current")
    }
    if (!currentDaemon.contentEquals(nextDaemon)) {
        return RootConfigChangeDecision(RootConfigChangePlan.Restart, daemonRestartReason(currentDaemon, nextDaemon))
    }
    if (currentProfile.contentEquals(nextProfile)) {
        return RootConfigChangeDecision(RootConfigChangePlan.NoOp, "identical")
    }
    val current = restartSensitiveProfileView(currentProfile)
    val next = restartSensitiveProfileView(nextProfile)
    if (current == null || next == null) {
        return RootConfigChangeDecision(RootConfigChangePlan.Restart, "profile_unreadable")
    }
    if (current == next) {
        return RootConfigChangeDecision(RootConfigChangePlan.HotReload, "profile_reloadable")
    }
    val keys = (current.keys + next.keys).distinct().sorted().filter { key -> current[key] != next[key] }
    return RootConfigChangeDecision(RootConfigChangePlan.Restart, profileKeyReason(keys))
}

internal fun sanitizeRootLogToken(value: String): String {
    val cleaned = value.map { character ->
        if (character.isLetterOrDigit() || character in "._:,-_") character else '?'
    }.joinToString("").take(64)
    return cleaned.ifBlank { "unknown" }
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

private fun profileKeyReason(keys: List<String>): String {
    val shown = keys.take(MaxLoggedKeys).joinToString(",") { key -> sanitizeRootLogToken(key) }
    return if (shown.isBlank()) "profile_unreadable" else "profile_key:$shown"
}

private fun daemonRestartReason(current: ByteArray, next: ByteArray): String {
    val keys = changedJsonKeyPaths(current.toString(Charsets.UTF_8), next.toString(Charsets.UTF_8))
    if (keys.isNullOrEmpty()) return "daemon_config"
    val shown = keys.take(MaxLoggedKeys).joinToString(",") { key -> sanitizeRootLogToken(key) }
    return if (shown.isBlank()) "daemon_config" else "daemon_config:$shown"
}

private fun changedJsonKeyPaths(current: String, next: String): List<String>? {
    val left = runCatching { DaemonJson.parseToJsonElement(current) }.getOrNull() as? JsonObject ?: return null
    val right = runCatching { DaemonJson.parseToJsonElement(next) }.getOrNull() as? JsonObject ?: return null
    return jsonKeyDiff(left, right, "")
}

private fun jsonKeyDiff(left: JsonObject, right: JsonObject, prefix: String): List<String> {
    return (left.keys + right.keys).distinct().sorted().flatMap { key ->
        val path = if (prefix.isEmpty()) key else "$prefix.$key"
        val current = left[key]
        val next = right[key]
        when {
            current == null || next == null -> listOf(path)
            current is JsonObject && next is JsonObject -> jsonKeyDiff(current, next, path)
            current == next -> emptyList()
            else -> listOf(path)
        }
    }
}

private const val MaxLoggedKeys = 8

private val DaemonJson = Json { isLenient = true }
