// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.mihomo.tailscale

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.AppState
import app.LocalAppServices
import app.R
import engine.mihomo.mihomoHomeDirectory
import engine.mihomo.runtime.MihomoRuntimeRepository
import engine.mihomo.tailscale.TailnetOutboundKey
import engine.mihomo.tailscale.TailscaleCoreOutbound
import engine.mihomo.tailscale.TailscaleBackendState
import engine.mihomo.tailscale.TailscaleStatusQuery
import engine.mihomo.tailscale.TailscaleStatusUnavailableReason
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ui.components.WarningConfirmDialog

/** Latest Tailscale poll of the running core, shared by the dashboard and the Tailnet card. */
@Stable
internal class TailnetStatusState(initial: TailscaleStatusQuery) {
    private var current by mutableStateOf(initial)

    var statuses: TailscaleStatusQuery
        get() = current
        internal set(value) {
            if (value is TailscaleStatusQuery.Available) lastReported = value.outbounds
            current = value
        }

    /** Outbounds of the last successful poll since the service started; see [resolveTailnetCardOutbounds]. */
    var lastReported: List<TailscaleCoreOutbound>? by mutableStateOf(null)
        private set
    internal var refreshTick by mutableIntStateOf(0)
        private set

    /** Polls again now, for example after a login or logout. */
    fun refresh() {
        refreshTick++
    }
}

/**
 * Polls the running core's Tailscale state while the dashboard is shown, whether or not the
 * profile file declares a tailscale outbound: proxy providers and override scripts can add one,
 * and a provider refresh can add or remove one at any time.
 */
@Composable
internal fun rememberTailnetStatusState(appState: AppState): TailnetStatusState {
    val services = LocalAppServices.current
    val latestAppState by rememberUpdatedState(appState)
    val state = remember(appState.proxyRunning) { TailnetStatusState(tailnetStatusPlaceholder(appState.proxyRunning)) }
    LaunchedEffect(state, appState.proxyRunning, appState.runMode, state.refreshTick) {
        if (!appState.proxyRunning) return@LaunchedEffect
        while (isActive) {
            state.statuses = services.mihomoRuntime.tailscaleStatuses(latestAppState)
            delay(tailnetPollInterval(state.statuses))
        }
    }
    return state
}

/**
 * Dashboard Tailnet card with live state, login and logout for the outbounds chosen by
 * [resolveTailnetCardOutbounds]. The login URL is only held in memory long enough to hand it to
 * the browser.
 */
@Composable
internal fun TailnetStatusSection(
    appState: AppState,
    card: TailnetCardOutbounds,
    statusState: TailnetStatusState,
    modifier: Modifier = Modifier,
) {
    if (card.outbounds.isEmpty()) return
    val services = LocalAppServices.current
    val context = LocalContext.current
    val latestAppState by rememberUpdatedState(appState)
    val actions = remember(services.mihomoRuntime, statusState) {
        RepositoryTailnetControlActions(services.mihomoRuntime, statusState) { latestAppState }
    }
    var busyOutbound by remember { mutableStateOf<TailnetOutboundKey?>(null) }
    var pendingLogout by remember { mutableStateOf<TailnetOutboundPresentation?>(null) }
    val scope = rememberCoroutineScope()
    val statuses = statusState.statuses

    val presentation = remember(card, appState.runMode, appState.privateAddressCidrs, statuses) {
        buildTailnetStatusPresentation(
            card = card,
            runMode = appState.runMode,
            privateAddressCidrs = appState.privateAddressCidrs,
            mihomoHomeDirectory = mihomoHomeDirectory(context.applicationContext.filesDir).absolutePath,
            ownPackageName = context.packageName,
            statuses = statuses,
        )
    }

    val messages = TailnetMessages.resolve()
    TailnetStatusCard(
        presentation = presentation,
        modifier = modifier,
        busyOutbound = busyOutbound,
        onLogIn = { outbound ->
            if (busyOutbound != null) return@TailnetStatusCard
            busyOutbound = outbound.key
            scope.launch {
                try {
                    val outcome = runTailnetLogin(actions, outbound.key, outbound.allowedLoginHosts)
                    services.tipNotifier.show(messages.forLogin(outcome, context))
                } finally {
                    busyOutbound = null
                    statusState.refresh()
                }
            }
        },
        onLogOut = { outbound -> pendingLogout = outbound },
    )

    val logoutTarget = pendingLogout
    WarningConfirmDialog(
        show = logoutTarget != null,
        title = stringResource(R.string.tailnet_logout_title),
        summary = stringResource(R.string.tailnet_logout_message, logoutTarget?.name.orEmpty()),
        dismissText = stringResource(R.string.common_cancel),
        confirmText = stringResource(R.string.tailnet_logout_confirm),
        onDismissRequest = { pendingLogout = null },
        onConfirm = {
            val target = logoutTarget ?: return@WarningConfirmDialog
            pendingLogout = null
            busyOutbound = target.key
            scope.launch {
                try {
                    val failure = runTailnetLogout(actions, target.key)
                    services.tipNotifier.show(failure?.let(messages::forFailure) ?: messages.logoutDone)
                } finally {
                    busyOutbound = null
                    statusState.refresh()
                }
            }
        },
    )
}

