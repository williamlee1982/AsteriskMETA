// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo.tailscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MihomoTailscaleOutboundTest {
    @Test
    fun extractsOnlyTailscaleOutboundsWithoutSecrets() {
        val outbounds = parseMihomoTailscaleOutbounds(
            """
            mixed-port: 7890
            proxies:
              - name: TS
                type: tailscale
                hostname: example-node
                state-dir: ./tailscale-example-node
                auth-key: $FakeSecret
                control-url: https://control.example.net
                udp: true
              - name: Second
                type: Tailscale
                ephemeral: true
              - name: plain
                type: socks5
                server: 192.0.2.1
                port: 1080
              - type: tailscale
            """.trimIndent(),
        )

        assertEquals(listOf("TS", "Second"), outbounds.map { it.name })
        val first = outbounds[0]
        assertEquals("example-node", first.hostname)
        assertEquals("./tailscale-example-node", first.stateDir)
        assertTrue(first.hasAuthKey)
        assertTrue(first.usesCustomControlUrl)
        assertEquals("only the host of control-url is kept", "control.example.net", first.controlUrlHost)
        assertFalse(first.ephemeral)
        assertFalse(first.toString().contains(FakeSecret))
        assertEquals(MihomoTailscaleOutbound("Second", null, null, false, true, false), outbounds[1])
    }

    @Test
    fun invalidOrNonObjectYamlYieldsNoOutbounds() {
        assertTrue(parseMihomoTailscaleOutbounds("proxies: [").isEmpty())
        assertTrue(parseMihomoTailscaleOutbounds("- just\n- a list").isEmpty())
        assertTrue(parseMihomoTailscaleOutbounds("proxies: not-a-list").isEmpty())
    }

    @Test
    fun parsesInboundForwardsAndDefaultsNameAndNetwork() {
        val outbound = parseMihomoTailscaleOutbounds(
            """
            proxies:
              - name: TS
                type: tailscale
                inbound-forwards:
                  - listen: 20022
                    target: 127.0.0.1:22
                  - name: adb
                    network: tcp
                    listen: 20555
                    target: 127.0.0.1:5555
            """.trimIndent(),
        ).single()

        assertEquals(
            listOf(
                TailnetInboundForward(name = "20022", network = "tcp", listen = 20022, target = "127.0.0.1:22"),
                TailnetInboundForward(name = "adb", network = "tcp", listen = 20555, target = "127.0.0.1:5555"),
            ),
            outbound.inboundForwards,
        )
    }

    @Test
    fun invalidInboundEntriesAreReportedNotDropped() {
        val forwards = parseMihomoTailscaleOutbounds(
            """
            proxies:
              - name: TS
                type: tailscale
                inbound-forwards:
                  - listen: nope
                    target: example.test:22
            """.trimIndent(),
        ).single().inboundForwards

        assertEquals(1, forwards.size)
        assertTrue(forwards.single().error?.isNotBlank() == true)
    }

    @Test
    fun malformedInboundForwardsValueIsReportedNotDropped() {
        val forwards = parseMihomoTailscaleOutbounds(
            """
            proxies:
              - name: TS
                type: tailscale
                inbound-forwards: invalid
            """.trimIndent(),
        ).single().inboundForwards

        assertEquals(1, forwards.size)
        assertTrue(forwards.single().error?.contains("list") == true)
    }

    private companion object {
        const val FakeSecret = "fixture-secret-value"
    }
}
