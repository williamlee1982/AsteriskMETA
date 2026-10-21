// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.publication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** ROOT service logs must survive a start (append + rotate), never be wiped. */
class RootServiceLogRotationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun layout(dataDir: File) = RootRuntimeLayout(
        configPath = File(dataDir, "config.yaml").path,
        mihomoCorePath = "/unused/mihomo",
        asteriskdPath = "/unused/asteriskd",
        bpfMatcherPath = "/unused/matcher",
        bpf2socksPath = "/unused/bpf2socks",
        hevSocks5TunnelPath = "/unused/hev",
        dataDir = dataDir.path,
    )

    private fun runRotation(dataDir: File): Pair<Int, String> {
        assumeTrue(File("/bin/sh").canExecute())
        val script = "(\nset -eu\n" + RootPublicationCommand.buildServiceLogRotation(layout(dataDir)) + ")\n"
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        return process.exitValue() to output
    }

    @Test
    fun smallLogsAreKeptAndAppendedWithAStartMarker() {
        val logs = File(temp.root, "logs").apply { mkdirs() }
        File(logs, "error.log").writeText("previous core crash: panic\n")
        File(logs, "asteriskd.log").writeText("previous supervisor line\n")
        File(logs, "logcat.log").writeText("app log\n")

        val (exit, output) = runRotation(temp.root)

        assertEquals(output, 0, exit)
        val error = File(logs, "error.log").readText()
        assertTrue(error.startsWith("previous core crash: panic\n"))
        assertTrue(error.contains("===== AsteriskMETA ROOT start "))
        assertTrue(File(logs, "asteriskd.log").readText().startsWith("previous supervisor line\n"))
        assertEquals("app log\n", File(logs, "logcat.log").readText())
        assertFalse(File(logs, "error.log.1").exists())
    }

    @Test
    fun oversizedLogsRotateKeepingTwoGenerations() {
        val logs = File(temp.root, "logs").apply { mkdirs() }
        val big = ByteArray((RootServiceLogRotateBytes + 1).toInt()) { 'x'.code.toByte() }
        File(logs, "error.log").writeBytes(big)
        File(logs, "error.log.1").writeText("generation one\n")
        File(logs, "error.log.2").writeText("generation two (dropped)\n")

        val (exit, output) = runRotation(temp.root)

        assertEquals(output, 0, exit)
        assertFalse(File(logs, "error.log").exists())
        assertEquals(big.size.toLong(), File(logs, "error.log.1").length())
        assertEquals("generation one\n", File(logs, "error.log.2").readText())
    }

    @Test
    fun missingLogsAndSymlinksAreLeftAlone() {
        val logs = File(temp.root, "logs").apply { mkdirs() }
        val outside = temp.newFile("outside.txt").apply { writeText("do not touch\n") }
        java.nio.file.Files.createSymbolicLink(File(logs, "asteriskd.log").toPath(), outside.toPath())

        val (exit, output) = runRotation(temp.root)

        assertEquals(output, 0, exit)
        assertEquals("do not touch\n", outside.readText())
        assertFalse(File(logs, "error.log").exists())
    }

    @Test
    fun preparationNoLongerDeletesServiceLogs() {
        val command = RootPublicationCommand.buildPreparation(
            RootPublicationBundle(
                runtimeLayout = layout(File("/data/user/0/app/files/clash")),
                bootEnabled = false,
                launchMode = RootPublicationLaunchMode.Service,
                restartExpectedOwner = null,
                manageProviders = false,
            ),
        )
        assertFalse(command.contains("rm -f -- \"\$service_log\""))
        assertTrue(command.contains("mv -f -- \"\$service_log\" \"\$service_log.1\""))
    }
}
