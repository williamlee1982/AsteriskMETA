// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.daemon

import engine.root.daemon.control.AsteriskdPhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import system.RootShellGateway
import system.ShellExecOptions
import system.ShellExecResult

/** M5: a watch window that ends while the daemon is still starting is re-watched, not failed. */
class AsteriskdClientAwaitRunningTest {
    private fun snapshot(phase: String): String {
        val running = phase == "running"
        return """{"phase":"$phase","owner":"asteriskmeta","coreType":"mihomo","mode":"tproxy",""" +
            """"supervisorPid":100,"corePid":${if (running) "200" else "null"},"helperType":null,"helperPid":null,""" +
            """"matcherConfigured":false,"matcherActive":false,""" +
            """"rules":{"active":$running,"generation":${if (running) 1 else 0},"categories":${if (running) "[\"tproxy\"]" else "[]"}},""" +
            """"network":{"ipv4Ready":$running,"ipv6Enabled":false,"ipv6Ready":$running},""" +
            """"dnsEnhancement":{"required":false,"phase":"disabled","instanceId":"0","cycleId":"0","generation":"0","reason":null},""" +
            """"error":null}"""
    }

    private fun watchResponse(phase: String) =
        """{"protocolVersion":2,"requestId":"watch","result":{"code":"ok","snapshot":${snapshot(phase)},"message":null}}"""

    private fun event(sequence: Int, type: String, phase: String) =
        """{"protocolVersion":2,"event":{"sequence":$sequence,"type":"$type","snapshot":${snapshot(phase)},"details":null}}"""

    private val notRunning =
        """{"protocolVersion":2,"requestId":"watch","result":{"code":"not_running","snapshot":null,"message":"asteriskd is not running"}}"""

    private class ScriptedShell(private val windows: List<Pair<List<String>, Int>>) : RootShellGateway {
        var calls = 0
        override suspend fun exec(command: String, options: ShellExecOptions) = ShellExecResult(1, "", "")
        override suspend fun execStreaming(
            command: String,
            options: ShellExecOptions,
            onStdoutLine: (String) -> Unit,
        ): ShellExecResult {
            val (lines, exit) = windows[minOf(calls, windows.lastIndex)]
            calls++
            lines.forEach(onStdoutLine)
            return ShellExecResult(exit, "", "")
        }
        override suspend fun hasRootAccess() = true
    }

    @Test
    fun windowTimeoutDuringStartIsRewatched() = runBlocking {
        val shell = ScriptedShell(
            listOf(
                listOf(watchResponse("starting"), event(1, "starting", "applying-rules")) to 124,
                listOf(watchResponse("applying-rules"), event(2, "running", "running")) to 0,
            ),
        )
        val snapshot = AsteriskdClient(shell).awaitRunning("/data/asteriskd")
        assertEquals(AsteriskdPhase.Running, snapshot.phase)
        assertEquals(2, shell.calls)
    }

    @Test
    fun neverBindingGivesUpAfterTheUnboundTimeout() = runBlocking {
        var now = 0L
        val shell = ScriptedShell(listOf(listOf(notRunning) to 3))
        try {
            AsteriskdClient(shell).awaitRunning("/data/asteriskd", unboundTimeoutMilliseconds = 1_000L) {
                now += 300L
                now
            }
            fail("expected timeout")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("control socket"))
        }
    }

    @Test
    fun failedEventStillFailsImmediately() = runBlocking {
        val shell = ScriptedShell(listOf(listOf(watchResponse("starting")) to 124))
        val failing = ScriptedShell(
            listOf(listOf(watchResponse("starting"), event(1, "stopped", "stopped")) to 0),
        )
        try {
            AsteriskdClient(failing).awaitRunning("/data/asteriskd")
            fail("expected failure")
        } catch (_: IllegalStateException) {
        }
        assertEquals(1, failing.calls)
        assertEquals(0, shell.calls)
    }
}
