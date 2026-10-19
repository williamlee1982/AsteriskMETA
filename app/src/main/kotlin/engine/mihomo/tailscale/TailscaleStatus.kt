// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

/**
 * Typed Tailscale backend state of one Mihomo tailscale outbound, as reported by the core's
 * `/asterisk/tailscale` API (AndroidLibClashLite with the Tailscale state API).
 *
 * A login URL is deliberately not part of the model: [NeedsLogin] only says whether the core
 * offered one. Opening it goes through a user-initiated action that fetches the URL on demand,
 * keeps it in memory and never persists or logs it.
 */
internal sealed interface TailscaleBackendState {
    /** The outbound exists but tsnet has not started; Mihomo starts it on first use. */
    data object Idle : TailscaleBackendState
    data object Stopped : TailscaleBackendState
    data object Starting : TailscaleBackendState
    data class NeedsLogin(val loginUrlAvailable: Boolean, val machineAuthorization: Boolean = false) : TailscaleBackendState
    data class Running(
        val tailnetAddresses: List<String>,
        val hostname: String?,
        val dnsName: String? = null,
        val magicDnsSuffix: String? = null,
    ) : TailscaleBackendState

    /** Use [tailscaleBackendError] so the message is always redacted. */
    data class Error(val redactedMessage: String) : TailscaleBackendState
}

internal fun tailscaleBackendError(message: String): TailscaleBackendState.Error =
    TailscaleBackendState.Error(redactTailnetSecrets(message).take(MaxErrorMessageLength))

internal enum class TailscaleStatusUnavailableReason {
    /** The running core has no `/asterisk/tailscale` API (for example a custom ROOT core). */
    CoreApiMissing,

    /** The proxy service is not running, so no core instance owns the outbound. */
    ProxyNotRunning,

    /** The running core configuration has no tailscale outbound with this name. */
    NotInRunningConfig,

    /** The core API could not be reached; the message is redacted. */
    QueryFailed,

    /** The first poll has not returned yet. */
    Checking,
}

internal sealed interface TailscaleStatusResult {
    data class Available(val state: TailscaleBackendState) : TailscaleStatusResult
    data class Unavailable(val reason: TailscaleStatusUnavailableReason) : TailscaleStatusResult
}

/** One status poll of every tailscale outbound in the running core. */
internal sealed interface TailscaleStatusQuery {
    data class Available(val states: Map<String, TailscaleBackendState>) : TailscaleStatusQuery
    data class Unavailable(val reason: TailscaleStatusUnavailableReason) : TailscaleStatusQuery
}

/** Status of [outboundName] within this poll. */
internal fun TailscaleStatusQuery.resultFor(outboundName: String): TailscaleStatusResult = when (this) {
    is TailscaleStatusQuery.Unavailable -> TailscaleStatusResult.Unavailable(reason)
    is TailscaleStatusQuery.Available -> states[outboundName]?.let(TailscaleStatusResult::Available)
        ?: TailscaleStatusResult.Unavailable(TailscaleStatusUnavailableReason.NotInRunningConfig)
}

private const val MaxErrorMessageLength = 240
