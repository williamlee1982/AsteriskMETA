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
        /** Null means the core predates inbound-forward status support. */
        val inboundForwards: List<TailscaleInboundForwardStatus>? = null,
    ) : TailscaleBackendState

    /** Use [tailscaleBackendError] so the message is always redacted. */
    data class Error(val redactedMessage: String) : TailscaleBackendState
}

internal enum class TailscaleInboundForwardState { Listening, Waiting, Error }

internal data class TailscaleInboundForwardStatus(
    val name: String,
    val network: String,
    val listen: Int,
    val target: String,
    val state: TailscaleInboundForwardState,
    val error: String? = null,
    val activeConns: Int = 0,
    val totalConns: Int = 0,
)

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

/**
 * Identifies a tailscale outbound of the running core. Mihomo allows the same name in several
 * proxy providers, so the provider is part of the identity; null means a top-level proxy.
 */
internal data class TailnetOutboundKey(val name: String, val provider: String? = null)

/** One outbound reported by the running core. */
internal data class TailscaleCoreOutbound(
    val key: TailnetOutboundKey,
    val state: TailscaleBackendState,
    /** Effective, non-secret configuration; null when the core does not report it. */
    val config: MihomoTailscaleOutbound?,
)

/** One status poll of every tailscale outbound in the running core. */
internal sealed interface TailscaleStatusQuery {
    /** [outbounds] is the running core's effective list, including proxy-provider members. */
    data class Available(val outbounds: List<TailscaleCoreOutbound>) : TailscaleStatusQuery {
        val states: Map<TailnetOutboundKey, TailscaleBackendState> by lazy { outbounds.associate { it.key to it.state } }
    }

    data class Unavailable(val reason: TailscaleStatusUnavailableReason) : TailscaleStatusQuery
}

/** Status of [outbound] within this poll. */
internal fun TailscaleStatusQuery.resultFor(outbound: TailnetOutboundKey): TailscaleStatusResult = when (this) {
    is TailscaleStatusQuery.Unavailable -> TailscaleStatusResult.Unavailable(reason)
    is TailscaleStatusQuery.Available -> states[outbound]?.let(TailscaleStatusResult::Available)
        ?: TailscaleStatusResult.Unavailable(TailscaleStatusUnavailableReason.NotInRunningConfig)
}

internal const val MaxErrorMessageLength = 240
