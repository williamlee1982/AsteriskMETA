// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo

import app.AppState
import app.modes.RunModeTproxy
import app.modes.RunModeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.snakeyaml.engine.v2.api.Load

/** Review H4: a ROOT-mode core must never expose its controller without a secret. */
class ManagedControllerSecretTest {
    @Test
    fun rootModeRefusesBlankManagedSecret() {
        val error = assertThrows(IllegalStateException::class.java) { render(AppState(), RunModeTproxy) }
        assertEquals(true, error.message!!.contains("secret"))
    }

    @Test
    fun rootModeCarriesTheManagedSecret() {
        val runtime = render(AppState(mihomoControlSecret = "fixture-secret"), RunModeTproxy)
        assertEquals("fixture-secret", runtime["secret"])
    }

    @Test
    fun vpnModeKeepsUpstreamBehaviour() {
        render(AppState(), RunModeVpnService)
    }

    private fun render(appState: AppState, runMode: Int): Map<*, *> {
        val yaml = linkedMapOf<String, Any?>("rules" to listOf("MATCH,DIRECT"))
            .toAsteriskRuntimeProfileYaml(appState, runMode, tunApplicationUids = emptyList(), useRootProviderPaths = false)
        return Load(MihomoYamlLoadSettings).loadFromString(yaml) as Map<*, *>
    }
}
