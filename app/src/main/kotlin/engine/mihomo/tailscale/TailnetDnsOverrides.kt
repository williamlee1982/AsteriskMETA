// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

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
 * `asterisk-tailnet-dns: false` turns it off. User entries for the same suffix win.
 */
internal const val AsteriskTailnetDnsKey = "asterisk-tailnet-dns"

/** Tailscale's MagicDNS domain; tailnet-specific names live under it. */
internal const val DefaultTailnetDnsSuffix = "ts.net"

internal data class TailnetDnsSettings(
    val outbound: String,
    val suffixes: List<String>,
    val routeRules: Boolean,
)

/** Effective settings for this profile, or null when disabled or not applicable. */
internal fun Map<String, Any?>.tailnetDnsSettings(): TailnetDnsSettings? {
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
    if (suffixes.isEmpty()) return null
    return TailnetDnsSettings(outbound, suffixes, routeRules = options["rules"] != false)
}

/**
 * Makes MagicDNS names resolvable in every run mode: a `nameserver-policy` entry per suffix
 * sends queries to the outbound's tailnet resolver, and (optionally) a `DOMAIN-SUFFIX` rule
 * routes the connections into the tailnet. Always removes [AsteriskTailnetDnsKey].
 * Call after the app's own DNS and DNS-hijack rule overrides.
 */
internal fun MutableMap<String, Any?>.putTailnetDnsOverrides() {
    val settings = tailnetDnsSettings()
    remove(AsteriskTailnetDnsKey)
    settings ?: return

    (this["dns"] as? Map<*, *>)?.let { dns ->
        val updatedDns = LinkedHashMap<String, Any?>()
        dns.forEach { (key, value) -> (key as? String)?.let { updatedDns[it] = value } }
        val policy = LinkedHashMap<String, Any?>()
        (dns["nameserver-policy"] as? Map<*, *>)?.forEach { (key, value) -> (key as? String)?.let { policy[it] = value } }
        val existing = policy.keys.map { it.trim().lowercase() }.toSet()
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
        val existingSuffixRules = rules.mapNotNull { rule ->
            (rule as? String)?.split(',')?.takeIf { it.size >= 2 && it[0].trim().equals("DOMAIN-SUFFIX", true) }
                ?.get(1)?.trim()?.lowercase()
        }.toSet()
        val added = settings.suffixes.filter { it !in existingSuffixRules }.map { "DOMAIN-SUFFIX,$it,${settings.outbound}" }
        if (added.isNotEmpty()) {
            // Keep the app's DNS-hijack rules first so port-53 traffic still reaches dns-out.
            val insertAt = rules.indexOfFirst { (it as? String)?.trim()?.endsWith(",dns-out") != true }
                .let { if (it < 0) rules.size else it }
            rules.addAll(insertAt, added)
            put("rules", rules)
        }
    }
}

private val DomainSuffixPattern = Regex("""^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)*$""")

internal fun normalizedTailnetDnsSuffix(value: String): String? {
    val suffix = value.trim().lowercase().removePrefix("+.").removePrefix("*.").trim('.')
    return suffix.takeIf { it.isNotEmpty() && DomainSuffixPattern.matches(it) }
}
