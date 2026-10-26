// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

/**
 * Peer addresses observed from one status poll.
 *
 * [peerAddresses] is null when no Running outbound reported a peer list. That includes a core
 * which predates the field, a node that is not up yet, and a failed query. An empty list is a
 * real report: the node is up and the netmap has no other devices.
 */
internal data class TailnetPeerSnapshot(
    val selfAddresses: List<String>,
    val peerAddresses: List<String>?,
)

internal fun tailnetPeerSnapshot(query: TailscaleStatusQuery): TailnetPeerSnapshot {
    if (query !is TailscaleStatusQuery.Available) return TailnetPeerSnapshot(emptyList(), null)
    val running = query.outbounds.mapNotNull { outbound -> outbound.state as? TailscaleBackendState.Running }
    if (running.isEmpty()) return TailnetPeerSnapshot(emptyList(), null)
    val reported = running.mapNotNull { state -> state.peerIps }
    return TailnetPeerSnapshot(
        selfAddresses = running.flatMap { state -> state.tailnetAddresses },
        peerAddresses = reported.takeIf { it.isNotEmpty() }?.flatten(),
    )
}
