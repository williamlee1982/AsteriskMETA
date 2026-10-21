// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo

import app.AppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MihomoControlPortOwnershipTest {
    private val state = AppState().copy(mihomoControlPort = "35277")

    @Test
    fun portHeldByOurOwnRunningCoreIsKept() {
        val resolved = state.withResolvedMihomoControlPort(ownedPort = 35277) { false }
        assertEquals("35277", resolved.mihomoControlPort)
    }

    @Test
    fun portHeldBySomethingElseIsReplaced() {
        val resolved = state.withResolvedMihomoControlPort(ownedPort = null) { port -> port != 35277 }
        assertNotEquals("35277", resolved.mihomoControlPort)
    }
}
