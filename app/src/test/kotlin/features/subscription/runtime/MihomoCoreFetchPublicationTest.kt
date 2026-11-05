// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.runtime

import app.AppState
import app.MihomoProfileState
import app.MihomoProfileType
import features.subscription.usecase.toSubscriptionFetchOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MihomoCoreFetchPublicationTest {
    @Test
    fun corePublicationDoesNotWaitForAnInFlightFetch() = runBlocking {
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val fetch = launch {
            mihomoCoreFetchLock.withLock {
                fetchStarted.complete(Unit)
                releaseFetch.await()
            }
        }
        fetchStarted.await()

        withTimeout(1_000L) {
            mihomoCorePublicationLock.withLock { }
        }

        releaseFetch.complete(Unit)
        fetch.join()
    }

    @Test
    fun proxyFetchGetsAShortTimeoutOnlyWhileTheProxyIsNotRunning() {
        val stopped = AndroidSubscriptionFetchOptions(useRunningProxy = true, proxyRunning = false)
        val running = stopped.copy(proxyRunning = true)
        val direct = AndroidSubscriptionFetchOptions(useRunningProxy = false, proxyRunning = false)

        assertEquals(ProxyNotRunningFetchTimeoutMillis, stopped.fetchTimeoutMillis())
        assertNull(running.fetchTimeoutMillis())
        assertNull(direct.fetchTimeoutMillis())
    }

    @Test
    fun appStatePropagatesProxyRunningIntoSubscriptionFetchOptions() {
        val profile = MihomoProfileState(
            id = 7,
            name = "STT Link",
            type = MihomoProfileType.Url,
            url = "https://example.com/subscription",
            updateViaProxy = true,
        )
        val state = AppState(
            mihomoProfiles = listOf(profile),
            selectedMihomoProfileId = profile.id,
        )

        val stoppedOptions = state.toSubscriptionFetchOptions(profile)
        val runningOptions = state.copy(proxyRunning = true).toSubscriptionFetchOptions(profile)

        assertTrue(stoppedOptions.useRunningProxy)
        assertFalse(stoppedOptions.proxyRunning)
        assertEquals(ProxyNotRunningFetchTimeoutMillis, stoppedOptions.fetchTimeoutMillis())
        assertTrue(runningOptions.proxyRunning)
        assertNull(runningOptions.fetchTimeoutMillis())
    }
}
