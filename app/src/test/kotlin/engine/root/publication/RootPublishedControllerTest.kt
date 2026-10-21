// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.publication

import engine.mihomo.MihomoControlConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RootPublishedControllerTest {
    @Test
    fun readsTopLevelControllerAndSecretOnly() {
        val published = RootPublishedControllerReader.parse(
            sequenceOf(
                "# external-controller: 127.0.0.1:1",
                "listeners:",
                "  - external-controller: 127.0.0.1:2",
                "external-controller: '127.0.0.1:35277'",
                "secret: \"s3\"",
            ),
        )
        assertEquals(RootPublishedController(35277, "s3", "127.0.0.1"), published)
        assertFalse(published.toString().contains("s3"))
    }

    @Test
    fun readsBracketedLoopbackHost() {
        val published = RootPublishedControllerReader.parse(
            sequenceOf("external-controller: '[::1]:9090'", "secret: s3"),
        )
        assertEquals(RootPublishedController(9090, "s3", "::1"), published)
    }

    @Test
    fun missingOrInvalidControllerIsIgnored() {
        assertNull(RootPublishedControllerReader.parse(sequenceOf("secret: x")))
        assertNull(RootPublishedControllerReader.parse(sequenceOf("external-controller: 127.0.0.1:99999")))
    }

    @Test
    fun publishedControllerOverridesStaleAppPort() {
        val stale = MihomoControlConfig(port = 33279, secret = "s3")
        assertEquals(35277, stale.withPublishedRootController(RootPublishedController(35277, null)).port)
        assertEquals("s3", stale.withPublishedRootController(RootPublishedController(35277, null)).secret)
        assertSame(stale, stale.withPublishedRootController(null))
    }

    @Test
    fun rootModePrefersPublishedEndpointBeforeRunningStateIsRestored() {
        val stale = MihomoControlConfig(host = "127.0.0.1", port = 33279, secret = "old")
        val published = RootPublishedController(port = 35277, secret = "s3", host = "127.0.0.1")
        val resolved = resolveRootControlEndpoint(
            configured = stale,
            rootMode = true,
            published = published,
        )
        assertEquals("127.0.0.1", resolved.host)
        assertEquals(35277, resolved.port)
        assertEquals("s3", resolved.secret)
        assertFalse(resolved.toString().contains("s3"))
        assertSame(stale, resolveRootControlEndpoint(stale, rootMode = true, published = null))
    }

    @Test
    fun nonRootModeIgnoresPublishedController() {
        val configured = MihomoControlConfig(host = "127.0.0.1", port = 33279, secret = "old")
        val published = RootPublishedController(port = 35277, secret = "s3", host = "10.0.0.8")
        assertSame(
            configured,
            resolveRootControlEndpoint(configured, rootMode = false, published = published),
        )
    }

    @Test
    fun wildcardBindKeepsLoopbackClientHost() {
        val configured = MihomoControlConfig(host = "127.0.0.1", port = 33279, secret = "old")
        val resolved = resolveRootControlEndpoint(
            configured = configured,
            rootMode = true,
            published = RootPublishedController(port = 35277, secret = "s3", host = "0.0.0.0"),
        )
        assertEquals("127.0.0.1", resolved.host)
        assertEquals(35277, resolved.port)
        assertEquals("s3", resolved.secret)

        val ipv6Wildcard = RootPublishedControllerReader.parse(
            sequenceOf("external-controller: '[::]:35277'", "secret: s3"),
        )
        val fromFile = resolveRootControlEndpoint(configured, rootMode = true, ipv6Wildcard)
        assertEquals("127.0.0.1", fromFile.host)
        assertEquals(35277, fromFile.port)
    }
}
