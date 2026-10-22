// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import app.modes.RunModeTproxy
import app.modes.RunModeVpnService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import system.RootShellGateway
import system.ShellExecOptions
import system.ShellExecResult
import utils.shellQuote
import java.io.File
import java.util.concurrent.TimeUnit

class RootNativeBinaryIdentityTest {
    @get:Rule
    val temp = TemporaryFolder()
    private val installed = RootNativeBinaryIdentity(
        path = "/data/app/new/lib/arm64/libmihomo.so",
        inode = 20L,
        mtimeEpochSeconds = 200L,
        embeddedVersion = "v1.19.32-android.5",
    )

    @Test
    fun deletedOrDifferentIdentityMismatches() {
        assertTrue(
            rootNativeBinaryMismatch(
                installed,
                installed.copy(path = "/data/app/old/lib/arm64/libmihomo.so (deleted)"),
            ),
        )
        assertTrue(
            rootNativeBinaryMismatch(
                installed,
                installed.copy(path = "/data/app/old/lib/arm64/libmihomo.so"),
            ),
        )
        assertTrue(rootNativeBinaryMismatch(installed, installed.copy(inode = 21L)))
        assertTrue(rootNativeBinaryMismatch(installed, installed.copy(mtimeEpochSeconds = 201L)))
        assertTrue(rootNativeBinaryMismatch(installed, installed.copy(embeddedVersion = "v1.19.31")))
    }

    @Test
    fun matchingIdentityDoesNotRestart() {
        assertFalse(rootNativeBinaryMismatch(installed, installed))
        assertFalse(
            rootNativeBinaryMismatch(
                installed.copy(inode = null, mtimeEpochSeconds = null, embeddedVersion = null),
                RootNativeBinaryIdentity(path = ""),
            ),
        )
    }

    @Test
    fun versionTokenRequiresTheMihomoModulePath() {
        assertEquals(
            "v1.19.32-android.5",
            embeddedNativeVersionToken("github.com/metacubex/mihomo v1.19.32-android.5"),
        )
        assertNull(embeddedNativeVersionToken("v2.1.11-wl.2"))
    }

    @Test
    fun probeInterpretationDistinguishesMatchMismatchAndUnknown() {
        val deleted = """
            role=supervisor
            running_path=/data/app/old/lib/arm64/libasteriskd.so (deleted)
            running_stat=11 100
            running_version=
            installed_path=/data/app/new/lib/arm64/libasteriskd.so
            installed_stat=22 200
            installed_version=
            ---
        """.trimIndent()
        assertEquals(
            RootBinaryClassification.Mismatch,
            interpretRootNativeBinaryProbe(listOf("supervisor"), deleted),
        )

        val matched = """
            role=supervisor
            running_path=/data/app/new/lib/arm64/libasteriskd.so
            running_stat=22 200
            running_version=
            installed_path=/data/app/new/lib/arm64/libasteriskd.so
            installed_stat=22 200
            installed_version=
            ---
            role=core
            running_path=/data/app/new/lib/arm64/libmihomo.so
            running_stat=30 200
            running_version=github.com/metacubex/mihomo v1.19.32-android.5
            installed_path=/data/app/new/lib/arm64/libmihomo.so
            installed_stat=30 200
            installed_version=github.com/metacubex/mihomo v1.19.32-android.5
            ---
        """.trimIndent()
        assertEquals(
            RootBinaryClassification.Match,
            interpretRootNativeBinaryProbe(listOf("supervisor", "core"), matched),
        )
        assertEquals(RootBinaryClassification.Unknown, interpretRootNativeBinaryProbe(listOf("supervisor"), ""))
        assertEquals(RootBinaryClassification.Unknown, interpretRootNativeBinaryProbe(listOf("supervisor"), "role=core\n---\n"))
    }

