// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo

import org.junit.Assert.assertThrows
import org.junit.Test

/** M2: `dns-out` is reserved for the DNS outbound used by managed hijack rules. */
class DnsOutReservedNameTest {
    @Test
    fun dnsTypedOrAbsentIsAccepted() {
        requireReservedDnsOutName(listOf(mapOf("name" to "dns-out", "type" to "dns")), null)
        requireReservedDnsOutName(listOf(mapOf("name" to "a", "type" to "ss")), listOf(mapOf("name" to "g")))
        requireReservedDnsOutName(null, null)
    }

    @Test
    fun nonDnsProxyNamedDnsOutIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            requireReservedDnsOutName(listOf(mapOf("name" to "dns-out", "type" to "ss")), null)
        }
    }

    @Test
    fun groupNamedDnsOutIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            requireReservedDnsOutName(null, listOf(mapOf("name" to "dns-out", "type" to "select")))
        }
    }
}
