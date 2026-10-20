// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.network

import java.math.BigInteger
import java.net.InetAddress

/** IPv4 range Tailscale assigns Tailnet addresses from (RFC 6598 shared address space / CGNAT). */
const val TailnetIpv4Cidr = "100.64.0.0/10"

/** IPv6 ULA prefix Tailscale assigns Tailnet addresses from. */
const val TailnetIpv6Cidr = "fd7a:115c:a1e0::/48"

/**
 * Classification of the user's private-address overrides that intersect Tailnet ranges.
 *
 * - [hostOverrides]: single-host entries (`/32` or `/128`) inside a Tailnet range; these are the
 *   recommended way to send selected Tailnet peers through the core in ROOT transparent modes.
 * - [broadOverrides]: wider entries overlapping a Tailnet range. They also capture carrier CGNAT
 *   addresses that are not Tailnet peers, so they deserve a visible warning.
 */
data class TailnetPrivateAddressOverrides(
    val hostOverrides: List<String>,
    val broadOverrides: List<String>,
)

fun tailnetPrivateAddressOverrides(privateAddressCidrs: List<String>): TailnetPrivateAddressOverrides {
    val hosts = mutableListOf<String>()
    val broad = mutableListOf<String>()
    privateAddressCidrs.map(String::trim).filter(String::isNotEmpty).distinct().forEach { entry ->
        val cidr = parseIpCidrOrNull(entry) ?: return@forEach
        val tailnet = if (cidr.bits == 32) TailnetIpv4Range else TailnetIpv6Range
        if (!cidr.overlaps(tailnet)) return@forEach
        if (cidr.prefixLength == cidr.bits && tailnet.contains(cidr)) hosts += entry else broad += entry
    }
    return TailnetPrivateAddressOverrides(hostOverrides = hosts, broadOverrides = broad)
}

/** Returns true when [address] is a literal IPv4/IPv6 address inside a Tailnet range. */
fun isTailnetAddress(address: String): Boolean {
    val trimmed = address.trim()
    val bits = if (":" in trimmed) 128 else 32
    val cidr = parseIpCidrOrNull("$trimmed/$bits") ?: return false
    return (if (bits == 32) TailnetIpv4Range else TailnetIpv6Range).contains(cidr)
}

/**
 * Returns true when the IPv4 FakeIP [pool] intersects the Tailnet CGNAT range. Such a pool would
 * hand out fake addresses that ROOT modes bypass and shadow real Tailnet peer addresses.
 */
fun fakeIpPoolOverlapsTailnet(pool: String): Boolean {
    val cidr = parseIpCidrOrNull(pool) ?: return false
    return cidr.overlaps(TailnetIpv4Range)
}

/** Returns true when [cidr] fully contains [address]; both must be literal and the same family. */
fun cidrContainsAddress(cidr: String, address: String): Boolean {
    val range = parseIpCidrOrNull(cidr) ?: return false
    val trimmed = address.trim()
    val host = parseIpCidrOrNull("$trimmed/${if (":" in trimmed) 128 else 32}") ?: return false
    return range.contains(host)
}

private class IpCidr(val network: BigInteger, val prefixLength: Int, val bits: Int) {
    private val size: BigInteger = BigInteger.ONE.shiftLeft(bits - prefixLength)
    private val last: BigInteger = network + size - BigInteger.ONE

    fun contains(other: IpCidr): Boolean =
        bits == other.bits && other.network >= network && other.last <= last

    fun overlaps(other: IpCidr): Boolean =
        bits == other.bits && network <= other.last && other.network <= last
}

private val TailnetIpv4Range = requireNotNull(parseIpCidrOrNull(TailnetIpv4Cidr))
private val TailnetIpv6Range = requireNotNull(parseIpCidrOrNull(TailnetIpv6Cidr))

private fun parseIpCidrOrNull(value: String): IpCidr? {
    val cidr = parseCidrAddressOrNull(value) ?: return null
    // Validated literals only: InetAddress never performs a DNS lookup for numeric input.
    val bytes = runCatching { InetAddress.getByName(cidr.address).address }.getOrNull() ?: return null
    val bits = bytes.size * 8
    if (cidr.prefixLength !in 0..bits) return null
    val raw = BigInteger(1, bytes)
    val hostBits = bits - cidr.prefixLength
    val network = raw.shiftRight(hostBits).shiftLeft(hostBits)
    return IpCidr(network, cidr.prefixLength, bits)
}
