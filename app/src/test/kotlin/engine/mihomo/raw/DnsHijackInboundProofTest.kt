// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.raw

import app.modes.RunModeTproxy
import app.modes.RunModeTun
import app.modes.RunModeTun2Socks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M1: an IN-NAME/IN-TYPE restricted DNS rule only proves hijack for the inbound that receives it. */
class DnsHijackInboundProofTest {
    private fun hijack(rules: String, extra: String = ""): MihomoRawDnsHijack {
        val yaml = listOf(
            "dns: {enable: true}",
            "tproxy-port: 7895",
            extra,
            "proxies:",
            "  - {name: dns-out, type: dns}",
            "rules:",
            rules,
        ).joinToString("\n")
        val snapshot = requireNotNull(MihomoRawConfigParser.parse(yaml.toByteArray()).snapshot)
        return requireNotNull(snapshot.dnsHijack.value)
    }

    @Test
    fun unrestrictedRuleProvesEveryMode() {
        val value = hijack("  - DST-PORT,53,dns-out")
        assertTrue(value.provenFor(RunModeTproxy))
        assertTrue(value.provenFor(RunModeTun))
        assertTrue(value.provenFor(RunModeTun2Socks))
    }

    @Test
    fun tproxyTypeSelectorProvesOnlyTproxy() {
        val value = hijack("  - AND,((DST-PORT,53),(IN-TYPE,TPROXY)),dns-out")
        assertTrue(value.provenFor(RunModeTproxy))
        assertFalse(value.provenFor(RunModeTun))
    }

    @Test
    fun foreignInboundNameDoesNotProveTproxy() {
        val value = hijack("  - AND,((DST-PORT,53),(IN-NAME,lan-socks)),dns-out")
        assertFalse(value.provenFor(RunModeTproxy))
        assertFalse(value.proven)
    }

    @Test
    fun defaultTproxyNameAndNamedTproxyListenerProve() {
        assertTrue(hijack("  - AND,((DST-PORT,53),(IN-NAME,DEFAULT-TPROXY)),dns-out").provenFor(RunModeTproxy))
        val named = hijack(
            "  - AND,((DST-PORT,53),(IN-NAME,tp-in)),dns-out",
            extra = "listeners:\n  - {name: tp-in, type: tproxy, port: 7896}",
        )
        assertTrue(named.provenFor(RunModeTproxy))
    }

    @Test
    fun protocolSplitRulesNeedBothTransportsForTheMode() {
        val value = hijack(
            "  - AND,((DST-PORT,53),(NETWORK,TCP),(IN-TYPE,TPROXY)),dns-out\n" +
                "  - AND,((DST-PORT,53),(NETWORK,UDP),(IN-TYPE,TUN)),dns-out",
        )
        assertFalse(value.provenFor(RunModeTproxy))
        assertFalse(value.provenFor(RunModeTun))
    }
}
