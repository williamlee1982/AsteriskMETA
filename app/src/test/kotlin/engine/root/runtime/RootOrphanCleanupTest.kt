// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import engine.root.publication.RootRuntimeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import system.ShellExecResult

/** C1: Stop/Shutdown without a live supervisor run a cleanup-only pass. */
class RootOrphanCleanupTest {
    private val layout = RootRuntimeLayout(
        configPath = "/data/adb/asterisk/config.yaml",
        mihomoCorePath = "/data/adb/asterisk/mihomo",
        asteriskdPath = "/data/adb/asterisk/asteriskd",
        bpfMatcherPath = "/data/adb/asterisk/matcher",
        bpf2socksPath = "/data/adb/asterisk/bpf2socks",
        hevSocks5TunnelPath = "/data/adb/asterisk/hev",
        dataDir = "/data/adb/asterisk",
    )

    @Test
    fun commandRunsCleanupOnlyWhenTheConfigExists() {
        val command = RootOrphanCleanupCommand.build(layout)
        assertTrue(command.contains("[ -f '/data/adb/asterisk/asteriskd.json' ]"))
        assertTrue(command.contains("'/data/adb/asterisk/asteriskd' cleanup --config '/data/adb/asterisk/asteriskd.json'"))
        assertFalse(command.contains(" start"))
    }

    @Test
    fun outcomeClassification() {
        assertEquals(RootOrphanCleanupOutcome.Skipped, RootOrphanCleanupCommand.outcome(ShellExecResult(0, "", "")))
        val stopped = """{"phase":"stopped","owner":"asteriskmeta","coreType":"mihomo","mode":"tproxy",""" +
            """"supervisorPid":100,"corePid":null,"helperType":null,"helperPid":null,"matcherConfigured":false,""" +
            """"matcherActive":false,"rules":{"active":false,"generation":0,"categories":[]},""" +
            """"network":{"ipv4Ready":false,"ipv6Enabled":false,"ipv6Ready":false},""" +
            """"dnsEnhancement":{"required":false,"phase":"disabled","instanceId":"0","cycleId":"0","generation":"0","reason":null},""" +
            """"error":null}"""
        val ok = """{"protocolVersion":2,"requestId":"cleanup","result":{"code":"ok","snapshot":$stopped,"message":null}}"""
        val live = """{"protocolVersion":2,"requestId":"cleanup","result":{"code":"already_running","snapshot":$stopped,"message":"m"}}"""
        assertEquals(RootOrphanCleanupOutcome.SupervisorLive, RootOrphanCleanupCommand.outcome(ShellExecResult(4, live, "")))
        assertEquals(RootOrphanCleanupOutcome.Cleaned, RootOrphanCleanupCommand.outcome(ShellExecResult(0, ok, "")))
        val busy = """{"protocolVersion":2,"requestId":"cleanup","result":{"code":"start_failed","snapshot":null,"message":"x"}}"""
        assertEquals(RootOrphanCleanupOutcome.Failed, RootOrphanCleanupCommand.outcome(ShellExecResult(1, busy, "")))
        assertEquals(RootOrphanCleanupOutcome.Failed, RootOrphanCleanupCommand.outcome(ShellExecResult(127, "", "not found")))
    }
}
