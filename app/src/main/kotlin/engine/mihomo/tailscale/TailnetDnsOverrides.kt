// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

import engine.network.TailnetIpv4Cidr
import engine.network.TailnetIpv6Cidr

/**
 * Profile key controlling Tailnet name resolution. Read and removed by the app before the
 * profile reaches the core:
 *
 * ```yaml
 * asterisk-tailnet-dns:
 *   enable: true          # default true when the profile declares a tailscale outbound
 *   outbound: TS          # default: the first tailscale outbound
 *   suffixes: [ts.net]    # MagicDNS suffixes; set your control server's base domain if custom
 *   rules: true           # prepend DOMAIN-SUFFIX rules routing the suffixes to the outbound
 * ```
 *
 * `asterisk-tailnet-dns: false` turns the whole feature off, including short names.
 * User entries for the same suffix or for `*` win.
 *
 * Short names (a single DNS label such as `wbvm`) are controlled by the app setting passed to
 * [putTailnetDnsOverrides]. They use mihomo's one-label `*` nameserver-policy key.
 */
internal const val AsteriskTailnetDnsKey = "asterisk-tailnet-dns"

/** Tailscale's MagicDNS domain; tailnet-specific names live under it. */
internal const val DefaultTailnetDnsSuffix = "ts.net"

/**
 * Mihomo 1.19.33 domain trie: a policy key of exactly `*` matches one label (`wbvm`)
 * and does not match a name that contains a dot.
 */
internal const val SingleLabelNameserverPolicyKey = "*"

/** One DNS label. No comma, so the rule stays `DOMAIN-REGEX,<pattern>,<proxy>`. */
internal const val SingleLabelDomainRegex = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"

internal data class TailnetDnsSettings(
    val outbound: String,
    val suffixes: List<String>,
    val routeRules: Boolean,
    val shortNames: Boolean,
)

/** Effective settings for this profile, or null when disabled or not applicable. */
internal fun Map<String, Any?>.tailnetDnsSettings(shortNames: Boolean = true): TailnetDnsSettings? {
    val raw = this[AsteriskTailnetDnsKey]
    if (raw == false) return null
    val options = raw as? Map<*, *> ?: emptyMap<Any?, Any?>()
    if (options["enable"] == false) return null
    val tailscaleOutbounds = mihomoTailscaleOutbounds().map { it.name }
    if (tailscaleOutbounds.isEmpty()) return null
    val outbound = (options["outbound"] as? String)?.trim()?.takeIf(String::isNotEmpty)
        ?: tailscaleOutbounds.first()
    // tailscale:// DNS clients are registered per tailscale outbound; anything else cannot resolve.
    if (outbound !in tailscaleOutbounds) return null
    val suffixes = when (val value = options["suffixes"]) {
        null -> listOf(DefaultTailnetDnsSuffix)
        is String -> listOf(value)
        is List<*> -> value.filterIsInstance<String>()
        else -> emptyList()
    }.mapNotNull(::normalizedTailnetDnsSuffix).distinct()
    if (suffixes.isEmpty() && !shortNames) return null
    return TailnetDnsSettings(outbound, suffixes, routeRules = options["rules"] != false, shortNames = shortNames)
}

/**
 * Makes MagicDNS names resolvable in every run mode: a `nameserver-policy` entry per suffix
 * sends queries to the outbound's tailnet resolver, and (optionally) a `DOMAIN-SUFFIX` rule
 * routes the connections into the tailnet.
 *
 * When [shortNames] is true, a `*` policy sends dotless names to the same resolver, and rules
 * route those names plus the Tailnet CGNAT ranges through the outbound. `localhost` stays on
 * the system resolver and DIRECT so the one-label wildcard does not capture it.
 *
 * Always removes [AsteriskTailnetDnsKey]. Call after the app's own DNS and DNS-hijack rule overrides.
 */