private class RepositoryTailnetControlActions(
    private val repository: MihomoRuntimeRepository,
    private val statusState: TailnetStatusState,
    private val appState: () -> AppState,
) : TailnetControlActions {
    // The login flow's own polls also keep the card current.
    override suspend fun statuses(): TailscaleStatusQuery =
        repository.tailscaleStatuses(appState()).also { statusState.statuses = it }
    override suspend fun startLogin(outbound: TailnetOutboundKey): Result<Unit> =
        repository.startTailscaleLogin(appState(), outbound)
    override suspend fun loginUrl(outbound: TailnetOutboundKey): Result<String> =
        repository.tailscaleLoginUrl(appState(), outbound)
    override suspend fun logout(outbound: TailnetOutboundKey): Result<Unit> =
        repository.logoutTailscale(appState(), outbound)
}

/** Shown before the first poll returns. */
internal fun tailnetStatusPlaceholder(proxyRunning: Boolean): TailscaleStatusQuery = TailscaleStatusQuery.Unavailable(
    if (proxyRunning) TailscaleStatusUnavailableReason.Checking else TailscaleStatusUnavailableReason.ProxyNotRunning,
)

/** Polls quickly while a state is transitional and slowly once it is settled. */
internal fun tailnetPollInterval(statuses: TailscaleStatusQuery): Duration = when (statuses) {
    is TailscaleStatusQuery.Unavailable -> 10.seconds
    is TailscaleStatusQuery.Available -> if (
        statuses.states.values.any { it == TailscaleBackendState.Starting || it is TailscaleBackendState.NeedsLogin }
    ) {
        2.seconds
    } else {
        10.seconds
    }
}

private class TailnetMessages(
    val loginOpened: String,
    val alreadyRunning: String,
    val machineAuth: String,
    val logoutDone: String,
    val noBrowser: String,
    private val failures: Map<TailnetActionFailure, String>,
) {
    fun forFailure(failure: TailnetActionFailure): String = failures.getValue(failure)

    fun forLogin(outcome: TailnetLoginOutcome, context: Context): String = when (outcome) {
        is TailnetLoginOutcome.OpenLoginPage -> if (openLoginPage(context, outcome)) loginOpened else noBrowser
        TailnetLoginOutcome.AlreadyRunning -> alreadyRunning
        TailnetLoginOutcome.MachineAuthorizationRequired -> machineAuth
        is TailnetLoginOutcome.Failed -> forFailure(outcome.reason)
    }

    companion object {
        @Composable
        fun resolve() = TailnetMessages(
            loginOpened = stringResource(R.string.tailnet_login_opened),
            alreadyRunning = stringResource(R.string.tailnet_login_already_running),
            machineAuth = stringResource(R.string.tailnet_login_machine_auth),
            logoutDone = stringResource(R.string.tailnet_logout_done),
            noBrowser = stringResource(R.string.tailnet_error_no_browser),
            failures = mapOf(
                TailnetActionFailure.CoreApiMissing to stringResource(R.string.tailnet_error_core_api_missing),
                TailnetActionFailure.NotInRunningConfig to stringResource(R.string.tailnet_error_not_in_running_config),
                TailnetActionFailure.SecretRequired to stringResource(R.string.tailnet_error_secret_required),
                TailnetActionFailure.InvalidLoginUrl to stringResource(R.string.tailnet_error_invalid_login_url),
                TailnetActionFailure.TimedOut to stringResource(R.string.tailnet_error_timed_out),
                TailnetActionFailure.ActionFailed to stringResource(R.string.tailnet_error_action_failed),
            ),
        )
    }
}

/** Hands the validated URL to the browser; it is never logged, persisted or shown in a toast. */
private fun openLoginPage(context: Context, outcome: TailnetLoginOutcome.OpenLoginPage): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(outcome.uri.toASCIIString()))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
