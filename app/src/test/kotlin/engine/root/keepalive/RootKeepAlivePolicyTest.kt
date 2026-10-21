// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.keepalive

import app.modes.RunModeTproxy
import app.modes.RunModeVpnService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootKeepAlivePolicyTest {
    @Test
    fun runsOnlyForARunningRootCoreWithoutTheStatsService() {
        assertTrue(RootKeepAliveService.shouldRun(RunModeTproxy, proxyRunning = true, trafficStatsNotificationActive = false))
        assertFalse(RootKeepAliveService.shouldRun(RunModeTproxy, proxyRunning = true, trafficStatsNotificationActive = true))
        assertFalse(RootKeepAliveService.shouldRun(RunModeTproxy, proxyRunning = false, trafficStatsNotificationActive = false))
        assertFalse(RootKeepAliveService.shouldRun(RunModeVpnService, proxyRunning = true, trafficStatsNotificationActive = false))
    }
}
