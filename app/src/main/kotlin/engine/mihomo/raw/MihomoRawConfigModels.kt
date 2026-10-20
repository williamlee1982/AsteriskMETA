// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.raw

import engine.mihomo.MihomoControlConfig

internal data class RawConfigField<T>(
    val value: T? = null,
    val path: String,
    val problem: String? = null,
)

internal data class MihomoRawApiConfig(
    val control: MihomoControlConfig,
    val hasSecret: Boolean,
    /** The controller listens beyond loopback (wildcard, empty host or a LAN address). */
    val bindsNonLoopback: Boolean = false,
)

internal data class MihomoRawSocksInbound(
    val port: Int,
    val path: String,
)

internal data class MihomoRawTunInbound(
    val device: String,
    val stack: String,
    val congestionController: String = "",
    val mtu: Int,
    val ipv4Address: String,
    val ipv6Address: String? = null,
    val path: String,
)

internal data class MihomoRawDnsHijack(
    val dnsEnabled: Boolean,
    /** Proven for every inbound: both transports are covered by rules without IN-NAME/IN-TYPE. */
    val proven: Boolean,
    val matchedRule: String? = null,
    val outbound: String? = null,
    val rules: List<MihomoRawDnsHijackRule> = emptyList(),
    val inbounds: MihomoRawInboundNames = MihomoRawInboundNames(),
) {
    /**
     * Hijacked DNS reaches the core through the run mode's inbound, so an IN-NAME/IN-TYPE
     * restricted rule only proves interception when it selects that inbound.
     */
    fun provenFor(runMode: Int, vpnUsesHev: Boolean = false): Boolean {
        if (!dnsEnabled) return false
        val selector = inbounds.selectorFor(runMode, vpnUsesHev)
        fun covers(protocol: String) = rules.any { rule ->
            protocol in rule.protocols && rule.selects(selector)
        }
        return covers("TCP") && covers("UDP")
    }
}

internal data class MihomoRawDnsHijackRule(
    val protocols: Set<String>,
    val inNames: Set<String> = emptySet(),
    val inTypes: Set<String> = emptySet(),
) {
    fun selects(selector: MihomoRawInboundSelector): Boolean =
        inNames.all { it in selector.names } && inTypes.all { it in selector.types }
}

internal data class MihomoRawInboundSelector(val names: Set<String>, val types: Set<String>)

/** Upper-cased inbound names declared by the profile, grouped by listener kind. */
internal data class MihomoRawInboundNames(
    val tproxy: Set<String> = emptySet(),
    val tun: Set<String> = emptySet(),
    val socks: Set<String> = emptySet(),
) {
    fun selectorFor(runMode: Int, vpnUsesHev: Boolean): MihomoRawInboundSelector {
        val tproxySelector = MihomoRawInboundSelector(tproxy + "DEFAULT-TPROXY", setOf("TPROXY"))
        val tunSelector = MihomoRawInboundSelector(tun + "DEFAULT-TUN", setOf("TUN"))
        val socksSelector = MihomoRawInboundSelector(
            socks + setOf("DEFAULT-MIXED", "DEFAULT-SOCKS"),
            setOf("SOCKS", "SOCKS5", "MIXED"),
        )
        return when (runMode) {
            app.modes.RunModeTproxy -> tproxySelector
            app.modes.RunModeTun -> tunSelector
            app.modes.RunModeTun2Socks, app.modes.RunModeBpf2Socks -> socksSelector
            else -> if (vpnUsesHev) socksSelector else tunSelector
        }
    }
}

internal data class MihomoRawConfigSnapshot(
    val sourceSha256: String,
    val mode: RawConfigField<String>,
    val logLevel: RawConfigField<String>,
    val ipv6: RawConfigField<Boolean>,
    val geodataMode: RawConfigField<Boolean>,
    val geodataLoader: RawConfigField<String>,
    val snifferEnabled: RawConfigField<Boolean>,
    val dnsEnabled: RawConfigField<Boolean>,
    val api: RawConfigField<MihomoRawApiConfig>,
    val tproxyPort: RawConfigField<Int>,
    val socksInbound: RawConfigField<MihomoRawSocksInbound>,
    val tunInbound: RawConfigField<MihomoRawTunInbound>,
    val dnsHijack: RawConfigField<MihomoRawDnsHijack>,
)

internal data class MihomoRawConfigParseResult(
    val sourceBytes: ByteArray,
    val snapshot: MihomoRawConfigSnapshot? = null,
    val error: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MihomoRawConfigParseResult

        if (!sourceBytes.contentEquals(other.sourceBytes)) return false
        if (snapshot != other.snapshot) return false
        if (error != other.error) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sourceBytes.contentHashCode()
        result = 31 * result + (snapshot?.hashCode() ?: 0)
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }
}

internal enum class RawConfigReadiness {
    Ready,
    Degraded,
    Blocked,
}

internal data class RawConfigIssue(
    val readiness: RawConfigReadiness,
    val fieldPath: String,
    val reason: String,
)

internal data class MihomoRawConfigCheckResult(
    val readiness: RawConfigReadiness,
    val issues: List<RawConfigIssue>,
) {
    val canStart: Boolean
        get() = readiness != RawConfigReadiness.Blocked
}
