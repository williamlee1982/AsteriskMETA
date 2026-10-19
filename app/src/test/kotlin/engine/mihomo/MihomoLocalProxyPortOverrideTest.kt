// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo

import app.AppState
import app.modes.RunModeBpf2Socks
import app.modes.RunModeTproxy
import app.modes.RunModeTun2Socks
import app.modes.RunModeVpnService
import engine.proxy.LocalProxyLoopbackAddress
import engine.vpn.VpnDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.snakeyaml.engine.v2.api.Load

/**
 * AsteriskMETA owns the local inbound ports: a profile's `mixed-port` (for example the common
 * 7890) is replaced by the app's local proxy port setting in every run mode, so smoke tests must
 * target `127.0.0.1:<app setting>` rather than the port written in the YAML.
 */
class MihomoLocalProxyPortOverrideTest {
    @Test
    fun defaultAppSettingReplacesProfileMixedPort() {
        val runtime = render(AppState(), RunModeVpnService)

        assertEquals(VpnDefaults.LOCAL_PROXY_PORT, runtime["mixed-port"])
        assertEquals(0, runtime["port"])
        assertEquals(LocalProxyLoopbackAddress, runtime["bind-address"])
        assertEquals(false, runtime["allow-lan"])
    }

    @Test
    fun configuredPortIsUsedInVpnAndRootModes() {
        val appState = AppState(localProxyPort = CustomPort.toString())
        for (runMode in listOf(RunModeVpnService, RunModeTproxy, RunModeTun2Socks, RunModeBpf2Socks)) {
            val runtime = render(appState, runMode)
            assertEquals("runMode=$runMode", CustomPort, runtime["mixed-port"])
            assertEquals("runMode=$runMode", 0, runtime["port"])
            assertEquals("runMode=$runMode", LocalProxyLoopbackAddress, runtime["bind-address"])
        }
    }

    @Test
    fun invalidPortSettingFallsBackToAppDefault() {
        for (value in listOf("", "0", "70000", "not-a-port", " ")) {
            val runtime = render(AppState(localProxyPort = value), RunModeTproxy)
            assertEquals("localProxyPort='$value'", VpnDefaults.LOCAL_PROXY_PORT, runtime["mixed-port"])
        }
    }

    @Test
    fun tailscaleOutboundAndTailnetRulesPassThroughUnchanged() {
        val runtime = render(AppState(localProxyPort = CustomPort.toString()), RunModeTproxy)

        val proxies = runtime["proxies"] as List<*>
        val tailscale = proxies.map { it as Map<*, *> }.single { it["name"] == "TS" }
        assertEquals("tailscale", tailscale["type"])
        assertEquals("example-node", tailscale["hostname"])
        assertEquals("./tailscale-example-node", tailscale["state-dir"])
        assertEquals(true, tailscale["udp"])
        val rules = runtime["rules"] as List<*>
        assertTrue(rules.toString(), TailnetRule in rules)
    }

    private fun render(appState: AppState, runMode: Int): Map<*, *> {
        val yaml = sourceProfile().toAsteriskRuntimeProfileYaml(
            appState = appState,
            runMode = runMode,
            tunApplicationUids = emptyList(),
            useRootProviderPaths = false,
        )
        return Load(MihomoYamlLoadSettings).loadFromString(yaml) as Map<*, *>
    }

    private fun sourceProfile(): Map<String, Any?> = linkedMapOf(
        "mixed-port" to 7890,
        "port" to 7891,
        "socks-port" to 7892,
        "allow-lan" to true,
        "proxies" to listOf(
            linkedMapOf(
                "name" to "TS",
                "type" to "tailscale",
                "hostname" to "example-node",
                "state-dir" to "./tailscale-example-node",
                "udp" to true,
            ),
        ),
        "rules" to listOf(TailnetRule, "MATCH,DIRECT"),
    )

    private companion object {
        const val CustomPort = 23456
        const val TailnetRule = "IP-CIDR,100.64.0.0/10,TS,no-resolve"
    }
}
