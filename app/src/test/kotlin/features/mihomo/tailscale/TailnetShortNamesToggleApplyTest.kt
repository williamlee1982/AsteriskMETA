// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.mihomo.tailscale

import app.AppState
import engine.proxy.ProxyServiceResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetShortNamesToggleApplyTest {
    private class Harness(initial: AppState) {
        var state = initial
        val restarts = mutableListOf<AppState>()
        var failure: Throwable? = null
        val update: ((AppState) -> AppState) -> Unit = { state = it(state) }
    }

    @Test
    fun runningProxyIsRestartedWithTheNewSetting() = runBlocking {
        val harness = Harness(AppState(proxyRunning = true))
        applyTailnetShortNamesToggle(
            harness.state,
            enabled = false,
            update = harness.update,
            restart = { harness.restarts += it; ProxyServiceResult.Success(proxyRunning = true) },
            onFailure = { harness.failure = it },
        )
        assertEquals(1, harness.restarts.size)
        assertEquals(false, harness.restarts.single().tailnetShortNamesEnabled)
        assertEquals(false, harness.state.tailnetShortNamesEnabled)
        assertNull(harness.failure)
    }

    @Test
    fun stoppedProxyOnlySavesTheSetting() = runBlocking {
        val harness = Harness(AppState(proxyRunning = false, tailnetShortNamesEnabled = true))
        applyTailnetShortNamesToggle(
            harness.state,
            enabled = false,
            update = harness.update,
            restart = { harness.restarts += it; ProxyServiceResult.Success(proxyRunning = true) },
            onFailure = { harness.failure = it },
        )
        assertTrue(harness.restarts.isEmpty())
        assertEquals(false, harness.state.tailnetShortNamesEnabled)
    }

    @Test
    fun restartFailureIsReported() = runBlocking {
        val boom = IllegalStateException("boom")
        val harness = Harness(AppState(proxyRunning = true))
        applyTailnetShortNamesToggle(
            harness.state,
            enabled = false,
            update = harness.update,
            restart = { ProxyServiceResult.Failed(boom) },
            onFailure = { harness.failure = it },
        )
        assertEquals(boom, harness.failure)
        assertEquals(false, harness.state.tailnetShortNamesEnabled)
    }
}
