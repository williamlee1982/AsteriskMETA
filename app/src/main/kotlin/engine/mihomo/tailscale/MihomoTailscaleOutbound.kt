// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

import engine.mihomo.MihomoYamlLoadSettings
import engine.mihomo.escapeSupplementaryYamlCodePoints
import org.snakeyaml.engine.v2.api.Load

/**
 * Non-secret summary of a Mihomo `type: tailscale` outbound declared in a profile.
 *
 * Secret material is never retained: `auth-key` is reduced to [hasAuthKey] and `control-url`
 * to [usesCustomControlUrl] plus its host ([controlUrlHost], used to validate login URLs), so
 * instances are safe to log, render and snapshot in tests.
 */
internal data class MihomoTailscaleOutbound(
    val name: String,
    val hostname: String?,
    val stateDir: String?,
    val hasAuthKey: Boolean,
    val ephemeral: Boolean,
    val usesCustomControlUrl: Boolean,
    val controlUrlHost: String? = null,
    /** Proxy provider that supplied the outbound; null for top-level `proxies` entries. */
    val provider: String? = null,
    /** The running core reported [stateDir] as Mihomo's implicit default directory. */
    val stateDirIsDefault: Boolean = false,
    val inboundForwards: List<TailnetInboundForward> = emptyList(),
) {
    val key: TailnetOutboundKey get() = TailnetOutboundKey(name, provider)
}

internal const val MihomoTailscaleProxyType = "tailscale"

/** Extracts tailscale outbounds from the top-level `proxies` list of a parsed profile. */
internal fun Map<*, *>.mihomoTailscaleOutbounds(): List<MihomoTailscaleOutbound> {
    val proxies = this["proxies"] as? List<*> ?: return emptyList()
    return proxies.mapNotNull { entry ->
        val proxy = entry as? Map<*, *> ?: return@mapNotNull null
        val type = (proxy["type"] as? String)?.trim()?.lowercase()
        if (type != MihomoTailscaleProxyType) return@mapNotNull null
        val name = (proxy["name"] as? String)?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        MihomoTailscaleOutbound(
            name = name,
            hostname = proxy.stringOrNull("hostname"),
            stateDir = proxy.stringOrNull("state-dir"),
            hasAuthKey = proxy.stringOrNull("auth-key") != null,
            ephemeral = proxy["ephemeral"] == true,
            usesCustomControlUrl = proxy.stringOrNull("control-url") != null,
            controlUrlHost = proxy.stringOrNull("control-url")?.controlUrlHostOrNull(),
            inboundForwards = proxy.inboundForwards(),
        )
    }
}

private fun Map<*, *>.inboundForwards(): List<TailnetInboundForward> {
    if (!containsKey("inbound-forwards")) return emptyList()
    val entries = this["inbound-forwards"] as? List<*>
        ?: return listOf(
            TailnetInboundForward(
                name = "inbound-forwards",
                network = "tcp",
                listen = 0,
                target = "",
                error = "inbound-forwards must be a list",
            ),
        )
    return entries.mapIndexed { index, raw ->
        val value = raw as? Map<*, *>
        if (value == null) {
            return@mapIndexed TailnetInboundForward("entry-$index", "tcp", 0, "", "inbound-forwards entry $index must be an object")
        }
        val listenRaw = value["listen"]
        val listen = when (listenRaw) {
            is Number -> listenRaw.toInt().takeIf { listenRaw.toDouble() == it.toDouble() }
            is String -> listenRaw.trim().toIntOrNull()
            else -> null
        }
        val target = (value["target"] as? String)?.trim().orEmpty()
        val network = (value["network"] as? String)?.trim()?.ifEmpty { "tcp" } ?: "tcp"
        val name = (value["name"] as? String)?.trim()?.takeIf(String::isNotEmpty) ?: listen?.toString() ?: "entry-$index"
        val errors = buildList {
            if (listen == null) add("listen must be an integer")
            else if (listen !in 1..65535) add("listen must be between 1 and 65535")
            if (target.isEmpty()) add("target is required")
            if (network.lowercase() != "tcp") add("network must be tcp")
        }
        TailnetInboundForward(name, network, listen ?: 0, target, errors.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }
}

/**
 * Parses profile YAML and returns its top-level tailscale outbounds, or an empty list for
 * non-object or invalid YAML. Outbounds of proxy providers and override scripts are not visible
 * here; the running core reports them (see [parseTailscaleCoreStatuses]), so this is only the
 * fallback while the core cannot.
 */
internal fun parseMihomoTailscaleOutbounds(profileYaml: String): List<MihomoTailscaleOutbound> {
    val escaped = profileYaml.escapeSupplementaryYamlCodePoints()
    val root = runCatching {
        Load(MihomoYamlLoadSettings).loadFromString(escaped.value) as? Map<*, *>
    }.getOrNull() ?: return emptyList()
    return root.mihomoTailscaleOutbounds().map { outbound ->
        outbound.copy(
            name = escaped.restore(outbound.name),
            hostname = outbound.hostname?.let(escaped::restore),
            stateDir = outbound.stateDir?.let(escaped::restore),
        )
    }
}

private fun Map<*, *>.stringOrNull(key: String): String? =
    (this[key] as? String)?.trim()?.takeIf(String::isNotEmpty)

private fun String.controlUrlHostOrNull(): String? =
    runCatching { java.net.URI(this).host }.getOrNull()?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
