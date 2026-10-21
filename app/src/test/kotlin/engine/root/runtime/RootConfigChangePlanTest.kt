// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun decisionNamesTheKeyThatForcedRestartWithoutValues() {
        val identical = explainRootConfigChange(base.toByteArray(), daemon, base.toByteArray(), daemon)
        assertEquals(RootConfigChangePlan.NoOp, identical.plan)
        assertEquals("identical", identical.reason)

        val reloaded = explainRootConfigChange(
            base.toByteArray(),
            daemon,
            base.replace("a.example", "b.example").toByteArray(),
            daemon,
        )
        assertEquals(RootConfigChangePlan.HotReload, reloaded.plan)
        assertEquals("profile_reloadable", reloaded.reason)

        val port = explainRootConfigChange(
            base.toByteArray(),
            daemon,
            base.replace("tproxy-port: 7895", "tproxy-port: 7896").toByteArray(),
            daemon,
        )
        assertEquals(RootConfigChangePlan.Restart, port.plan)
        assertEquals("profile_key:tproxy-port", port.reason)
        assertFalse(port.reason.contains("7896"))
        assertFalse(port.reason.contains("not-a-real-secret"))

        val dns = explainRootConfigChange(
            base.toByteArray(),
            daemon,
            base.replace("listen: 127.0.0.1:1053", "listen: 127.0.0.1:1054").toByteArray(),
            daemon,
        )
        assertEquals("profile_key:dns.listen", dns.reason)

        val missing = explainRootConfigChange(null, daemon, base.toByteArray(), daemon)
        assertEquals("missing_current", missing.reason)

        val opaqueDaemon = explainRootConfigChange(base.toByteArray(), daemon, base.toByteArray(), "mode: tun\n".toByteArray())
        assertEquals(RootConfigChangePlan.Restart, opaqueDaemon.plan)
        assertEquals("daemon_config", opaqueDaemon.reason)
        assertFalse(opaqueDaemon.reason.contains("7895"))

        val currentDaemon = """{"mode":"tproxy","coreExecutablePath":"/old/libmihomo.so"}"""
        val nextDaemon = """{"mode":"tproxy","coreExecutablePath":"/new/libmihomo.so"}"""
        val executable = explainRootConfigChange(
            base.toByteArray(),
            currentDaemon.toByteArray(),
            base.toByteArray(),
            nextDaemon.toByteArray(),
        )
        assertEquals(RootConfigChangePlan.Restart, executable.plan)
        assertEquals("daemon_config:coreExecutablePath", executable.reason)
        assertFalse(executable.reason.contains("/old/libmihomo.so"))
        assertFalse(executable.reason.contains("/new/libmihomo.so"))
    }
}
