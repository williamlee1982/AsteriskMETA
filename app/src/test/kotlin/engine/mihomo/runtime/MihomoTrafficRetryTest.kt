// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.runtime

import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MihomoTrafficRetryTest {
    @Test
    fun retryDelayBacksOffAndCaps() {
        assertEquals(1_000L, trafficCollectionRetryDelayMillis(1))
        assertEquals(2_000L, trafficCollectionRetryDelayMillis(2))
        assertEquals(4_000L, trafficCollectionRetryDelayMillis(3))
        assertEquals(8_000L, trafficCollectionRetryDelayMillis(4))
        assertEquals(16_000L, trafficCollectionRetryDelayMillis(5))
        assertEquals(16_000L, trafficCollectionRetryDelayMillis(9))
    }

    @Test
    fun connectFailureIsHardAndReadTimeoutStaysSoft() {
        assertTrue(ConnectException("timeout").isHardTrafficStreamFailure())
        assertTrue(ConnectException("Failed to connect to /127.0.0.1:33279").isHardTrafficStreamFailure())
        assertTrue(IllegalStateException("Mihomo API GET /traffic failed with HTTP 503").isHardTrafficStreamFailure())
        assertFalse(SocketTimeoutException("timeout").isHardTrafficStreamFailure())
        assertFalse(EOFException().isHardTrafficStreamFailure())
        assertFalse(SocketException("Connection reset").isHardTrafficStreamFailure())
        assertFalse(CancellationException("stopped").isHardTrafficStreamFailure())
    }

    @Test
    fun connectFailureRetriesUntilASampleArrives() = runBlocking {
        val totals = mutableListOf<Long>()
        val delays = mutableListOf<Long>()
        var calls = 0
        var failures = 0
        retryTrafficCollection(
            stillCurrent = { calls < 2 },
            onFailure = { error ->
                failures += 1
                assertTrue(error is ConnectException)
            },
            sleep = { delays += it },
            collectOnce = { markHealthy ->
                calls += 1
                if (calls == 1) throw ConnectException("Failed to connect to /127.0.0.1:33279")
                markHealthy()
                totals += 4_096L
            },
        )
        assertEquals(1, failures)
        assertEquals(listOf(1_000L), delays)
        assertEquals(listOf(4_096L), totals)
    }

    @Test
    fun recoveredSampleResetsBackoff() = runBlocking {
        val delays = mutableListOf<Long>()
        var phase = 0
        retryTrafficCollection(
            stillCurrent = { phase < 3 },
            onFailure = {},
            sleep = { delayMillis ->
                delays += delayMillis
                if (delays.size == 2) phase = 3
            },
            collectOnce = { markHealthy ->
                phase += 1
                if (phase == 1) throw ConnectException("refused")
                markHealthy()
                if (phase == 2) throw ConnectException("refused")
            },
        )
        assertEquals(listOf(1_000L, 1_000L), delays)
    }

    @Test
    fun cancellationDoesNotRetry() = runBlocking {
        var calls = 0
        var failed = false
        try {
            retryTrafficCollection(
                stillCurrent = { true },
                onFailure = { failed = true },
                sleep = { fail("sleep after cancellation") },
                collectOnce = { _ ->
                    calls += 1
                    throw CancellationException("stopped")
                },
            )
            fail("expected cancellation")
        } catch (error: CancellationException) {
            assertEquals("stopped", error.message)
        }
        assertEquals(1, calls)
        assertFalse(failed)
    }

    @Test
    fun staleCollectorDoesNotDelayAfterFailure() = runBlocking {
        var current = true
        var slept = false
        retryTrafficCollection(
            stillCurrent = { current },
            onFailure = { current = false },
            sleep = { slept = true },
            collectOnce = { _ -> throw ConnectException("refused") },
        )
        assertFalse(slept)
    }
}
