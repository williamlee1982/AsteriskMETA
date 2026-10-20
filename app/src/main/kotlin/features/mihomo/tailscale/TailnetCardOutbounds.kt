// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.mihomo.tailscale

import engine.mihomo.tailscale.MihomoTailscaleOutbound
import engine.mihomo.tailscale.TailnetOutboundKey
import engine.mihomo.tailscale.TailscaleCoreOutbound
import engine.mihomo.tailscale.TailscaleStatusQuery
import engine.mihomo.tailscale.TailscaleStatusUnavailableReason

/** Where the card learned about an outbound. */
internal enum class TailnetOutboundSource {
    /** Reported by the running core: includes proxy providers and override scripts. */
    RunningCore,

    /** Read from the selected profile after its bound override script: top-level `proxies` only. */
    ProfileFile,
}

internal data class TailnetCardOutbound(
    val outbound: MihomoTailscaleOutbound,
    val source: TailnetOutboundSource,
    /** False when neither the core nor the profile file told us the outbound's options. */
    val configKnown: Boolean = true,
)

internal data class TailnetCardOutbounds(
    val outbounds: List<TailnetCardOutbound>,
    /** Why the profile file is used instead of the running core; null while the core reports. */
    val profileFallback: TailscaleStatusUnavailableReason?,
)

/**
 * Chooses the outbounds the Tailnet card shows.
 *
 * While the core reports its outbounds, its effective list is authoritative: proxy-provider
 * members and outbounds added by override scripts are included and the list follows provider
 * refreshes on the next poll. Top-level profile outbounds the core does not run are kept so the
 * card can say they are not in the running configuration. Otherwise the effective profile is the
 * fallback and [TailnetCardOutbounds.profileFallback] says why.
 */
internal fun resolveTailnetCardOutbounds(
    statuses: TailscaleStatusQuery,
    profileOutbounds: List<MihomoTailscaleOutbound>,
    lastReported: List<TailscaleCoreOutbound>? = null,
): TailnetCardOutbounds {
    val profile = profileOutbounds.map { it.copy(provider = null) }.distinctBy { it.name }
    // A single failed poll of a running core keeps its last list instead of flickering back to
    // the profile file; each row then reports that the core did not respond.
    if (statuses is TailscaleStatusQuery.Unavailable &&
        statuses.reason == TailscaleStatusUnavailableReason.QueryFailed && lastReported != null
    ) {
        return resolveTailnetCardOutbounds(TailscaleStatusQuery.Available(lastReported), profileOutbounds)
    }
    if (statuses !is TailscaleStatusQuery.Available) {
        return TailnetCardOutbounds(
            outbounds = profile.map { TailnetCardOutbound(it, TailnetOutboundSource.ProfileFile) },
            profileFallback = statuses.reasonOrChecking(),
        )
    }
    val profileByKey = profile.associateBy { it.key }
    val running = statuses.outbounds.map { reported ->
        // A core without the config summary: borrow the profile entry it was declared by.
        // The summary never carries inbound-forwards (and the runtime config strips them while
        // the toggle is off), so keep the effective profile's forwards for the toggle and card.
        val declared = profileByKey[reported.key]
        val config = reported.config?.let { summary ->
            if (summary.inboundForwards.isEmpty() && declared != null) {
                summary.copy(inboundForwards = declared.inboundForwards)
            } else {
                summary
            }
        } ?: declared
        TailnetCardOutbound(
            outbound = config ?: bareOutbound(reported.key),
            source = TailnetOutboundSource.RunningCore,
            configKnown = config != null,
        )
    }
    val runningKeys = statuses.outbounds.mapTo(HashSet()) { it.key }
    val notRunning = profile.filter { it.key !in runningKeys }
        .map { TailnetCardOutbound(it, TailnetOutboundSource.ProfileFile) }
    return TailnetCardOutbounds(running + notRunning, profileFallback = null)
}

private fun TailscaleStatusQuery.reasonOrChecking(): TailscaleStatusUnavailableReason =
    (this as? TailscaleStatusQuery.Unavailable)?.reason ?: TailscaleStatusUnavailableReason.Checking

private fun bareOutbound(key: TailnetOutboundKey) = MihomoTailscaleOutbound(
    name = key.name,
    hostname = null,
    stateDir = null,
    hasAuthKey = false,
    ephemeral = false,
    usesCustomControlUrl = false,
    provider = key.provider,
)
