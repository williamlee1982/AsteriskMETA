// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

/**
 * Typed Tailscale backend state of one Mihomo tailscale outbound.
 *
 * This is the app-side contract for MetaCubeX/mihomo#3203. A login URL is deliberately not part
 * of the model: [NeedsLogin] only says whether the core offered one. Opening it must go through a
 * dedicated, user-initiated action that fetches the URL on demand and never persists or logs it.
 */
internal sealed interface TailscaleBackendState {
    data object Stopped : TailscaleBackendState
    data object Starting : TailscaleBackendState
    data class NeedsLogin(val loginUrlAvailable: Boolean, val machineAuthorization: Boolean = false) : TailscaleBackendState
    data class Running(val tailnetAddresses: List<String>, val hostname: String?) : TailscaleBackendState

    /** Use [tailscaleBackendError] so the message is always redacted. */
    data class Error(val redactedMessage: String) : TailscaleBackendState
}

internal fun tailscaleBackendError(message: String): TailscaleBackendState.Error =
    TailscaleBackendState.Error(redactTailnetSecrets(message).take(MaxErrorMessageLength))

/**
 * Maps a Tailscale `ipn.State` name, as a future structured core API would report it, to the
 * app model. Unknown names map to [TailscaleBackendState.Error] instead of guessing.
 */
internal fun tailscaleBackendStateFromIpn(
    ipnState: String,
    loginUrlAvailable: Boolean = false,
    tailnetAddresses: List<String> = emptyList(),
    hostname: String? = null,
): TailscaleBackendState = when (ipnState.trim()) {
    "NoState", "Starting" -> TailscaleBackendState.Starting
    "Stopped" -> TailscaleBackendState.Stopped
    "NeedsLogin" -> TailscaleBackendState.NeedsLogin(loginUrlAvailable)
    "NeedsMachineAuth" -> TailscaleBackendState.NeedsLogin(loginUrlAvailable = false, machineAuthorization = true)
    "Running" -> TailscaleBackendState.Running(tailnetAddresses, hostname)
    else -> tailscaleBackendError("Unknown Tailscale backend state")
}

internal enum class TailscaleStatusUnavailableReason {
    /** The bundled AndroidLibClashLite/Mihomo core exposes no typed Tailscale state API. */
    CoreApiMissing,

    /** The proxy service is not running, so no core instance owns the outbound. */
    ProxyNotRunning,
}

internal sealed interface TailscaleStatusResult {
    data class Available(val state: TailscaleBackendState) : TailscaleStatusResult
    data class Unavailable(val reason: TailscaleStatusUnavailableReason) : TailscaleStatusResult
}

/**
 * Source of Tailscale state for a named outbound. Implementations must use a structured core API
 * (Go binding export or Mihomo controller endpoint); scraping core logs is not an implementation.
 */
internal fun interface TailscaleStatusSource {
    fun query(outboundName: String, proxyRunning: Boolean): TailscaleStatusResult
}

/**
 * Production source for the pinned core. AndroidLibClashLite v1.19.32-android.4 exports no
 * Tailscale state (see docs/tailnet/README.md), so a running core reports [CoreApiMissing].
 * Replace this object once the binding exposes typed state; callers need no change.
 */
internal object BundledCoreTailscaleStatusSource : TailscaleStatusSource {
    override fun query(outboundName: String, proxyRunning: Boolean): TailscaleStatusResult =
        if (!proxyRunning) {
            TailscaleStatusResult.Unavailable(TailscaleStatusUnavailableReason.ProxyNotRunning)
        } else {
            TailscaleStatusResult.Unavailable(TailscaleStatusUnavailableReason.CoreApiMissing)
        }
}

private const val MaxErrorMessageLength = 240
