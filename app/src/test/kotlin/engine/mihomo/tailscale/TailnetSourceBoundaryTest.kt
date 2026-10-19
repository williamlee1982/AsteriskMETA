// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the P0 boundaries in source: Tailnet code is shared by VPN and ROOT, so it must not
 * depend on ROOT runtime code, must not scrape core logs for state and must not hardcode device
 * identities or login URLs, and must not log or persist anything (it handles login URLs).
 */
class TailnetSourceBoundaryTest {
    private val sources: List<File> = listOf(
        "src/main/kotlin/engine/mihomo/tailscale",
        "src/main/kotlin/features/mihomo/tailscale",
        "src/main/kotlin/engine/network/TailnetAddressing.kt",
    ).map(::File).flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    @Test
    fun sourcesAreFound() {
        assertTrue("run from the app module directory", sources.size >= 5)
    }

    @Test
    fun noRootRuntimeOrShellDependencies() {
        val forbidden = listOf("import engine.root", "com.topjohnwu", "Runtime.getRuntime", "ProcessBuilder", "asteriskd.control")
        assertNoneContains(forbidden)
    }

    @Test
    fun noLogScrapingForTailscaleState() {
        assertNoneContains(listOf("subscribeLogcat", "LogMessage", "MihomoCoreLog", "\"/logs\""))
    }

    @Test
    fun noHardcodedLoginUrlsOrDeviceIdentities() {
        // The default login host is allowed (it validates URLs); concrete login URLs are not.
        assertNoneContains(listOf("login.tailscale.com/a/", "tskey-auth-", "oneplus"))
    }

    @Test
    fun loginUrlsAreNeverLoggedOrPersisted() {
        assertNoneContains(
            listOf("AndroidAppLogger", "android.util.Log", "SharedPreferences", "dataStore", "putString", "writeText", "println("),
        )
    }

    private fun assertNoneContains(needles: List<String>) {
        sources.forEach { file ->
            val text = file.readText()
            needles.forEach { needle ->
                assertTrue("${file.path} contains '$needle'", needle !in text)
            }
        }
    }
}
