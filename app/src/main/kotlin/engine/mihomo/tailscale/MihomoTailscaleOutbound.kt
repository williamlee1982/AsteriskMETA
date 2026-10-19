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
)

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
        )
    }
}

/**
 * Parses profile YAML and returns its tailscale outbounds, or an empty list for non-object or
 * invalid YAML. Outbounds that only exist in proxy providers or are injected by override scripts
 * are not visible here; see docs/tailnet/README.md for that limitation.
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
