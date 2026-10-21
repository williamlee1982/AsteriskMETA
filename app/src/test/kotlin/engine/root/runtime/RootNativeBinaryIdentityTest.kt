// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import app.modes.RunModeTproxy
import app.modes.RunModeVpnService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import system.RootShellGateway
import system.ShellExecOptions
import system.ShellExecResult
import utils.shellQuote

class RootNativeBinaryIdentityTest {
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
        assertTrue(command.contains("readlink"))
        assertTrue(command.contains("stat -c '%i %Y'"))
        assertTrue(command.contains("grep -a -o -m 1 -E"))
        assertTrue(command.contains(path.shellQuote()))
        assertTrue(command.contains("/proc/42/exe".shellQuote()))
        assertFalse(command.contains("\n$path"))
        assertFalse(command.contains(" $path "))
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
}
