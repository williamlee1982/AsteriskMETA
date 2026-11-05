// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription.runtime

import app.AppState
import app.MihomoProfileState
import app.MihomoProfileType
import features.subscription.usecase.MihomoProfileSubscriptionFailure
import features.subscription.usecase.MihomoProfileSubscriptionUpdateResult
import features.subscription.usecase.MihomoProfileSyncStage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionWorkerRunnerTest {
    @Test
    fun fetchSkippedWhileTheCoreStartsIsRetried() = runBlocking {
        val runner = runnerFor(
            failure = SubscriptionFetchTimeoutException(ProxyNotRunningFetchTimeoutMillis),
        )

        assertEquals(SubscriptionWorkerResult.RETRY, runner.run(ProfileId))
    }

    @Test
    fun ordinaryFailureIsNotRetried() = runBlocking {
        val runner = runnerFor(failure = IllegalStateException("invalid profile"))

        assertEquals(SubscriptionWorkerResult.FAILURE, runner.run(ProfileId))
    }

    private fun runnerFor(failure: Throwable): SubscriptionWorkerRunner {
        val profile = MihomoProfileState(
            id = ProfileId,
            name = "STT Link",
            type = MihomoProfileType.Url,
            url = "https://example.com/subscription",
            updateInterval = "1",
        )
        val result = MihomoProfileSubscriptionUpdateResult(
            updates = emptyList(),
            failures = listOf(
                MihomoProfileSubscriptionFailure(
                    profileId = profile.id,
                    stage = MihomoProfileSyncStage.Downloading,
                    error = failure,
                ),
            ),
            updatedAtMillis = 0L,
        )
        return SubscriptionWorkerRunner(
            stateProvider = {
                AppState(
                    mihomoProfiles = listOf(profile),
                    selectedMihomoProfileId = profile.id,
                )
            },
            update = { result },
        )
    }

    private companion object {
        const val ProfileId = 7
    }
}