    @Test
    fun replacementIsOneShotAndRespectsStopAndVpn() {
        assertEquals(
            RootRunningBinaryAction.ReplaceOnce,
            rootRunningBinaryAction(explicitStop = false, alreadyAttempted = false, RootBinaryClassification.Mismatch),
        )
        assertEquals(
            RootRunningBinaryAction.Reattach,
            rootRunningBinaryAction(explicitStop = false, alreadyAttempted = true, RootBinaryClassification.Mismatch),
        )
        assertEquals(
            RootRunningBinaryAction.Reattach,
            rootRunningBinaryAction(explicitStop = false, alreadyAttempted = false, RootBinaryClassification.Match),
        )
        assertEquals(
            RootRunningBinaryAction.Reattach,
            rootRunningBinaryAction(explicitStop = false, alreadyAttempted = false, RootBinaryClassification.Unknown),
        )
        assertEquals(
            RootRunningBinaryAction.Ignore,
            rootRunningBinaryAction(explicitStop = true, alreadyAttempted = false, RootBinaryClassification.Mismatch),
        )
        assertEquals(
            RootRunningBinaryAction.Ignore,
            rootRunningBinaryAction(explicitStop = false, alreadyAttempted = false, RootBinaryClassification.NotRunning),
        )
        assertFalse(shouldConsiderRootBinaryReplacement(RunModeVpnService))
        assertTrue(shouldConsiderRootBinaryReplacement(RunModeTproxy))
    }

    @Test
    fun probeTargetsCoverSupervisorCoreAndHelperOnlyWhenPresent() {
        val targets = rootProcessProbeTargets(
            supervisorPid = 10,
            asteriskdPath = "/new/libasteriskd.so",
            corePid = 11,
            corePath = "/new/libmihomo.so",
            helperPid = 12,
            helperPath = "/new/libhev-socks5-tunnel-cli.so",
        )
        assertEquals(listOf("supervisor", "core", "helper"), targets.map { it.role })
        assertTrue(
            rootProcessProbeTargets(
                supervisorPid = 0,
                asteriskdPath = "/new/libasteriskd.so",
                corePid = null,
                corePath = "/new/libmihomo.so",
                helperPid = 4,
                helperPath = null,
            ).isEmpty(),
        )
    }

    @Test
    fun probeCommandReadsIdentityAndDoesNotExecuteTheBinary() {
        val path = "/data/app/new/lib/arm64/libasteriskd.so"
        val command = RootNativeBinaryProbeCommand.build(
            listOf(RootNativeBinaryProbeTarget("supervisor", 42, path)),
        )
        val procExe = "/proc/42/exe".shellQuote()
        assertTrue(command.contains("readlink"))
        // lstat of the proc symlink is a different inode than the mapped file.
        assertTrue(command.contains("stat -L -c '%i %Y' $procExe"))
        assertFalse(command.contains("stat -c '%i %Y' $procExe"))
        assertTrue(command.contains("stat -c '%i %Y' ${path.shellQuote()}"))
        assertTrue(command.contains("grep -a -o -m 1 -E"))
        assertTrue(command.contains(path.shellQuote()))
        assertTrue(command.contains(procExe))
        assertFalse(command.contains("\n$path"))
        assertFalse(command.contains(" $path "))
    }

    @Test
    fun sameBinaryMatchesWhenProcLinkStatDiffersFromTarget() {
        assumeProcProbe()
        val binary = executableCopy("libmihomo.so")
        val process = startHoldingProcess(binary)
        try {
            val linkStat = statLine("-c", "%i %Y", "/proc/${process.pid()}/exe")
            val fileStat = statLine("-c", "%i %Y", binary.absolutePath)
            assertNotEquals(linkStat, fileStat)
            assertEquals(binary.absolutePath, readlinkExe(process.pid()))
            val stdout = probe(
                listOf(RootNativeBinaryProbeTarget("core", process.pid().toInt(), binary.absolutePath)),
            )
            assertTrue(stdout, stdout.contains("running_stat=$fileStat"))
            assertFalse(stdout, stdout.contains("running_stat=$linkStat"))
            assertEquals(
                stdout,
                RootBinaryClassification.Match,
                interpretRootNativeBinaryProbe(listOf("core"), stdout),
            )
        } finally {
            stop(process)
        }
    }

