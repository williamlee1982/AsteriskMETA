// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RootConfigChangePlanTest {
    private val daemon = "mode: tproxy\ntproxy_port: 7895\n".toByteArray()
    private val base = """
        mixed-port: 7890
        tproxy-port: 7895
        external-controller: '127.0.0.1:35277'
        secret: 'not-a-real-secret'
        dns:
          enable: true
          listen: 127.0.0.1:1053
          enhanced-mode: fake-ip
          nameserver:
            - https://1.1.1.1/dns-query
        proxies:
          - {name: a, type: ss, server: a.example, port: 1, cipher: aes-128-gcm, password: x}
        rules:
          - MATCH,DIRECT
    """.trimIndent()

    private fun plan(nextProfile: String, nextDaemon: ByteArray = daemon) =
        planRootConfigChange(base.toByteArray(), daemon, nextProfile.toByteArray(), nextDaemon)

    @Test
    fun identicalFilesAreANoOp() {
        assertEquals(RootConfigChangePlan.NoOp, plan(base))
    }

    @Test
    fun proxiesRulesAndDnsUpstreamsAreHotReloaded() {
        val next = base
            .replace("a.example", "b.example")
            .replace("MATCH,DIRECT", "MATCH,a")
            .replace("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query")
        assertEquals(RootConfigChangePlan.HotReload, plan(next))
    }

    @Test
    fun listenerControllerAndDnsHijackChangesRestart() {
        assertEquals(RootConfigChangePlan.Restart, plan(base.replace("tproxy-port: 7895", "tproxy-port: 7896")))
        assertEquals(RootConfigChangePlan.Restart, plan(base.replace("35277", "35278")))
        assertEquals(RootConfigChangePlan.Restart, plan(base.replace("enhanced-mode: fake-ip", "enhanced-mode: redir-host")))
        assertEquals(RootConfigChangePlan.Restart, plan("$base\ntun:\n  enable: true\n"))
    }

    @Test
    fun anyDaemonChangeOrUnknownStateRestarts() {
        assertEquals(RootConfigChangePlan.Restart, plan(base, "mode: tun\n".toByteArray()))
        assertEquals(
            RootConfigChangePlan.Restart,
            planRootConfigChange(null, daemon, base.toByteArray(), daemon),
        )
        assertEquals(RootConfigChangePlan.Restart, plan("proxies: [unterminated\n"))
    }
}
