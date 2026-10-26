// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TailnetPrivateAddressSyncTest {
    @Test
    fun addsIpv4AndIpv6HostsWithoutTouchingManualEntries() {
        val merged = syncTailnetPrivateAddresses(
            enabled = true,
            privateAddressCidrs = listOf(" 10.0.0.0/8 ", "10.0.0.0/8", "192.168.1.10/32"),
            managedCidrs = emptyList(),
            peerAddresses = listOf(
                "fd7a:115c:a1e0::2",
                "100.101.102.103",
                "100.101.102.103",
                "8.8.8.8",
                "100.64.0.0/10",
                "fd00::1",
                "not-an-address",
            ),
            selfAddresses = listOf("100.101.102.10"),
        )

        assertEquals(
            listOf("10.0.0.0/8", "192.168.1.10/32", "100.101.102.103/32", "fd7a:115c:a1e0::2/128"),
            merged.privateAddressCidrs,
        )
        assertEquals(listOf("100.101.102.103/32", "fd7a:115c:a1e0::2/128"), merged.managedCidrs)
        assertEquals(2, merged.acceptedPeers)
    }

    @Test
    fun doesNotDuplicateAManualHostWrittenInAnotherForm() {
        val merged = syncTailnetPrivateAddresses(
            enabled = true,
            privateAddressCidrs = listOf("FD7A:115C:A1E0:0:0:0:0:5/128", "100.64.0.0/10"),
            managedCidrs = emptyList(),
            peerAddresses = listOf("fd7a:115c:a1e0::5", "100.101.102.103"),
        )

        assertEquals(
            listOf("FD7A:115C:A1E0:0:0:0:0:5/128", "100.64.0.0/10", "100.101.102.103/32"),
            merged.privateAddressCidrs,
        )
        assertEquals(listOf("100.101.102.103/32"), merged.managedCidrs)
        assertEquals(2, merged.acceptedPeers)
    }

    @Test
    fun removesManagedHostsThatLeftAndKeepsManualOnes() {
        val merged = syncTailnetPrivateAddresses(
            enabled = true,
            privateAddressCidrs = listOf(
                "10.0.0.0/8",
                "100.101.102.103/32",
                "100.101.102.104/32",
                "fd7a:115c:a1e0::9/128",
            ),
            managedCidrs = listOf("100.101.102.104/32", "fd7a:115c:a1e0::9/128"),
            peerAddresses = listOf("100.101.102.104"),
        )

        assertEquals(listOf("10.0.0.0/8", "100.101.102.103/32", "100.101.102.104/32"), merged.privateAddressCidrs)
        assertEquals(listOf("100.101.102.104/32"), merged.managedCidrs)
    }

    @Test
    fun emptyPeerReportClearsManagedHosts() {
        val merged = syncTailnetPrivateAddresses(
            enabled = true,
            privateAddressCidrs = listOf("10.0.0.0/8", "100.101.102.104/32"),
            managedCidrs = listOf("100.101.102.104/32"),
            peerAddresses = emptyList(),
        )

        assertEquals(listOf("10.0.0.0/8"), merged.privateAddressCidrs)
        assertEquals(emptyList<String>(), merged.managedCidrs)
        assertEquals(0, merged.acceptedPeers)
    }

    @Test
    fun unknownPeerReportLeavesTheListAlone() {
        val current = listOf("10.0.0.0/8", "100.101.102.104/32")
        val managed = listOf("100.101.102.104/32")
        val merged = syncTailnetPrivateAddresses(
            enabled = true,
            privateAddressCidrs = current,
            managedCidrs = managed,
            peerAddresses = null,
        )

        assertEquals(current, merged.privateAddressCidrs)
        assertEquals(managed, merged.managedCidrs)
        assertNull(merged.acceptedPeers)
    }

    @Test
    fun disablingSyncWithdrawsOnlyManagedHosts() {
        val merged = syncTailnetPrivateAddresses(
            enabled = false,
            privateAddressCidrs = listOf("10.0.0.0/8", "100.101.102.103/32", "100.101.102.104/32"),
            managedCidrs = listOf("100.101.102.104/32"),
            peerAddresses = listOf("100.101.102.104", "fd7a:115c:a1e0::2"),
        )

        assertEquals(listOf("10.0.0.0/8", "100.101.102.103/32"), merged.privateAddressCidrs)
        assertEquals(emptyList<String>(), merged.managedCidrs)
        assertNull(merged.acceptedPeers)
    }

    @Test
    fun skipsThisDevicesOwnAddresses() {
        val merged = syncTailnetPrivateAddresses(
            enabled = true,
            privateAddressCidrs = emptyList(),
            managedCidrs = emptyList(),
            peerAddresses = listOf("100.101.102.10", "FD7A:115C:A1E0::1", "100.101.102.103"),
            selfAddresses = listOf("100.101.102.10", "fd7a:115c:a1e0:0:0:0:0:1"),
        )

        assertEquals(listOf("100.101.102.103/32"), merged.privateAddressCidrs)
        assertEquals(1, merged.acceptedPeers)
    }
}
