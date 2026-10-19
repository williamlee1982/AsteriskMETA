// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.mihomo.tailscale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.R
import engine.mihomo.tailscale.TailscaleBackendState
import engine.mihomo.tailscale.TailscaleStateDirIssue
import engine.mihomo.tailscale.TailscaleStatusResult
import engine.mihomo.tailscale.TailscaleStatusUnavailableReason
import engine.network.TailnetIpv4Cidr
import ui.components.AsteriskPageCard

@Composable
internal fun TailnetStatusCard(
    presentation: TailnetStatusPresentation,
    modifier: Modifier = Modifier,
    busyOutbound: String? = null,
    onLogIn: (TailnetOutboundPresentation) -> Unit = {},
    onLogOut: (TailnetOutboundPresentation) -> Unit = {},
) {
    AsteriskPageCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.tailnet_card_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            presentation.outbounds.forEachIndexed { index, outbound ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TailnetOutboundRow(
                    outbound = outbound,
                    busy = busyOutbound == outbound.name,
                    actionsEnabled = busyOutbound == null,
                    onLogIn = { onLogIn(outbound) },
                    onLogOut = { onLogOut(outbound) },
                )
            }
            presentation.rootRouting?.let { hint ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TailnetRootRoutingRow(hint)
            }
        }
    }
}

@Composable
private fun TailnetOutboundRow(
    outbound: TailnetOutboundPresentation,
    busy: Boolean,
    actionsEnabled: Boolean,
    onLogIn: () -> Unit,
    onLogOut: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = outbound.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val running = (outbound.status as? TailscaleStatusResult.Available)?.state as? TailscaleBackendState.Running
        (running?.hostname ?: outbound.hostname)?.let { hostname ->
            Text(
                text = stringResource(R.string.tailnet_outbound_hostname, hostname),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = tailnetStatusText(outbound.status),
            style = MaterialTheme.typography.bodyMedium,
            color = if (outbound.status.isError()) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        running?.dnsName?.let { dnsName ->
            Text(
                text = stringResource(R.string.tailnet_outbound_dns_name, dnsName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        outbound.stateDirIssues.forEach { issue ->
            Text(
                text = tailnetStateDirIssueText(issue),
                style = MaterialTheme.typography.bodySmall,
                color = if (issue == TailscaleStateDirIssue.ImplicitDefault) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        TailnetOutboundActionButton(outbound.action, busy, actionsEnabled, onLogIn, onLogOut)
    }
}

@Composable
private fun TailnetOutboundActionButton(
    action: TailnetOutboundAction,
    busy: Boolean,
    enabled: Boolean,
    onLogIn: () -> Unit,
    onLogOut: () -> Unit,
) {
    if (busy) {
        Text(
            text = stringResource(R.string.tailnet_action_in_progress),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    when (action) {
        TailnetOutboundAction.None -> Unit
        TailnetOutboundAction.LogIn -> FilledTonalButton(onClick = onLogIn, enabled = enabled) {
            Text(stringResource(R.string.tailnet_action_log_in))
        }
        TailnetOutboundAction.LogOut -> OutlinedButton(onClick = onLogOut, enabled = enabled) {
            Text(stringResource(R.string.tailnet_action_log_out))
        }
    }
}

@Composable
private fun TailnetRootRoutingRow(hint: TailnetRootRoutingHint) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (hint.hostOverrideCount == 0) {
                stringResource(R.string.tailnet_root_no_host_override, TailnetIpv4Cidr)
            } else {
                stringResource(R.string.tailnet_root_host_overrides, hint.hostOverrideCount)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        hint.broadOverrides.forEach { cidr ->
            Text(
                text = stringResource(R.string.tailnet_root_broad_override, cidr),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun tailnetStatusText(status: TailscaleStatusResult): String = when (status) {
    is TailscaleStatusResult.Unavailable -> when (status.reason) {
        TailscaleStatusUnavailableReason.CoreApiMissing -> stringResource(R.string.tailnet_status_core_api_missing)
        TailscaleStatusUnavailableReason.ProxyNotRunning -> stringResource(R.string.tailnet_status_proxy_not_running)
        TailscaleStatusUnavailableReason.NotInRunningConfig -> stringResource(R.string.tailnet_status_not_in_running_config)
        TailscaleStatusUnavailableReason.QueryFailed -> stringResource(R.string.tailnet_status_query_failed)
        TailscaleStatusUnavailableReason.Checking -> stringResource(R.string.tailnet_status_checking)
    }
    is TailscaleStatusResult.Available -> when (val state = status.state) {
        TailscaleBackendState.Idle -> stringResource(R.string.tailnet_status_idle)
        TailscaleBackendState.Stopped -> stringResource(R.string.tailnet_status_stopped)
        TailscaleBackendState.Starting -> stringResource(R.string.tailnet_status_starting)
        is TailscaleBackendState.NeedsLogin -> if (state.machineAuthorization) {
            stringResource(R.string.tailnet_status_needs_machine_auth)
        } else if (state.loginUrlAvailable) {
            stringResource(R.string.tailnet_status_login_available)
        } else {
            stringResource(R.string.tailnet_status_needs_login)
        }
        is TailscaleBackendState.Running -> if (state.tailnetAddresses.isEmpty()) {
            stringResource(R.string.tailnet_status_running)
        } else {
            stringResource(R.string.tailnet_status_running_addresses, state.tailnetAddresses.joinToString())
        }
        is TailscaleBackendState.Error -> stringResource(R.string.tailnet_status_error, state.redactedMessage)
    }
}

@Composable
private fun tailnetStateDirIssueText(issue: TailscaleStateDirIssue): String = when (issue) {
    TailscaleStateDirIssue.ImplicitDefault -> stringResource(R.string.tailnet_state_dir_implicit_default)
    TailscaleStateDirIssue.SharedWithAnotherOutbound -> stringResource(R.string.tailnet_state_dir_shared)
    TailscaleStateDirIssue.OutsideMihomoHome -> stringResource(R.string.tailnet_state_dir_outside_home)
    TailscaleStateDirIssue.ForeignAppData -> stringResource(R.string.tailnet_state_dir_foreign_app)
    TailscaleStateDirIssue.SharedStorage -> stringResource(R.string.tailnet_state_dir_shared_storage)
}

private fun TailscaleStatusResult.isError(): Boolean =
    this is TailscaleStatusResult.Available && state is TailscaleBackendState.Error