internal fun MutableMap<String, Any?>.putTailnetDnsOverrides(shortNames: Boolean = true) {
    val settings = tailnetDnsSettings(shortNames)
    remove(AsteriskTailnetDnsKey)
    settings ?: return

    (this["dns"] as? Map<*, *>)?.let { dns ->
        val updatedDns = LinkedHashMap<String, Any?>()
        dns.forEach { (key, value) -> (key as? String)?.let { updatedDns[it] = value } }
        val policy = LinkedHashMap<String, Any?>()
        (dns["nameserver-policy"] as? Map<*, *>)?.forEach { (key, value) -> (key as? String)?.let { policy[it] = value } }
        val existing = policy.keys.map { it.trim().lowercase() }.toSet()
        if (settings.shortNames) {
            // Static `localhost` beats the one-label `*` wildcard, including when the user owns `*`.
            if ("localhost" !in existing) {
                policy["localhost"] = "system"
            }
            if (SingleLabelNameserverPolicyKey !in existing) {
                policy[SingleLabelNameserverPolicyKey] = "tailscale://${settings.outbound}"
            }
        }
        settings.suffixes.forEach { suffix ->
            val key = "+.$suffix"
            if (key !in existing && suffix !in existing && ".$suffix" !in existing) {
                policy[key] = "tailscale://${settings.outbound}"
            }
        }
        updatedDns["nameserver-policy"] = policy
        put("dns", updatedDns)
    }

    if (settings.routeRules) {
        val rules = (this["rules"] as? List<*>)?.toMutableList() ?: mutableListOf<Any?>("MATCH,DIRECT")
        // Keep the app's DNS-hijack rules first so port-53 traffic still reaches dns-out.
        val insertAt = rules.indexOfFirst { (it as? String)?.trim()?.endsWith(",dns-out") != true }
            .let { if (it < 0) rules.size else it }
        val added = mutableListOf<String>()
        if (settings.shortNames) {
            // The one-label regex also matches `localhost`. An earlier exact rule keeps it DIRECT
            // even when the profile routes localhost further down the list.
            if (rules.take(insertAt).none { it.isRule("DOMAIN", "localhost") }) {
                added += "DOMAIN,localhost,DIRECT"
            }
            if (!rules.hasRule("DOMAIN-REGEX", SingleLabelDomainRegex)) {
                added += "DOMAIN-REGEX,$SingleLabelDomainRegex,${settings.outbound}"
            }
            if (!rules.hasRule("IP-CIDR", TailnetIpv4Cidr)) {
                added += "IP-CIDR,$TailnetIpv4Cidr,${settings.outbound},no-resolve"
            }
            if (!rules.hasRule("IP-CIDR6", TailnetIpv6Cidr)) {
                added += "IP-CIDR6,$TailnetIpv6Cidr,${settings.outbound},no-resolve"
            }
        }
        val existingSuffixRules = rules.mapNotNull { rule ->
            (rule as? String)?.split(',')?.takeIf { it.size >= 2 && it[0].trim().equals("DOMAIN-SUFFIX", true) }
                ?.get(1)?.trim()?.lowercase()
        }.toSet()
        added += settings.suffixes.filter { it !in existingSuffixRules }.map { "DOMAIN-SUFFIX,$it,${settings.outbound}" }
        if (added.isNotEmpty()) {
            rules.addAll(insertAt, added)
            put("rules", rules)
        }
    }
}

private fun Any?.isRule(type: String, payload: String): Boolean {
    val parts = (this as? String)?.split(',') ?: return false
    return parts.size >= 2 &&
        parts[0].trim().equals(type, ignoreCase = true) &&
        parts[1].trim().equals(payload, ignoreCase = true)
}

private fun List<Any?>.hasRule(type: String, payload: String): Boolean = any { it.isRule(type, payload) }

private val DomainSuffixPattern = Regex("""^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)*$""")

internal fun normalizedTailnetDnsSuffix(value: String): String? {
    val suffix = value.trim().lowercase().removePrefix("+.").removePrefix("*.").trim('.')
    return suffix.takeIf { it.isNotEmpty() && DomainSuffixPattern.matches(it) }
}