    @Test
    fun deletedExeIsMismatch() {
        assumeProcProbe()
        val oldBinary = executableCopy("old-libmihomo.so")
        val process = startHoldingProcess(oldBinary)
        try {
            assertTrue(oldBinary.delete())
            val installed = executableCopy("libmihomo.so")
            val followed = statLine("-L", "-c", "%i %Y", "/proc/${process.pid()}/exe")
            val installedStat = statLine("-c", "%i %Y", installed.absolutePath)
            assertNotEquals(followed, installedStat)
            val stdout = probe(
                listOf(RootNativeBinaryProbeTarget("core", process.pid().toInt(), installed.absolutePath)),
            )
            assertTrue(stdout, stdout.contains(" (deleted)"))
            assertTrue(stdout, stdout.contains("running_stat=$followed"))
            assertTrue(stdout, stdout.contains("installed_stat=$installedStat"))
            assertEquals(
                stdout,
                RootBinaryClassification.Mismatch,
                interpretRootNativeBinaryProbe(listOf("core"), stdout),
            )
        } finally {
            stop(process)
        }
    }

    @Test
    fun differentInstallPathIsMismatch() {
        assumeProcProbe()
        val runningBinary = executableCopy("running-libmihomo.so")
        val installed = executableCopy("installed-libmihomo.so")
        val process = startHoldingProcess(runningBinary)
        try {
            val linkStat = statLine("-c", "%i %Y", "/proc/${process.pid()}/exe")
            val runningStat = statLine("-c", "%i %Y", runningBinary.absolutePath)
            assertNotEquals(linkStat, runningStat)
            assertEquals(runningBinary.absolutePath, readlinkExe(process.pid()))
            val stdout = probe(
                listOf(RootNativeBinaryProbeTarget("core", process.pid().toInt(), installed.absolutePath)),
            )
            assertFalse(stdout, stdout.contains(" (deleted)"))
            assertTrue(stdout, stdout.contains("running_path=${runningBinary.absolutePath}"))
            assertTrue(stdout, stdout.contains("installed_path=${installed.absolutePath}"))
            assertTrue(stdout, stdout.contains("running_stat=$runningStat"))
            assertEquals(
                stdout,
                RootBinaryClassification.Mismatch,
                interpretRootNativeBinaryProbe(listOf("core"), stdout),
            )
        } finally {
            stop(process)
        }
    }

    @Test
    fun everyProbeRoleMatchesItsOwnMappedBinary() {
        assumeProcProbe()
        val supervisor = executableCopy("libasteriskd.so")
        val core = executableCopy("libmihomo.so")
        val helper = executableCopy("libhev-socks5-tunnel-cli.so")
        val supervisorProcess = startHoldingProcess(supervisor)
        val coreProcess = startHoldingProcess(core)
        val helperProcess = startHoldingProcess(helper)
        try {
            val targets = listOf(
                RootNativeBinaryProbeTarget("supervisor", supervisorProcess.pid().toInt(), supervisor.absolutePath),
                RootNativeBinaryProbeTarget("core", coreProcess.pid().toInt(), core.absolutePath),
                RootNativeBinaryProbeTarget("helper", helperProcess.pid().toInt(), helper.absolutePath),
            )
            val stdout = probe(targets)
            listOf(supervisor to supervisorProcess, core to coreProcess, helper to helperProcess).forEach { (binary, process) ->
                val linkStat = statLine("-c", "%i %Y", "/proc/${process.pid()}/exe")
                val fileStat = statLine("-c", "%i %Y", binary.absolutePath)
                assertNotEquals(linkStat, fileStat)
                assertEquals(binary.absolutePath, readlinkExe(process.pid()))
                assertTrue(stdout, stdout.contains("running_stat=$fileStat"))
                assertFalse(stdout, stdout.contains("running_stat=$linkStat"))
            }
            assertEquals(
                stdout,
                RootBinaryClassification.Match,
                interpretRootNativeBinaryProbe(listOf("supervisor", "core", "helper"), stdout),
            )
        } finally {
            stop(supervisorProcess)
            stop(coreProcess)
            stop(helperProcess)
        }
    }

