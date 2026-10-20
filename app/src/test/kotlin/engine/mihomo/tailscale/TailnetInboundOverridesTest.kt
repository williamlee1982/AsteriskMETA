package engine.mihomo.tailscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetInboundOverridesTest {
    private fun profile() = linkedMapOf<String, Any?>(
        "proxies" to mutableListOf<Any?>(
            linkedMapOf<String, Any?>(
                "name" to "TS",
                "type" to "tailscale",
                "inbound-forwards" to listOf(linkedMapOf<String, Any?>("listen" to 20022, "target" to "127.0.0.1:22")),
                "allow-lan-target" to true,
            ),
            linkedMapOf<String, Any?>(
                "name" to "SOCKS",
                "type" to "socks5",
                "inbound-forwards" to listOf("untouched"),
            ),
        ),
    )

    @Test
    fun exposePortsFalseStripsForwardsFromEveryTailscaleProxy() {
        val root = profile()
        root.putTailnetInboundOverrides(exposePorts = false, enabled = true)
        val tailscale = (root["proxies"] as List<*>).first() as Map<*, *>
        assertFalse(tailscale.containsKey("inbound-forwards"))
        assertEquals(true, tailscale["allow-lan-target"])
    }

    @Test
    fun exposePortsTrueKeepsForwardsUnchangedWhenEnabled() {
        val root = profile()
        root.putTailnetInboundOverrides(exposePorts = true, enabled = true)
        val tailscale = (root["proxies"] as List<*>).first() as Map<*, *>
        assertTrue(tailscale.containsKey("inbound-forwards"))
    }

    @Test
    fun toggleOffStripsForwardsEvenOnActiveCore() {
        val root = profile()
        root.putTailnetInboundOverrides(exposePorts = true, enabled = false)
        val tailscale = (root["proxies"] as List<*>).first() as Map<*, *>
        assertFalse(tailscale.containsKey("inbound-forwards"))
    }

    @Test
    fun nonTailscaleProxiesAreUntouched() {
        val root = profile()
        val before = (root["proxies"] as List<*>)[1]
        root.putTailnetInboundOverrides(exposePorts = false, enabled = true)
        assertEquals(before, (root["proxies"] as List<*>)[1])
    }
}
