// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

/** A top-level profile outbound fixture without secrets. */
internal fun tailnetOutbound(name: String, stateDir: String? = null, provider: String? = null) =
    MihomoTailscaleOutbound(
        name = name,
        hostname = null,
        stateDir = stateDir,
        hasAuthKey = false,
        ephemeral = false,
        usesCustomControlUrl = false,
        provider = provider,
    )

/** A core poll reporting top-level outbounds by name, without config. */
internal fun coreStates(vararg states: Pair<String, TailscaleBackendState>): TailscaleStatusQuery.Available =
    TailscaleStatusQuery.Available(
        states.map { (name, state) -> TailscaleCoreOutbound(TailnetOutboundKey(name), state, config = null) },
    )
