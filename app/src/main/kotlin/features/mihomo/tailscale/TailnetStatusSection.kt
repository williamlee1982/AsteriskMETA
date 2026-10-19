// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.mihomo.tailscale

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import engine.mihomo.tailscale.MihomoTailscaleOutbound
import engine.mihomo.tailscale.TailscaleBackendState
import engine.mihomo.tailscale.TailscaleStatusQuery
import engine.mihomo.tailscale.TailscaleStatusUnavailableReason
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ui.components.WarningConfirmDialog

/**
 * Dashboard Tailnet card with live state, login and logout. Status is polled from the core's
 * structured API while the card is shown; the login URL is only held in memory long enough to
 * hand it to the browser.
 */
@Composable
internal fun TailnetStatusSection(
    appState: AppState,
    outbounds: List<MihomoTailscaleOutbound>,
    modifier: Modifier = Modifier,
) {
    if (outbounds.isEmpty()) return
    val services = LocalAppServices.current
    val context = LocalContext.current
    val latestAppState by rememberUpdatedState(appState)
    val actions = remember(services.mihomoRuntime) {
        RepositoryTailnetControlActions(services.mihomoRuntime) { latestAppState }
    }
    var statuses by remember { mutableStateOf<TailscaleStatusQuery>(NotPolledYet) }
    var refreshTick by remember { mutableStateOf(0) }
    var busyOutbound by remember { mutableStateOf<String?>(null) }
    var pendingLogout by remember { mutableStateOf<TailnetOutboundPresentation?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(appState.proxyRunning, appState.runMode, outbounds, refreshTick) {
        while (isActive) {
            statuses = actions.statuses()
            delay(tailnetPollInterval(statuses))
        }
    }

    val presentation = remember(outbounds, appState.runMode, appState.privateAddressCidrs, statuses) {
        buildTailnetStatusPresentation(
            outbounds = outbounds,
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
            busyOutbound = outbound.name
            scope.launch {
                try {
                    val outcome = runTailnetLogin(actions, outbound.name, outbound.allowedLoginHosts)
                    services.tipNotifier.show(messages.forLogin(outcome, context))
                } finally {
                    busyOutbound = null
                    refreshTick++
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
            busyOutbound = target.name
            scope.launch {
                try {
                    val failure = runTailnetLogout(actions, target.name)
                    services.tipNotifier.show(failure?.let(messages::forFailure) ?: messages.logoutDone)
                } finally {
                    busyOutbound = null
                    refreshTick++
                }
            }
        },
    )
}

private class RepositoryTailnetControlActions(
    private val repository: MihomoRuntimeRepository,
    private val appState: () -> AppState,
) : TailnetControlActions {
    override suspend fun statuses(): TailscaleStatusQuery = repository.tailscaleStatuses(appState())
    override suspend fun startLogin(outboundName: String): Result<Unit> =
        repository.startTailscaleLogin(appState(), outboundName)
    override suspend fun loginUrl(outboundName: String): Result<String> =
        repository.tailscaleLoginUrl(appState(), outboundName)
    override suspend fun logout(outboundName: String): Result<Unit> =
        repository.logoutTailscale(appState(), outboundName)
}

/** Placeholder before the first poll; renders as "not running" without querying the core. */
private val NotPolledYet: TailscaleStatusQuery =
    TailscaleStatusQuery.Unavailable(TailscaleStatusUnavailableReason.ProxyNotRunning)

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