    @Test
    fun guardStyleProcSelfExeReexecMatchesInstalledBinary() {
        assumeProcProbe()
        val gcc = listOf("/usr/bin/gcc", "/usr/bin/cc").firstOrNull { File(it).canExecute() }
        assumeTrue(gcc != null)
        val source = temp.newFile("guard.c")
        source.writeText(
            """
            #include <unistd.h>
            int main(int argc, char **argv) {
                if (argc > 1 && argv[1][0] == 'g') for (;;) pause();
                char *args[] = {"asteriskd", "guard", "--config", "x", 0};
                execv("/proc/self/exe", args);
                return 127;
            }
            """.trimIndent(),
        )
        val binary = File(temp.root, "libasteriskd.so")
        val compile = ProcessBuilder(gcc, "-O2", "-o", binary.absolutePath, source.absolutePath).start()
        val compileErrors = compile.errorStream.bufferedReader().readText()
        assertTrue(compileErrors, compile.waitFor(30, TimeUnit.SECONDS))
        assertEquals(compileErrors, 0, compile.exitValue())
        val process = ProcessBuilder(binary.absolutePath).start()
        try {
            awaitCmdline(process.pid(), "guard")
            assertEquals(binary.absolutePath, readlinkExe(process.pid()))
            val linkStat = statLine("-c", "%i %Y", "/proc/${process.pid()}/exe")
            val fileStat = statLine("-c", "%i %Y", binary.absolutePath)
            assertNotEquals(linkStat, fileStat)
            val stdout = probe(
                listOf(RootNativeBinaryProbeTarget("supervisor", process.pid().toInt(), binary.absolutePath)),
            )
            assertTrue(stdout, stdout.contains("running_path=${binary.absolutePath}"))
            assertFalse(stdout, stdout.contains(" (deleted)"))
            assertTrue(stdout, stdout.contains("running_stat=$fileStat"))
            assertEquals(
                stdout,
                RootBinaryClassification.Match,
                interpretRootNativeBinaryProbe(listOf("supervisor"), stdout),
            )
        } finally {
            stop(process)
        }
    }

    @Test
    fun shellProbeClassifiesADeletedSupervisorAsMismatch() = runBlocking {
        val shell = object : RootShellGateway {
            override suspend fun exec(command: String, options: ShellExecOptions) = ShellExecResult(
                errno = 0,
                stdout = """
                    role=supervisor
                    running_path=/data/app/old/libasteriskd.so (deleted)
                    running_stat=1 10
                    running_version=
                    installed_path=/data/app/new/libasteriskd.so
                    installed_stat=2 20
                    installed_version=
                    ---
                """.trimIndent(),
                stderr = "",
            )
            override suspend fun execStreaming(
                command: String,
                options: ShellExecOptions,
                onStdoutLine: (String) -> Unit,
            ) = ShellExecResult(0, "", "")
            override suspend fun hasRootAccess() = true
        }
        val classification = RootNativeBinaryProbe(shell).classify(
            listOf(RootNativeBinaryProbeTarget("supervisor", 7, "/data/app/new/libasteriskd.so")),
        )
        assertEquals(RootBinaryClassification.Mismatch, classification)
    }

    private fun assumeProcProbe() {
        assumeTrue(File("/bin/sh").canExecute())
        assumeTrue(File("/bin/sleep").canExecute())
        assumeTrue(File("/proc/self/exe").exists())
    }

    private fun executableCopy(name: String): File {
        val binary = temp.newFile(name)
        binary.writeBytes(File("/bin/sleep").readBytes())
        assertTrue(binary.setExecutable(true))
        return binary
    }

    private fun startHoldingProcess(binary: File): Process =
        ProcessBuilder(binary.absolutePath, "30").start()

    private fun stop(process: Process) {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    private fun statLine(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("stat") + arguments.asList()).start()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        val stderr = process.errorStream.bufferedReader().readText()
        assertTrue(stderr.ifBlank { stdout }, process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(stderr.ifBlank { stdout }, 0, process.exitValue())
        return stdout
    }

    private fun readlinkExe(pid: Long): String {
        val process = ProcessBuilder("readlink", "/proc/$pid/exe").start()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(stdout, 0, process.exitValue())
        return stdout
    }

    private fun probe(targets: List<RootNativeBinaryProbeTarget>): String {
        val process = ProcessBuilder("/bin/sh", "-c", RootNativeBinaryProbeCommand.build(targets)).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        assertTrue(stderr.ifBlank { stdout }, process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(stderr.ifBlank { stdout }, 0, process.exitValue())
        return stdout
    }

    private fun awaitCmdline(pid: Long, needle: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var cmdline = ""
        while (System.nanoTime() < deadline) {
            cmdline = File("/proc/$pid/cmdline").readText(Charsets.ISO_8859_1)
            if (cmdline.contains(needle)) return
            Thread.sleep(20)
        }
        assertTrue(cmdline, cmdline.contains(needle))
    }
}
