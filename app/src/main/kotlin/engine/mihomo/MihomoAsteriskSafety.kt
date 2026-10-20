// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo

import org.snakeyaml.engine.v2.api.Load

/** Bits asteriskd uses for its own fwmarks/ip rules (ASTERISKD_MARK_MASK). */
internal const val AsteriskMarkMask = 0x60000000L

internal fun routingMarkCollidesWithAsterisk(value: Long): Boolean = (value and AsteriskMarkMask) != 0L

private fun Any?.routingMarkValueOrNull(): Long? = when (this) {
    is Number -> toLong()
    is String -> trim().let { text ->
        if (text.startsWith("0x", ignoreCase = true)) text.drop(2).toLongOrNull(16) else text.toLongOrNull()
    }
    else -> null
}

private fun Map<*, *>.hasCollidingRoutingMark(): Boolean =
    this["routing-mark"].routingMarkValueOrNull()?.let(::routingMarkCollidesWithAsterisk) == true

/**
 * A `routing-mark` carrying Asterisk mark bits makes the core's own sockets match the
 * TPROXY/TUN ip rules and loop back into the core. In ROOT modes such values are removed
 * (top level and per proxy); profiles without a collision stay byte for byte identical.
 */
internal fun stripAsteriskRoutingMarkCollisions(sourceBytes: ByteArray): ByteArray {
    val text = sourceBytes.toString(Charsets.UTF_8)
    if (!text.contains("routing-mark")) return sourceBytes.copyOf()
    val escaped = text.escapeSupplementaryYamlCodePoints()
    val root = runCatching {
        Load(MihomoYamlLoadSettings).loadFromString(escaped.value) as? Map<*, *>
    }.getOrNull() ?: return sourceBytes.copyOf()
    val proxies = (root["proxies"] as? List<*>).orEmpty()
    val collides = root.hasCollidingRoutingMark() ||
        proxies.any { (it as? Map<*, *>)?.hasCollidingRoutingMark() == true }
    if (!collides) return sourceBytes.copyOf()
    val normalized = linkedMapOf<String, Any?>()
    root.forEach { (key, value) ->
        val name = key as? String ?: return@forEach
        normalized[name] = normalizeYamlValue(value)
    }
    if (root.hasCollidingRoutingMark()) normalized.remove("routing-mark")
    (normalized["proxies"] as? List<*>)?.let { list ->
        normalized["proxies"] = list.map { item ->
            val proxy = item as? Map<*, *> ?: return@map item
            if (!proxy.hasCollidingRoutingMark()) return@map item
            linkedMapOf<String, Any?>().apply {
                proxy.forEach { (key, value) -> if (key != "routing-mark") put(key.toString(), value) }
            }
        }
    }
    return escaped.restore(dumpYaml(normalizeYamlValue(normalized))).toByteArray(Charsets.UTF_8)
}

/** `dns-out` is reserved for the DNS outbound the managed hijack rules target. */
internal fun requireReservedDnsOutName(proxies: Any?, proxyGroups: Any?) {
    (proxies as? List<*>).orEmpty().forEach { item ->
        val proxy = item as? Map<*, *> ?: return@forEach
        if (proxy["name"]?.toString() == MihomoTags.DNS_OUT &&
            proxy["type"]?.toString()?.trim()?.equals("dns", ignoreCase = true) != true
        ) {
            error("Proxy name \"${MihomoTags.DNS_OUT}\" is reserved for the DNS outbound (type: dns); rename this proxy")
        }
    }
    (proxyGroups as? List<*>).orEmpty().forEach { item ->
        val group = item as? Map<*, *> ?: return@forEach
        if (group["name"]?.toString() == MihomoTags.DNS_OUT) {
            error("Proxy group name \"${MihomoTags.DNS_OUT}\" is reserved for the DNS outbound; rename this group")
        }
    }
}
