// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo

import app.MihomoOverrideScriptState
import app.MihomoProfileState
import engine.mihomo.tailscale.MihomoTailscaleOutbound
import engine.mihomo.tailscale.parseMihomoTailscaleOutbounds

internal fun effectiveMihomoTailscaleOutbounds(
    profileYaml: String,
    profile: MihomoProfileState?,
    scripts: List<MihomoOverrideScriptState>,
    engine: MihomoProfileScriptEngine,
): List<MihomoTailscaleOutbound> {
    return parseMihomoTailscaleOutbounds(profileYaml)
}
