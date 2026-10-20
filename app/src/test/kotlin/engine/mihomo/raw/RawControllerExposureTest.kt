// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.raw

import app.modes.RunModeTproxy
import app.modes.RunModeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** H4: ROOT raw profiles must not expose an unauthenticated controller beyond loopback. */
class RawControllerExposureTest {
    private fun check(yaml: String, runMode: Int = RunModeTproxy): MihomoRawConfigCheckResult =
        MihomoRawConfigParser.parse(("tproxy-port: 7895\n" + yaml).toByteArray()).check(runMode)

    @Test
    fun wildcardControllerWithoutSecretBlocksRootStart() {
        val result = check("external-controller: 0.0.0.0:9090")
        assertEquals(RawConfigReadiness.Blocked, result.readiness)
        assertEquals("external-controller", result.issues.single { it.readiness == RawConfigReadiness.Blocked }.fieldPath)
    }

    @Test
    fun lanAddressAndBarePortAlsoBlock() {
        assertEquals(RawConfigReadiness.Blocked, check("external-controller: 192.168.1.2:9090").readiness)
        assertEquals(RawConfigReadiness.Blocked, check("external-controller: ':9090'").readiness)
        assertEquals(RawConfigReadiness.Blocked, check("external-controller: '[::]:9090'").readiness)
    }

    @Test
    fun secretOrLoopbackKeepsStartAllowed() {
        assertNotEquals(RawConfigReadiness.Blocked, check("external-controller: 0.0.0.0:9090\nsecret: s3cr3t").readiness)
        assertNotEquals(RawConfigReadiness.Blocked, check("external-controller: 127.0.0.1:9090").readiness)
        assertNotEquals(RawConfigReadiness.Blocked, check("external-controller: '[::1]:9090'").readiness)
        assertNotEquals(RawConfigReadiness.Blocked, check("external-controller: localhost:9090").readiness)
    }

    @Test
    fun nonRootModesKeepUpstreamBehaviour() {
        assertNotEquals(
            RawConfigReadiness.Blocked,
            check("external-controller: 0.0.0.0:9090", RunModeVpnService).readiness,
        )
    }
}
