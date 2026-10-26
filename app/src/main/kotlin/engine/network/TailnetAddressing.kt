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

/**
 * Stable textual form of a CIDR: IPv4 without leading zeros, IPv6 in lowercase RFC 5952 form.
 * Returns null when [value] is not a literal CIDR this app already accepts.
 */
internal fun canonicalIpCidrOrNull(value: String): String? {
    val parsed = parseCidrAddressOrNull(value) ?: return null
    val address = canonicalIpOrNull(parsed.address) ?: return null
    return "$address/${parsed.prefixLength}"
}

/**
 * A single Tailnet host (`/32` or `/128`) inside the Tailscale ranges, or null.
 * [value] may be a bare address or a host CIDR. Wider prefixes are rejected so a peer
 * report cannot install a CGNAT-wide route.
 */
internal fun canonicalTailnetHostCidrOrNull(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || '%' in trimmed) return null
    val cidr = if ('/' in trimmed) {
        canonicalIpCidrOrNull(trimmed)
    } else {
        val address = canonicalIpOrNull(trimmed) ?: return null
        val bits = if (':' in address) 128 else 32
        "$address/$bits"
    } ?: return null
    val prefix = cidr.substringAfter('/').toIntOrNull() ?: return null
    val bits = if (':' in cidr) 128 else 32
    if (prefix != bits) return null
    return cidr.takeIf { isTailnetAddress(it.substringBefore('/')) }
}

internal fun canonicalIpOrNull(address: String): String? = when {
    isIpv4Address(address) -> address.split('.').joinToString(".") { octet -> octet.toInt().toString() }
    isIpv6Address(address) -> canonicalIpv6(address)
    else -> null
}

private fun canonicalIpv6(address: String): String? {
    val groups = expandIpv6(address) ?: return null
    var bestStart = -1
    var bestLength = 0
    var index = 0
    while (index < groups.size) {
        if (groups[index] != 0) {
            index++
            continue
        }
        val start = index
        while (index < groups.size && groups[index] == 0) index++
        val length = index - start
        if (length > bestLength) {
            bestLength = length
            bestStart = start
        }
    }
    val compress = bestLength >= 2
    val formatted = StringBuilder()
    index = 0
    while (index < groups.size) {
        if (compress && index == bestStart) {
            formatted.append("::")
            index += bestLength
            continue
        }
        if (formatted.isNotEmpty() && formatted.last() != ':') formatted.append(':')
        formatted.append(groups[index].toString(16))
        index++
    }
    return formatted.toString().ifEmpty { "::" }
}

private fun expandIpv6(address: String): IntArray? {
    val parts = address.split("::")
    if (parts.size > 2) return null
    val left = ipv6Groups(parts[0]) ?: return null
    if (parts.size == 1) return left.takeIf { it.size == 8 }?.toIntArray()
    val right = ipv6Groups(parts[1]) ?: return null
    val missing = 8 - left.size - right.size
    if (missing < 1) return null
    return (left + List(missing) { 0 } + right).toIntArray()
}

private fun ipv6Groups(section: String): List<Int>? {
    if (section.isEmpty()) return emptyList()
    return section.split(':').map { hextet ->
        if (hextet.isEmpty() || hextet.length > 4 || hextet.any { it !in "0123456789abcdefABCDEF" }) return null
        hextet.toInt(16)
    }
}

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
