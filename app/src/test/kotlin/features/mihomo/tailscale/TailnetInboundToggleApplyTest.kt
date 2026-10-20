package features.mihomo.tailscale

import app.AppState
import engine.proxy.ProxyServiceResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** H1: the toggle must rebuild the running core's profile, which only a restart does. */
class TailnetInboundToggleApplyTest {
    private class Harness(initial: AppState) {
        var state = initial
        val restarts = mutableListOf<AppState>()
        var failure: Throwable? = null
        val update: ((AppState) -> AppState) -> Unit = { state = it(state) }
    }

    @Test
    fun runningProxyIsRestartedWithTheNewSetting() = runBlocking {
        val h = Harness(AppState(proxyRunning = true))
        applyTailnetInboundToggle(h.state, enabled = true, update = h.update,
            restart = { h.restarts += it; ProxyServiceResult.Success(proxyRunning = true) },
            onFailure = { h.failure = it })
        assertEquals(1, h.restarts.size)
        assertTrue(h.restarts.single().tailnetInboundForwardsEnabled)
        assertTrue(h.state.tailnetInboundForwardsEnabled)
        assertNull(h.failure)
    }

    @Test
    fun stoppedProxyOnlySavesTheSetting() = runBlocking {
        val h = Harness(AppState(proxyRunning = false))
        applyTailnetInboundToggle(h.state, enabled = true, update = h.update,
            restart = { h.restarts += it; ProxyServiceResult.Success(proxyRunning = true) },
            onFailure = { h.failure = it })
        assertTrue(h.restarts.isEmpty())
        assertTrue(h.state.tailnetInboundForwardsEnabled)
    }

    @Test
    fun restartFailureIsReported() = runBlocking {
        val boom = IllegalStateException("boom")
        val h = Harness(AppState(proxyRunning = true, tailnetInboundForwardsEnabled = true))
        applyTailnetInboundToggle(h.state, enabled = false, update = h.update,
            restart = { ProxyServiceResult.Failed(boom) },
            onFailure = { h.failure = it })
        assertEquals(boom, h.failure)
    }

    @Test
    fun restartResultUpdatesRunningState() = runBlocking {
        val h = Harness(AppState(proxyRunning = true))
        applyTailnetInboundToggle(h.state, enabled = true, update = h.update,
            restart = { ProxyServiceResult.Success(proxyRunning = false) },
            onFailure = { h.failure = it })
        assertEquals(false, h.state.proxyRunning)
    }
}
