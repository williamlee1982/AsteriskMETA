// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

import engine.mihomo.MihomoHomeDirectoryName
import engine.mihomo.mihomoHomeDirectory
import engine.mihomo.tailscale.TailscaleStateDirIssue.ForeignAppData
import engine.mihomo.tailscale.TailscaleStateDirIssue.ImplicitDefault
import engine.mihomo.tailscale.TailscaleStateDirIssue.OutsideMihomoHome
import engine.mihomo.tailscale.TailscaleStateDirIssue.SharedStorage
import engine.mihomo.tailscale.TailscaleStateDirIssue.SharedWithAnotherOutbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TailscaleStateDirPolicyTest {
    @Test
    fun vpnAndRootCoresShareOneHomeSoRelativeStateDirsResolveIdentically() {
        // VPN: MihomoBridge -> Libclash.init(mihomoHomeDirectory(filesDir)).
        // ROOT: AndroidResourceFileStore.dataDir -> RootStartConfig.workingDirectory -> mihomo -d.
        val vpnHome = mihomoHomeDirectory(File(FilesDirectory)).path
        val rootHome = mihomoHomeDirectory(File(FilesDirectory)).path
        assertEquals("$FilesDirectory/$MihomoHomeDirectoryName", vpnHome)
        assertEquals(
            resolveTailscaleStateDir("./tailscale-node-a", vpnHome),
            resolveTailscaleStateDir("./tailscale-node-a", rootHome),
        )
    }

    @Test
    fun resolutionFollowsMihomoPathRules() {
        assertEquals("$Home/tailscale-node-a", resolveTailscaleStateDir("./tailscale-node-a", Home))
        assertEquals("$Home/$MihomoTailscaleDefaultStateDir", resolveTailscaleStateDir(null, Home))
        assertEquals("$Home/$MihomoTailscaleDefaultStateDir", resolveTailscaleStateDir("  ", Home))
        assertEquals("/data/local/tmp/ts", resolveTailscaleStateDir("/data/local/tmp/ts", Home))
        assertEquals("$FilesDirectory/ts", resolveTailscaleStateDir("../ts", Home))
    }

    @Test
    fun distinctExplicitStateDirsInsideHomeHaveNoIssues() {
        val findings = diagnose(outbound("TS-A", "./tailscale-a"), outbound("TS-B", "tailscale-b"))
        assertTrue(findings.toString(), findings.all { it.issues.isEmpty() })
    }

    @Test
    fun implicitDefaultsCollideOnOneIdentity() {
        val findings = diagnose(outbound("TS-A", null), outbound("TS-B", null), outbound("TS-C", "./tailscale"))
        findings.forEach { finding ->
            assertTrue(finding.toString(), SharedWithAnotherOutbound in finding.issues)
        }
        assertEquals(setOf(ImplicitDefault, SharedWithAnotherOutbound), findings[0].issues)
        assertEquals(setOf(SharedWithAnotherOutbound), findings[2].issues)
    }

    @Test
    fun stateDirsOutsideHomeAreNotPortableToRootMode() {
        assertEquals(setOf(OutsideMihomoHome), diagnose(outbound("TS", "../ts")).single().issues)
        assertEquals(setOf(OutsideMihomoHome), diagnose(outbound("TS", "/data/local/tmp/ts")).single().issues)
        assertEquals(setOf(OutsideMihomoHome), diagnose(outbound("TS", ".")).single().issues)
    }

    @Test
    fun otherAppsDataIsNeverAcceptedAsTsnetState() {
        // A standalone Tailscale app (any package) keeps its own identity; never reuse it.
        for (path in listOf(
            "/data/data/com.example.standalone/files",
            "/data/user/0/com.example.standalone/files/tailscaled.state",
            "/data/user_de/10/com.example.standalone/state",
        )) {
            assertEquals(path, setOf(OutsideMihomoHome, ForeignAppData), diagnose(outbound("TS", path)).single().issues)
        }
    }

    @Test
    fun ownAppDataOutsideHomeIsNotForeign() {
        val issues = diagnose(outbound("TS", "/data/data/$OwnPackage/files/ts")).single().issues
        assertEquals(setOf(OutsideMihomoHome), issues)
    }

    @Test
    fun sharedStorageIsRejectedAsWorldReadable() {
        assertEquals(setOf(OutsideMihomoHome, SharedStorage), diagnose(outbound("TS", "/sdcard/ts")).single().issues)
        assertEquals(
            setOf(OutsideMihomoHome, SharedStorage, ForeignAppData),
            diagnose(outbound("TS", "/storage/emulated/0/Android/data/com.example.other/ts")).single().issues,
        )
    }

    private fun diagnose(vararg outbounds: MihomoTailscaleOutbound) =
        diagnoseTailscaleStateDirs(outbounds.toList(), Home, OwnPackage)

    private fun outbound(name: String, stateDir: String?) = MihomoTailscaleOutbound(
        name = name,
        hostname = null,
        stateDir = stateDir,
        hasAuthKey = false,
        ephemeral = false,
        usesCustomControlUrl = false,
    )

    private companion object {
        const val OwnPackage = "example.app"
        const val FilesDirectory = "/data/user/0/$OwnPackage/files"
        const val Home = "$FilesDirectory/clash"
    }
}
