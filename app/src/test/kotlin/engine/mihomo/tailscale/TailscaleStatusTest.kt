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
        ).associate { it.key.name to it.state }

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
        val error = states.single().state as TailscaleBackendState.Error
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
        val available = coreStates("ts" to TailscaleBackendState.Starting)
        assertEquals(TailscaleStatusResult.Available(TailscaleBackendState.Starting), available.resultFor(TailnetOutboundKey("ts")))
        assertEquals(
            TailscaleStatusResult.Unavailable(TailscaleStatusUnavailableReason.NotInRunningConfig),
            available.resultFor(TailnetOutboundKey("other")),
        )
        assertEquals(
            "a provider member is a different outbound than a top-level one with the same name",
            TailscaleStatusResult.Unavailable(TailscaleStatusUnavailableReason.NotInRunningConfig),
            available.resultFor(TailnetOutboundKey("ts", provider = "sub")),
        )
        assertEquals(
            TailscaleStatusResult.Unavailable(TailscaleStatusUnavailableReason.CoreApiMissing),
            TailscaleStatusQuery.Unavailable(TailscaleStatusUnavailableReason.CoreApiMissing).resultFor(TailnetOutboundKey("ts")),
        )
    }

    @Test
    fun coreReportsProviderAndEffectiveConfig() {
        val outbounds = parseTailscaleCoreStatuses(
            """
            {"outbounds":[
              {"name":"ts","state":"Idle","tailnetIps":[],"provider":"subscription",
               "config":{"hostname":" phone ","stateDir":"/data/home/tailscale","stateDirDefault":true,"ephemeral":true,
                         "hasAuthKey":true,"customControlUrl":true,"controlUrlHost":"Control.Example.Test","futureField":1}},
              {"name":"ts","state":"Running","tailnetIps":[],"provider":"",
               "config":{"stateDir":"/data/home/ts-local","stateDirDefault":false,"ephemeral":false,"hasAuthKey":false,"customControlUrl":false}},
              {"name":"legacy","state":"Idle","tailnetIps":[]}
            ]}
            """.trimIndent(),
        )
        assertEquals(
            listOf(TailnetOutboundKey("ts", "subscription"), TailnetOutboundKey("ts"), TailnetOutboundKey("legacy")),
            outbounds.map { it.key },
        )
        assertEquals(
            MihomoTailscaleOutbound(
                name = "ts",
                hostname = "phone",
                stateDir = "/data/home/tailscale",
                hasAuthKey = true,
                ephemeral = true,
                usesCustomControlUrl = true,
                controlUrlHost = "control.example.test",
                provider = "subscription",
                stateDirIsDefault = true,
            ),
            outbounds[0].config,
        )
        assertEquals("an empty provider means a top-level outbound", null, outbounds[1].config?.provider)
        assertEquals("/data/home/ts-local", outbounds[1].config?.stateDir)
        assertNull("older cores report no config", outbounds[2].config)
    }

    @Test
    fun parsesInboundForwardStatesAndRedactsErrors() {
        val outbound = parseTailscaleCoreStatuses(
            """
            {"outbounds":[{"name":"ts","state":"Running","tailnetIps":["100.101.102.103"],
              "inboundForwards":[
                {"name":"ssh","network":"tcp","listen":20022,"target":"127.0.0.1:22","state":"Listening","activeConns":1,"totalConns":2},
                {"name":"bad","network":"tcp","listen":20023,"target":"127.0.0.1:23","state":"Error","error":"secret https://login.example.test/a/0123456789abcdef","activeConns":0,"totalConns":1},
                {"name":"wait","network":"tcp","listen":20024,"target":"127.0.0.1:24","state":"Waiting"}
              ]}]}
            """.trimIndent(),
        ).single()
        val running = outbound.state as TailscaleBackendState.Running
        assertEquals(3, running.inboundForwards?.size)
        assertEquals(TailscaleInboundForwardState.Listening, running.inboundForwards?.get(0)?.state)
        assertEquals(TailscaleInboundForwardState.Waiting, running.inboundForwards?.get(2)?.state)
        assertFalse(running.inboundForwards?.get(1)?.error.orEmpty().contains("0123456789abcdef"))
        assertEquals(1, running.inboundForwards?.get(0)?.activeConns)
    }

    @Test
    fun missingInboundForwardFieldMeansUnsupportedCore() {
        val running = parseTailscaleCoreStatuses(
            """{"outbounds":[{"name":"ts","state":"Running"}]}""",
        ).single().state as TailscaleBackendState.Running
        assertNull(running.inboundForwards)
    }

    @Test
    fun unknownInboundForwardStateMapsToError() {
        val running = parseTailscaleCoreStatuses(
            """{"outbounds":[{"name":"ts","state":"Running","inboundForwards":[{"listen":20022,"target":"127.0.0.1:22","state":"Later"}]}]}""",
        ).single().state as TailscaleBackendState.Running
        assertEquals(TailscaleInboundForwardState.Error, running.inboundForwards?.single()?.state)
    }

    @Test
    fun duplicateCoreEntriesAreReportedOnce() {
        val outbounds = parseTailscaleCoreStatuses(
            """{"outbounds":[{"name":"ts","state":"Idle"},{"name":"ts","state":"Running"},{"name":"ts","state":"Idle","provider":"p"}]}""",
        )
        assertEquals(listOf(TailnetOutboundKey("ts"), TailnetOutboundKey("ts", "p")), outbounds.map { it.key })
        assertEquals(TailscaleBackendState.Idle, outbounds[0].state)
    }

    @Test
    fun customControlUrlWithoutHostAllowsNoLoginHost() {
        val outbound = parseTailscaleCoreStatuses(
            """{"outbounds":[{"name":"ts","state":"Idle","config":{"customControlUrl":true}}]}""",
        ).single().config!!
        assertEquals(emptySet<String>(), outbound.allowedLoginHosts())
    }
}
