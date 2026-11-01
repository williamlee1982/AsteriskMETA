// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo

import engine.network.isIpAddress
import engine.network.toPortOrNull

internal const val NameserverPolicySeparator = "=>"

/**
 * Turns `rule=>server1,server2` settings into a `nameserver-policy` map.
 *
 * Invalid keys are dropped here. They must not fall through into `nameserver`:
 * mihomo then tries to parse the whole `key=>server` string as a URL and refuses
 * to start (`first path segment in URL cannot contain colon`).
 *
 * A bare `*` is a valid key. Mihomo 1.19.33's domain trie stores it as a one-label
 * wildcard, so it matches `wbvm` and does not match `wbvm.example.ts.net`.
 */
internal fun List<String>.toMihomoNameserverPolicy(): Map<String, Any?> {
    val policy = linkedMapOf<String, Any?>()
    forEach { entry ->
        val separatorIndex = entry.indexOf(NameserverPolicySeparator)
        if (separatorIndex <= 0 || separatorIndex >= entry.length - NameserverPolicySeparator.length) {
            return@forEach
        }
        val rule = entry.substring(0, separatorIndex).trim()
        val servers = entry.substring(separatorIndex + NameserverPolicySeparator.length)
            .split(",")
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        if (!isMihomoNameserverPolicyKey(rule) || servers.isEmpty() || servers.any { !isMihomoDnsServer(it) }) {
            return@forEach
        }
        policy[rule] = if (servers.size == 1) servers.first() else servers
    }
    return policy
}

/**
 * Mihomo 1.19.33 `parseNameServerPolicy`: `geosite:` and `rule-set:` keys, including
 * comma-separated lists, plus domain patterns accepted by `trie.ValidAndSplitDomain`.
 */
internal fun isMihomoNameserverPolicyKey(rule: String): Boolean {
    val trimmed = rule.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
    val lower = trimmed.lowercase()
    if (lower.startsWith("geosite:") || lower.startsWith("rule-set:")) {
        val body = trimmed.substringAfter(':')
        return body.isNotEmpty() && body.split(',').all(String::isNotEmpty)
    }
    return trimmed.split(',').all { part -> isMihomoDomainPattern(part.trim()) }
}

/** Clash-style domain pattern. A single `*` matches one label and nothing with a dot. */
internal fun isMihomoDomainPattern(domain: String): Boolean {
    if (domain.isEmpty() || domain.last() == '.') return false
    if (domain.first().isWhitespace() || domain.last().isWhitespace()) return false
    val parts = domain.lowercase().split('.')
    if (parts.size == 1) {
        if (parts[0].isEmpty()) return false
    } else if (parts.drop(1).any(String::isEmpty)) {
        return false
    }
    parts.forEachIndexed { index, part ->
        if ('+' in part && (part != "+" || index != 0 || parts.size == 1)) return false
        if ('*' in part && part != "*") return false
    }
    return true
}

internal fun isMihomoDnsServer(value: String): Boolean {
    if (value.equals("localhost", ignoreCase = true) || value.equals(SystemDnsKeyword, ignoreCase = true)) {
        return true
    }

    val schemeEnd = value.indexOf("://")
    if (schemeEnd >= 0) {
        val scheme = value.substring(0, schemeEnd).lowercase()
        if (scheme !in MihomoDnsUrlSchemes) return false
        if (scheme == "system") return value == SystemDnsServer
        val authority = value.substring(schemeEnd + 3)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
        if (scheme == "rcode" || scheme == "dhcp" || scheme == "tailscale" || scheme == "ts") {
            return authority.isNotBlank()
        }
        return isMihomoDnsAuthority(authority)
    }

    return isIpAddress(value) || (!value.contains(":") && isDnsServerHost(value))
}

private fun isMihomoDnsAuthority(authority: String): Boolean {
    val trimmed = authority.trim()
    if (trimmed.isBlank()) return false

    if (trimmed.startsWith("[")) {
        val closeBracketIndex = trimmed.indexOf(']')
        if (closeBracketIndex <= 1) return false
        val host = trimmed.substring(1, closeBracketIndex)
        val rest = trimmed.substring(closeBracketIndex + 1)
        return isIpAddress(host) && (rest.isEmpty() || rest.startsWith(":") && rest.drop(1).toPortOrNull() != null)
    }

    val colonCount = trimmed.count { it == ':' }
    if (colonCount == 0) {
        return isIpAddress(trimmed) || isDnsServerHost(trimmed)
    }
    if (colonCount == 1) {
        val host = trimmed.substringBefore(':')
        val port = trimmed.substringAfter(':')
        return (isIpAddress(host) || isDnsServerHost(host)) && port.toPortOrNull() != null
    }

    return isIpAddress(trimmed)
}

private fun isDnsServerHost(domain: String): Boolean {
    val normalized = domain.removeSuffix(".")
    if (normalized.isEmpty() || normalized.length > 253) return false
    if (normalized.any { it.isWhitespace() || it == '/' || it == ':' }) return false
    if (normalized == "*") return true

    return normalized.split(".").all { label ->
        label == "*" ||
            label.startsWith("+") ||
            (
                label.isNotEmpty() &&
                    label.length <= 63 &&
                    label.first() != '-' &&
                    label.last() != '-' &&
                    label.all { it.isLetterOrDigit() || it == '-' }
                )
    }
}

private const val SystemDnsServer = "system://"
private const val SystemDnsKeyword = "system"

private val MihomoDnsUrlSchemes = setOf(
    "https",
    "h2c",
    "h3",
    "https+local",
    "h2c+local",
    "h3+local",
    "quic",
    "quic+local",
    "tls",
    "tls+local",
    "tcp",
    "tcp+local",
    "udp",
    "udp+local",
    "dhcp",
    "system",
    "rcode",
    "tailscale",
    "ts",
)
