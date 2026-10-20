// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

/** Removes inbound forwarding declarations from standby or disabled runtime profiles. */
internal fun MutableMap<String, Any?>.putTailnetInboundOverrides(
    exposePorts: Boolean,
    enabled: Boolean = true,
) {
    if (exposePorts && enabled) return
    val proxies = this["proxies"] as? List<*> ?: return
    this["proxies"] = proxies.map { raw ->
        val proxy = raw as? Map<*, *> ?: return@map raw
        if ((proxy["type"] as? String)?.trim()?.equals(MihomoTailscaleProxyType, ignoreCase = true) != true) return@map raw
        LinkedHashMap<Any?, Any?>(proxy).apply { remove("inbound-forwards") }
    }
}
