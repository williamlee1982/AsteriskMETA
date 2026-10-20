// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo

import app.MihomoOverrideScriptState
import app.MihomoProfileState
import engine.mihomo.tailscale.MihomoTailscaleOutbound
import engine.mihomo.tailscale.mihomoTailscaleOutbounds
import engine.mihomo.tailscale.parseMihomoTailscaleOutbounds
import kotlinx.coroutines.CancellationException
import org.snakeyaml.engine.v2.api.Load

/** Non-secret dashboard fallback, using the same script-before-extraction order as runtime. */
internal fun effectiveMihomoTailscaleOutbounds(
    profileYaml: String,
    profile: MihomoProfileState?,
    scripts: List<MihomoOverrideScriptState>,
    engine: MihomoProfileScriptEngine = QuickJsMihomoProfileScriptEngine,
): List<MihomoTailscaleOutbound> {
    if (profile?.disableOverrides == true) return parseMihomoTailscaleOutbounds(profileYaml)

    return runCatching {
        val escaped = profileYaml.escapeSupplementaryYamlCodePoints()
        val root = Load(MihomoYamlLoadSettings).loadFromString(escaped.value) as? Map<*, *>
            ?: return@runCatching emptyList()
        val rawProfile = linkedMapOf<String, Any?>()
        root.forEach { (key, value) ->
            val name = key as? String ?: return@forEach
            rawProfile[name] = normalizeYamlValue(value)
        }
        rawProfile.applyMihomoProfileScriptOverride(profile, scripts, engine)
            .mihomoTailscaleOutbounds()
            .map { outbound ->
                outbound.copy(
                    name = escaped.restore(outbound.name),
                    hostname = outbound.hostname?.let(escaped::restore),
                    stateDir = outbound.stateDir?.let(escaped::restore),
                )
            }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        parseMihomoTailscaleOutbounds(profileYaml)
    }
}
