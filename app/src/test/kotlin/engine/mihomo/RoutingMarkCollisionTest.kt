// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.mihomo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** H6: routing-mark values carrying Asterisk mark bits (0x60000000) are stripped in ROOT modes. */
class RoutingMarkCollisionTest {
    @Test
    fun profileWithoutCollisionIsByteIdentical() {
        val yaml = "# keep\nrouting-mark: 6666\nproxies:\n  - {name: a, type: ss, routing-mark: 0x1000}\n".toByteArray()
        assertArrayEquals(yaml, stripAsteriskRoutingMarkCollisions(yaml))
        val plain = "proxies: []\n".toByteArray()
        assertArrayEquals(plain, stripAsteriskRoutingMarkCollisions(plain))
    }

    @Test
    fun collidingTopLevelAndProxyMarksAreRemoved() {
        val yaml = """
            routing-mark: 0x20000000
            proxies:
              - {name: a, type: ss, routing-mark: 1610612736}
              - {name: b, type: ss, routing-mark: 255}
              - {name: c, type: ss, routing-mark: "0x40000001"}
        """.trimIndent().toByteArray()
        val out = stripAsteriskRoutingMarkCollisions(yaml).toString(Charsets.UTF_8)
        assertFalse(out.contains("536870912"))
        assertFalse(out.contains("0x20000000"))
        assertFalse(out.contains("1610612736"))
        assertFalse(out.contains("0x40000001"))
        assertTrue(out.contains("255"))
        assertTrue(out.contains("name: c") || out.contains("name: \"c\"") || out.contains("c"))
    }

    @Test
    fun collisionDetection() {
        assertTrue(routingMarkCollidesWithAsterisk(0x20000000L))
        assertTrue(routingMarkCollidesWithAsterisk(0x40000000L))
        assertFalse(routingMarkCollidesWithAsterisk(0x1FFFFFFFL))
        assertFalse(routingMarkCollidesWithAsterisk(0x80000000L))
    }
}
