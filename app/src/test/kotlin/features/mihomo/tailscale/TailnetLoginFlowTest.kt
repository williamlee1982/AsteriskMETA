// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.mihomo.tailscale

import engine.mihomo.tailscale.MihomoTailscaleSecretRequiredException
import engine.mihomo.tailscale.MihomoTailscaleUnsupportedException
import engine.mihomo.tailscale.TailscaleBackendState
import engine.mihomo.tailscale.TailscaleStatusQuery
import engine.mihomo.tailscale.TailscaleStatusUnavailableReason
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetLoginFlowTest {
    private class FakeActions(
        polls: List<TailscaleStatusQuery>,
        private val loginResult: Result<Unit> = Result.success(Unit),
        private val loginUrlResult: Result<String> = Result.success("https://login.example.test/a/0123456789abcdef"),
        private val logoutResult: Result<Unit> = Result.success(Unit),
    ) : TailnetControlActions {
        private val remaining = ArrayDeque(polls)
        val calls = mutableListOf<String>()

        override suspend fun statuses(): TailscaleStatusQuery {
            calls += "status"
            return if (remaining.size > 1) remaining.removeFirst() else remaining.first()
        }

        override suspend fun startLogin(outboundName: String): Result<Unit> = loginResult.also { calls += "login:$outboundName" }
        override suspend fun loginUrl(outboundName: String): Result<String> = loginUrlResult.also { calls += "login-url:$outboundName" }
        override suspend fun logout(outboundName: String): Result<Unit> = logoutResult.also { calls += "logout:$outboundName" }
    }

    private fun state(value: TailscaleBackendState) = TailscaleStatusQuery.Available(mapOf("ts" to value))

    private fun login(actions: FakeActions) = runBlocking {
        runTailnetLogin(actions, "ts", setOf("login.example.test"), pollInterval = 1.milliseconds, timeout = 50.milliseconds)
    }

    @Test
    fun waitsForTheLoginUrlAndValidatesIt() {
        val actions = FakeActions(
            listOf(
                state(TailscaleBackendState.Idle),
                state(TailscaleBackendState.Starting),
                state(TailscaleBackendState.NeedsLogin(loginUrlAvailable = false)),
                state(TailscaleBackendState.NeedsLogin(loginUrlAvailable = true)),
            ),
        )
        val outcome = login(actions)
        assertTrue(outcome is TailnetLoginOutcome.OpenLoginPage)
        assertEquals("login.example.test", (outcome as TailnetLoginOutcome.OpenLoginPage).uri.host)
        assertEquals(listOf("login:ts", "status", "status", "status", "status", "login-url:ts"), actions.calls)
        assertFalse("the outcome must not print the secret URL", outcome.toString().contains("0123456789abcdef"))
    }

    @Test
    fun rejectsLoginUrlsForUnexpectedHosts() {
        val actions = FakeActions(
            listOf(state(TailscaleBackendState.NeedsLogin(true))),
            loginUrlResult = Result.success("https://phish.example.test/a/abc"),
        )
        assertEquals(TailnetLoginOutcome.Failed(TailnetActionFailure.InvalidLoginUrl), login(actions))
    }

    @Test
    fun terminalStatesEndTheFlow() {
        assertEquals(TailnetLoginOutcome.AlreadyRunning, login(FakeActions(listOf(state(TailscaleBackendState.Running(emptyList(), null))))))
        assertEquals(
            TailnetLoginOutcome.MachineAuthorizationRequired,
            login(FakeActions(listOf(state(TailscaleBackendState.NeedsLogin(false, machineAuthorization = true))))),
        )
        assertEquals(
            TailnetLoginOutcome.Failed(TailnetActionFailure.NotInRunningConfig),
            login(FakeActions(listOf(TailscaleStatusQuery.Available(emptyMap())))),
        )
        assertEquals(
            TailnetLoginOutcome.Failed(TailnetActionFailure.CoreApiMissing),
            login(FakeActions(listOf(TailscaleStatusQuery.Unavailable(TailscaleStatusUnavailableReason.CoreApiMissing)))),
        )
    }

    @Test
    fun timesOutWhenNoUrlAppears() {
        val actions = FakeActions(listOf(state(TailscaleBackendState.Starting)))
        assertEquals(TailnetLoginOutcome.Failed(TailnetActionFailure.TimedOut), login(actions))
        assertFalse(actions.calls.contains("login-url:ts"))
    }

    @Test
    fun mapsActionErrors() {
        assertEquals(
            TailnetLoginOutcome.Failed(TailnetActionFailure.CoreApiMissing),
            login(FakeActions(listOf(state(TailscaleBackendState.Idle)), loginResult = Result.failure(MihomoTailscaleUnsupportedException()))),
        )
        assertEquals(
            TailnetLoginOutcome.Failed(TailnetActionFailure.SecretRequired),
            login(
                FakeActions(
                    listOf(state(TailscaleBackendState.NeedsLogin(true))),
                    loginUrlResult = Result.failure(MihomoTailscaleSecretRequiredException()),
                ),
            ),
        )
        assertEquals(
            TailnetLoginOutcome.Failed(TailnetActionFailure.ActionFailed),
            login(FakeActions(listOf(state(TailscaleBackendState.Idle)), loginResult = Result.failure(IllegalStateException("x")))),
        )
    }

    @Test
    fun logoutReportsFailures() = runBlocking {
        assertNull(runTailnetLogout(FakeActions(listOf(state(TailscaleBackendState.Idle))), "ts"))
        assertEquals(
            TailnetActionFailure.ActionFailed,
            runTailnetLogout(FakeActions(listOf(state(TailscaleBackendState.Idle)), logoutResult = Result.failure(IllegalStateException())), "ts"),
        )
    }
}
