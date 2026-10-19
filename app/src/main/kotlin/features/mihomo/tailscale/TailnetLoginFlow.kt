// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.mihomo.tailscale

import engine.mihomo.tailscale.MihomoTailscaleSecretRequiredException
import engine.mihomo.tailscale.MihomoTailscaleUnsupportedException
import engine.mihomo.tailscale.TailscaleBackendState
import engine.mihomo.tailscale.TailscaleStatusQuery
import engine.mihomo.tailscale.TailscaleStatusUnavailableReason
import engine.mihomo.tailscale.validatedTailscaleLoginUri
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/** Core operations the login flow needs; implemented by the runtime repository. */
internal interface TailnetControlActions {
    suspend fun statuses(): TailscaleStatusQuery
    suspend fun startLogin(outboundName: String): Result<Unit>
    suspend fun loginUrl(outboundName: String): Result<String>
    suspend fun logout(outboundName: String): Result<Unit>
}

internal sealed interface TailnetLoginOutcome {
    /** Open [uri] with an ACTION_VIEW intent. The URL is secret: [toString] never prints it. */
    class OpenLoginPage(val uri: URI) : TailnetLoginOutcome {
        override fun toString(): String = "OpenLoginPage(<redacted-login-url>)"
    }

    data object AlreadyRunning : TailnetLoginOutcome
    data object MachineAuthorizationRequired : TailnetLoginOutcome
    data class Failed(val reason: TailnetActionFailure) : TailnetLoginOutcome
}

internal enum class TailnetActionFailure {
    CoreApiMissing,
    NotInRunningConfig,
    SecretRequired,
    InvalidLoginUrl,
    TimedOut,
    ActionFailed,
}

/**
 * Starts an interactive login for [outboundName] and waits until the core offers a login URL,
 * which is fetched on demand, validated against [allowedLoginHosts] and returned in memory only.
 */
internal suspend fun runTailnetLogin(
    actions: TailnetControlActions,
    outboundName: String,
    allowedLoginHosts: Set<String>,
    pollInterval: Duration = 750.milliseconds,
    timeout: Duration = 45.seconds,
): TailnetLoginOutcome {
    actions.startLogin(outboundName).onFailure { return TailnetLoginOutcome.Failed(it.toActionFailure()) }
    val attempts = (timeout / pollInterval).toInt().coerceAtLeast(1)
    repeat(attempts) {
        when (val query = actions.statuses()) {
            is TailscaleStatusQuery.Unavailable -> if (query.reason == TailscaleStatusUnavailableReason.CoreApiMissing) {
                return TailnetLoginOutcome.Failed(TailnetActionFailure.CoreApiMissing)
            }
            is TailscaleStatusQuery.Available -> when (val state = query.states[outboundName]) {
                null -> return TailnetLoginOutcome.Failed(TailnetActionFailure.NotInRunningConfig)
                is TailscaleBackendState.Running -> return TailnetLoginOutcome.AlreadyRunning
                is TailscaleBackendState.NeedsLogin -> when {
                    state.machineAuthorization -> return TailnetLoginOutcome.MachineAuthorizationRequired
                    state.loginUrlAvailable -> return openLoginPage(actions, outboundName, allowedLoginHosts)
                }
                else -> Unit
            }
        }
        delay(pollInterval)
    }
    return TailnetLoginOutcome.Failed(TailnetActionFailure.TimedOut)
}

private suspend fun openLoginPage(
    actions: TailnetControlActions,
    outboundName: String,
    allowedLoginHosts: Set<String>,
): TailnetLoginOutcome {
    val url = actions.loginUrl(outboundName).getOrElse { return TailnetLoginOutcome.Failed(it.toActionFailure()) }
    val uri = validatedTailscaleLoginUri(url, allowedLoginHosts)
        ?: return TailnetLoginOutcome.Failed(TailnetActionFailure.InvalidLoginUrl)
    return TailnetLoginOutcome.OpenLoginPage(uri)
}

internal suspend fun runTailnetLogout(actions: TailnetControlActions, outboundName: String): TailnetActionFailure? =
    actions.logout(outboundName).exceptionOrNull()?.toActionFailure()

private fun Throwable.toActionFailure(): TailnetActionFailure = when (this) {
    is MihomoTailscaleUnsupportedException -> TailnetActionFailure.CoreApiMissing
    is MihomoTailscaleSecretRequiredException -> TailnetActionFailure.SecretRequired
    else -> TailnetActionFailure.ActionFailed
}
