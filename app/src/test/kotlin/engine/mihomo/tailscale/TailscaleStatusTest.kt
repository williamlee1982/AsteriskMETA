// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleStatusTest {
    @Test
    fun coreStatusJsonMapsToTypedStates() {
        val states = parseTailscaleCoreStatuses(
            """
            {"outbounds":[
              {"name":"idle","state":"Idle","tailnetIps":[]},
              {"name":"starting","state":"Starting","tailnetIps":[]},
              {"name":"stopped","state":"Stopped","tailnetIps":[]},
              {"name":"login","state":"NeedsLogin","loginUrlAvailable":true,"tailnetIps":[]},
              {"name":"login-pending","state":"NeedsLogin","loginUrlAvailable":false,"tailnetIps":[]},
              {"name":"approval","state":"NeedsMachineAuth","loginUrlAvailable":true,"tailnetIps":[]},
              {"name":"running","state":"Running","tailnetIps":[" 100.101.102.103 ","fd7a:115c:a1e0::1",""],
               "hostname":" example-node ","dnsName":"example-node.example-tailnet.ts.net.","magicDnsSuffix":"example-tailnet.ts.net.",
               "futureField":1},
              {"name":"broken","state":"Error","tailnetIps":[],"error":"dial failed"},
              {"name":"future","state":"SomethingNew","tailnetIps":[]},
              {"name":"","state":"Running","tailnetIps":[]}
            ]}
            """.trimIndent(),
        )

        assertEquals(TailscaleBackendState.Idle, states["idle"])
        assertEquals(TailscaleBackendState.Starting, states["starting"])
        assertEquals(TailscaleBackendState.Stopped, states["stopped"])
        assertEquals(TailscaleBackendState.NeedsLogin(loginUrlAvailable = true), states["login"])
        assertEquals(TailscaleBackendState.NeedsLogin(loginUrlAvailable = false), states["login-pending"])
        assertEquals(
            "a login URL is never offered while waiting for admin approval",
            TailscaleBackendState.NeedsLogin(loginUrlAvailable = false, machineAuthorization = true),
            states["approval"],
        )
        assertEquals(
            TailscaleBackendState.Running(
                tailnetAddresses = listOf("100.101.102.103", "fd7a:115c:a1e0::1"),
                hostname = "example-node",
                dnsName = "example-node.example-tailnet.ts.net",
                magicDnsSuffix = "example-tailnet.ts.net",
            ),
            states["running"],
        )
        assertEquals(TailscaleBackendState.Error("dial failed"), states["broken"])
        assertTrue(states["future"] is TailscaleBackendState.Error)
        assertFalse("unnamed entries are dropped", states.containsKey(""))
    }

    @Test
    fun coreErrorsAreRedactedAgain() {
        val states = parseTailscaleCoreStatuses(
            """{"outbounds":[{"name":"ts","state":"Error","error":"visit https://login.example.test/a/0123456789abcdef now"}]}""",
        )
        val error = states.getValue("ts") as TailscaleBackendState.Error
        assertFalse(error.redactedMessage.contains("0123456789abcdef"))
    }

    @Test
    fun loginUrlResponseParses() {
        assertEquals("https://login.example.test/a/abc", parseTailscaleLoginUrl("""{"url":"https://login.example.test/a/abc"}"""))
    }

    @Test
    fun loginUrlsMustBeHttpsOnAnAllowedHost() {
        val hosts = setOf("login.example.test")
        assertEquals(
            "login.example.test",
            validatedTailscaleLoginUri("https://login.example.test/a/abc", hosts)?.host,
        )
        assertEquals("login.example.test", validatedTailscaleLoginUri("https://LOGIN.example.test/a/abc", hosts)?.host?.lowercase())
        assertNull(validatedTailscaleLoginUri("http://login.example.test/a/abc", hosts))
        assertNull(validatedTailscaleLoginUri("https://evil.example.test/a/abc", hosts))
        assertNull(validatedTailscaleLoginUri("https://login.example.test.evil.test/a/abc", hosts))
        assertNull(validatedTailscaleLoginUri("https://user@login.example.test/a/abc", hosts))
        assertNull(validatedTailscaleLoginUri("intent://login.example.test/#Intent;end", hosts))
        assertNull(validatedTailscaleLoginUri("/a/abc", hosts))
        assertNull(validatedTailscaleLoginUri("not a url", hosts))
        assertNull(validatedTailscaleLoginUri("https://login.example.test/a/abc", emptySet()))
    }

    @Test
    fun allowedLoginHostsFollowTheControlServer() {
        val default = MihomoTailscaleOutbound("ts", null, null, hasAuthKey = false, ephemeral = false, usesCustomControlUrl = false)
        assertEquals(setOf(DefaultTailscaleLoginHost), default.allowedLoginHosts())
        val custom = default.copy(usesCustomControlUrl = true, controlUrlHost = "headscale.example.test")
        assertEquals(setOf("headscale.example.test"), custom.allowedLoginHosts())
        assertEquals("unparseable custom control URL allows nothing", emptySet<String>(), default.copy(usesCustomControlUrl = true).allowedLoginHosts())
    }

    @Test
    fun pollResultsResolvePerOutbound() {
        val available = TailscaleStatusQuery.Available(mapOf("ts" to TailscaleBackendState.Starting))
        assertEquals(TailscaleStatusResult.Available(TailscaleBackendState.Starting), available.resultFor("ts"))
        assertEquals(
            TailscaleStatusResult.Unavailable(TailscaleStatusUnavailableReason.NotInRunningConfig),
            available.resultFor("other"),
        )
        assertEquals(
            TailscaleStatusResult.Unavailable(TailscaleStatusUnavailableReason.CoreApiMissing),
            TailscaleStatusQuery.Unavailable(TailscaleStatusUnavailableReason.CoreApiMissing).resultFor("ts"),
        )
    }
}
