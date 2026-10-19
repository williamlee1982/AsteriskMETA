// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.mihomo.tailscale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
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
                TailnetOutboundRow(outbound)
            }
            presentation.rootRouting?.let { hint ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TailnetRootRoutingRow(hint)
            }
        }
    }
}

@Composable
private fun TailnetOutboundRow(outbound: TailnetOutboundPresentation) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = outbound.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        outbound.hostname?.let { hostname ->
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
    }
    is TailscaleStatusResult.Available -> when (val state = status.state) {
        TailscaleBackendState.Stopped -> stringResource(R.string.tailnet_status_stopped)
        TailscaleBackendState.Starting -> stringResource(R.string.tailnet_status_starting)
        is TailscaleBackendState.NeedsLogin -> if (state.machineAuthorization) {
            stringResource(R.string.tailnet_status_needs_machine_auth)
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
