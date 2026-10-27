// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.network

/**
 * Result of merging a tailnet peer report into **Private addresses**.
 *
 * [managedCidrs] are the host entries this sync owns. Everything else in
 * [privateAddressCidrs] was added by the user and is left untouched, including when a peer
 * with the same address later disappears.
 */
internal data class TailnetPrivateAddressSync(
    val privateAddressCidrs: List<String>,
    val managedCidrs: List<String>,
    /** Accepted tailnet hosts from this report, or null when the report itself is unknown. */
    val acceptedPeers: Int?,
)

/**
 * Merges tailnet peer addresses into the private-address list.
 *
 * - [peerAddresses] null means the core did not report peers (stopped, older core, query
 *   failed). The current list is kept.
 * - An empty report means the node is up and has no other devices, so previously managed
 *   hosts are removed.
 * - Only single hosts inside the Tailscale IPv4 and IPv6 ranges are added. This device's
 *   own addresses ([selfAddresses]) are skipped.
 * - [enabled] false withdraws managed hosts and leaves user entries in place.
 */
internal fun syncTailnetPrivateAddresses(
    enabled: Boolean,
    privateAddressCidrs: List<String>,
    managedCidrs: List<String>,
    peerAddresses: List<String>?,
    selfAddresses: List<String> = emptyList(),
): TailnetPrivateAddressSync {
    val current = privateAddressCidrs.cidrEntries()
    val managedKeys = managedCidrs.cidrEntries().mapTo(HashSet()) { entry -> entry.key }
    val manual = current.filter { entry -> entry.key !in managedKeys }
    if (!enabled) {
        return TailnetPrivateAddressSync(
            privateAddressCidrs = manual.map { entry -> entry.raw },
            managedCidrs = emptyList(),
            acceptedPeers = null,
        )
    }
    if (peerAddresses == null) {
        val stillManaged = current.filter { entry -> entry.key in managedKeys }
        return TailnetPrivateAddressSync(
            privateAddressCidrs = current.map { entry -> entry.raw },
            managedCidrs = stillManaged.map { entry -> entry.raw },
            acceptedPeers = null,
        )
    }
    val manualKeys = manual.mapTo(HashSet()) { entry -> entry.key }
    val selfKeys = selfAddresses.mapNotNullTo(HashSet(), ::canonicalTailnetHostCidrOrNull)
    val accepted = peerAddresses
        .mapNotNull(::canonicalTailnetHostCidrOrNull)
        .filter { cidr -> cidr !in selfKeys }
        .distinct()
    val desired = accepted.filter { cidr -> cidr !in manualKeys }.sortedWith(tailnetHostOrder)
    return TailnetPrivateAddressSync(
        privateAddressCidrs = manual.map { entry -> entry.raw } + desired,
        managedCidrs = desired,
        acceptedPeers = accepted.size,
    )
}

private data class CidrEntry(val raw: String, val key: String)

private fun List<String>.cidrEntries(): List<CidrEntry> {
    val seen = HashSet<String>()
    val entries = mutableListOf<CidrEntry>()
    for (value in this) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) continue
        val key = canonicalIpCidrOrNull(trimmed) ?: continue
        if (!seen.add(key)) continue
        entries += CidrEntry(trimmed, key)
    }
    return entries
}

/** IPv4 hosts first, then IPv6, so a stable poll does not reorder the saved list. */
private val tailnetHostOrder = compareBy<String> { cidr -> ':' in cidr }.thenBy { cidr -> cidr }
