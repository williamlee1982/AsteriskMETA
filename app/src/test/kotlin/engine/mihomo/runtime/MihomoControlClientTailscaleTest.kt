// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.runtime

import com.sun.net.httpserver.HttpServer
import engine.mihomo.MihomoControlConfig
import engine.mihomo.tailscale.MihomoTailscaleSecretRequiredException
import engine.mihomo.tailscale.MihomoTailscaleUnsupportedException
import engine.mihomo.tailscale.TailscaleBackendState
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class MihomoControlClientTailscaleTest {
    private lateinit var server: HttpServer
    private val requests = mutableListOf<String>()
    private val authorizations = mutableListOf<String?>()
    private var responses: Map<String, Pair<Int, String>> = emptyMap()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val key = "${exchange.requestMethod} ${exchange.requestURI.rawPath}" +
                (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
            synchronized(requests) {
                requests += key
                authorizations += exchange.requestHeaders.getFirst("Authorization")
            }
            val (status, body) = responses[key] ?: (404 to """{"message":"not found"}""")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun config(secret: String = "") =
        MihomoControlConfig(host = "127.0.0.1", port = server.address.port, secret = secret)

    @Test
    fun statusUsesTheControllerRouteWithTheSecret() {
        responses = mapOf("GET /asterisk/tailscale" to (200 to """{"outbounds":[{"name":"ts","state":"Starting","tailnetIps":[]}]}"""))
        val states = MihomoControlClient().getTailscaleStatuses(config("example-secret"), useBridge = false)
        assertEquals(mapOf("ts" to TailscaleBackendState.Starting), states)
        assertEquals(listOf<String?>("Bearer example-secret"), authorizations)
    }

    @Test
    fun missingListRouteMeansTheCoreLacksTheApi() {
        assertThrows(MihomoTailscaleUnsupportedException::class.java) {
            MihomoControlClient().getTailscaleStatuses(config(), useBridge = false)
        }
    }

    @Test
    fun loginLogoutAndLoginUrlEncodeTheOutboundName() {
        responses = mapOf(
            "POST /asterisk/tailscale/login?name=TS%20%E8%8A%82%E7%82%B9" to (202 to ""),
            "GET /asterisk/tailscale/login-url?name=TS%20%E8%8A%82%E7%82%B9" to (200 to """{"url":"https://login.example.test/a/abc"}"""),
            "POST /asterisk/tailscale/logout?name=TS%20%E8%8A%82%E7%82%B9" to (204 to ""),
        )
        val client = MihomoControlClient()
        client.startTailscaleLogin(config("s"), "TS 节点", useBridge = false)
        assertEquals("https://login.example.test/a/abc", client.tailscaleLoginUrl(config("s"), "TS 节点", useBridge = false))
        client.logoutTailscale(config("s"), "TS 节点", useBridge = false)
        assertEquals(3, requests.size)
    }

    @Test
    fun loginUrlWithoutSecretIsReportedAsSuch() {
        responses = mapOf("GET /asterisk/tailscale/login-url?name=ts" to (403 to """{"message":"controller secret required"}"""))
        assertThrows(MihomoTailscaleSecretRequiredException::class.java) {
            MihomoControlClient().tailscaleLoginUrl(config(), "ts", useBridge = false)
        }
    }

    @Test
    fun otherErrorsAreRedacted() {
        responses = mapOf(
            "POST /asterisk/tailscale/login?name=ts" to
                (502 to """{"message":"see https://login.example.test/a/0123456789abcdef"}"""),
        )
        val error = assertThrows(IllegalStateException::class.java) {
            MihomoControlClient().startTailscaleLogin(config(), "ts", useBridge = false)
        }
        assertFalse(error.message.orEmpty().contains("0123456789abcdef"))
    }
}
