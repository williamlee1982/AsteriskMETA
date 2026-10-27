// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.daemon.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** H2/M4: opt-in network flags are only encoded when enabled, so the default config stays upstream-identical. */
class AsteriskdNetworkOptionsEncodingTest {
    private fun network(capture: Boolean = false, block: Boolean = false) = AsteriskdNetworkConfig(
        enableIpv6 = false,
        disableSystemIpv6 = false,
        enableLocalDns = true,
        enableFakeDns = false,
        fakeDnsIpv4Pool = null,
        ignoredInterfaces = emptyList(),
        virtualInterfaces = emptyList(),
        hotspotInterfacePrefixes = emptyList(),
        proxyPrivateCidrs = emptyList(),
        bypassPrivateCidrs = emptyList(),
        appPolicy = AsteriskdAppPolicy(AsteriskdAppPolicyMode.Global, emptyList(), emptyList(), null, null),
        captureBoundNetworks = capture,
        blockEncryptedDns = block,
    )

    @Test
    fun defaultsAreOmitted() {
        val json = network().toJsonObject()
        assertFalse(json.containsKey("captureBoundNetworks"))
        assertFalse(json.containsKey("blockEncryptedDns"))
    }

    @Test
    fun enabledFlagsAreEncoded() {
        val json = network(capture = true, block = true).toJsonObject()
        assertEquals("true", json["captureBoundNetworks"].toString())
        assertEquals("true", json["blockEncryptedDns"].toString())
    }

    @Test
    fun defaultBpf2SocksIdleTimeoutIsEncodedAsZero() {
        val helper = AsteriskdBpf2SocksHelper(
            executablePath = "/data/app/libbpf2socks.so",
            bridgeListenAddress = "0.0.0.0",
            bridgePort = 1080,
            socksHost = "127.0.0.1",
            socksPort = 7890,
        )
        assertEquals(0, helper.tcpIdleTimeoutMilliseconds)
        val encoded = AsteriskdConfigEncoder.encode(bpf2SocksConfig(helper))
        assertTrue(encoded.contains("\"tcpIdleTimeoutMilliseconds\": 0"))
        assertFalse(encoded.contains("\"tcpIdleTimeoutMilliseconds\": 1000"))
        assertFalse(encoded.contains("\"tcpIdleTimeoutMilliseconds\": 300000"))
    }

    private fun bpf2SocksConfig(helper: AsteriskdBpf2SocksHelper) = AsteriskdConfig(
        owner = AsteriskdOwner.AsteriskMeta,
        coreType = AsteriskdCoreType.Mihomo,
        coreExecutablePath = "/data/app/libmihomo.so",
        coreConfigPath = "/data/data/org.asterisk.zcc.ameta/files/config.yaml",
        statePath = "/data/data/org.asterisk.zcc.ameta/files/asteriskd.state",
        logPath = "/data/data/org.asterisk.zcc.ameta/files/asteriskd.log",
        mode = AsteriskdMode.Bpf2Socks,
        core = AsteriskdCoreConfig(
            workingDirectory = "/data/data/org.asterisk.zcc.ameta/files",
            readinessTimeoutMilliseconds = 15000,
            ageSecretKey = null,
        ),
        network = network(),
        modeOptions = AsteriskdModeOptions(transparentPort = null, tunnelName = null),
        matcher = null,
        helper = helper,
        serviceControl = AsteriskdServiceControlConfig(
            enabled = false,
            schedule = AsteriskdScheduleControl(enabled = false, startCron = "", stopCron = ""),
            wifi = AsteriskdWifiControl(
                enabled = false,
                connectStart = AsteriskdWifiRule(false, emptyList(), emptyList()),
                connectStop = AsteriskdWifiRule(false, emptyList(), emptyList()),
                disconnectStart = AsteriskdWifiRule(false, emptyList(), emptyList()),
                disconnectStop = AsteriskdWifiRule(false, emptyList(), emptyList()),
            ),
        ),
    )
}
