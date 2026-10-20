package engine.mihomo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A1: raw (overrides disabled) profiles stay byte-identical unless a forward has to be stripped. */
class RawProfileInboundStripTest {
    @Test
    fun profileWithoutForwardsIsPassedThroughByteForByte() {
        val yaml = """
            # comment kept
            base: &base {type: tailscale, udp: yes}
            proxies:
              - <<: *base
                name: TS
                state-dir: ./ts
            rules: ["MATCH,DIRECT"]
        """.trimIndent().toByteArray()
        assertArrayEquals(yaml, stripTailnetInboundForwardsFromRawProfile(yaml))
    }

    @Test
    fun forwardsAreRemovedWhenPresent() {
        val yaml = """
            proxies:
              - name: TS
                type: tailscale
                inbound-forwards:
                  - {listen: 8022, target: "127.0.0.1:8022"}
        """.trimIndent().toByteArray()
        val stripped = stripTailnetInboundForwardsFromRawProfile(yaml).toString(Charsets.UTF_8)
        assertFalse(stripped.contains("inbound-forwards"))
        assertTrue(stripped.contains("TS"))
    }

    @Test
    fun emptyProfileStillFailsWithTheEmptyProfileError() {
        val error = runCatching { stripTailnetInboundForwardsFromRawProfile("  \n".toByteArray()) }.exceptionOrNull()
        assertEquals(MihomoProfileEmptyErrorMessage, error?.message)
    }
}
